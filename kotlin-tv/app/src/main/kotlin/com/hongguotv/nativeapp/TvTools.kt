// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hongguotv.core.PhoneSearchServer
import com.hongguotv.core.LibraryTransferServer
import com.hongguotv.core.BackupData
import java.net.Inet4Address
import java.util.concurrent.Executors

/**
 * 原生模态交互：手机输入、备份恢复、二次确认、列表选择。
 *
 * 全部走 [TvDialog]，因此弹窗里的按钮与页面上是同一套尺寸、圆角与焦点反馈——
 * 这里不再出现系统 AlertDialog 的 Material 文字按钮。
 */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: TvDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    /** 弹窗正文文字：与全站正文同一档字号与颜色，不再单独写一套白色 + 内边距。 */
    private fun label(value: String,size: Float=16f)=activity.tvText(value,size,TvStyle.textDim)
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun open(next: TvDialog,anchor: View?) {
        dialog?.dismiss(); dialog=next
        next.show(anchor) { if(dialog===next) { phone?.close(); phone=null; dialog=null } }
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); worker.shutdownNow() }
    fun info(title: String,message: String,anchor: View?) {
        val next=TvDialog(activity,title).message(message)
        next.button("知道了",ButtonTone.PRIMARY)
        open(next,anchor)
    }
    /** 二次确认：焦点默认落在「取消」上，确认必须多按一次，误触不会造成破坏。 */
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=TvDialog(activity,title).message(message)
        next.button("取消") {}
        next.button("确认",ButtonTone.PRIMARY) { action() }
        open(next,anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val next=TvDialog(activity,title).choices(labels,onChoice=onChoice)
        if(extra!=null) next.button(extra) { onExtra() }
        next.button("取消") {}
        open(next,anchor)
    }
    /**
     * 二维码：一块嵌在弹窗里的白色凸起方板。
     * 新拟态里「白」是最亮的一档材质，因此二维码不用描边也压得住深色弹窗，
     * 四周的柔光带同时也是二维码的静区（quiet zone），扫起来更稳。
     */
    private fun qr(url: String): FrameLayout {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        val code=ImageView(activity).apply {
            setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
            contentDescription="二维码"
        }
        val inset=dp(TvStyle.SOFT_BAND)+dp(TvStyle.GAP_TIGHT)
        return FrameLayout(activity).apply {
            background=TvStyle.soft(activity,Color.WHITE,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
            setPadding(inset,inset,inset,inset)
            addView(code,FrameLayout.LayoutParams(-1,-1))
        }
    }
    /**
     * 电视在局域网里的 IPv4 地址：手机表单（手机输入 / 登录态 / 备份恢复）都要用它拼二维码。
     * 没有可用的 Wi-Fi 或有线网络时返回 null，由调用方给出各自的提示。
     */
    private fun lanAddress(): String? {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
        if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    /** 二维码 + 说明文字的通用正文：两处扫码弹窗共用同一份排版与间距。 */
    private fun scanBody(instructions: String,url: String,into: LinearLayout) {
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        val side=dp(185+2*(TvStyle.GAP_TIGHT+TvStyle.SOFT_BAND))
        row.addView(qr(url),LinearLayout.LayoutParams(side,side))
        // 说明文字放进一条凹陷槽：与二维码的凸起方板形成一凹一凸，弹窗里两块内容一眼分得开。
        val band=dp(TvStyle.SOFT_BAND)
        val text=column().apply {
            background=TvStyle.well(activity,TvStyle.well,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND)
            setPadding(band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE))
        }
        text.addView(label(instructions,16f))
        text.addView(label(url,12f),LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) })
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(TvStyle.GAP_BLOCK) })
        into.addView(row)
    }
    fun phoneInput(anchor: View?,submit: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("手机输入","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机输入剧名")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ query -> main.post { if(dialog===next) { next.dismiss(); submit(query) } } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码后用浏览器打开，输入剧名发送到电视。\n需连接同一局域网；关闭窗口即失效，最长 5 分钟。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启手机输入，请检查网络后重试。" } }
        }
    }
    /**
     * 搜索登录态（可选）：说明 + 手机填写 + 清除。
     *
     * 不填也能搜索：搜索会退回官网搜索 + 片库目录补页。填上 App 抓到的 `x-tt-token` 后，
     * 搜索直接使用 App 搜索接口（按短剧 / 漫剧分 tab、可继续翻页）。口令在手机上粘贴。
     */
    fun searchSession(anchor: View?,current: String,save: (String)->Unit,clear: ()->Unit) {
        val next=TvDialog(activity,"搜索登录态")
        next.message("可选。不填也能搜索：搜索会退回官网搜索（第 1 页约 10 条），第 2 页起从片库目录里补。\n\n填上 App 抓到的 x-tt-token 后，搜索直接使用 App 搜索接口：按短剧 / 漫剧分开、一次 20 条、可以一直往下翻。\n\n口令只保存在本机、只用于搜索请求，不进备份文件。")
        next.button("取消") {}
        if(current.isNotBlank()) next.button("清除口令") { clear() }
        next.button("手机填写",ButtonTone.PRIMARY) { sessionToken(anchor) { save(it) } }
        open(next,anchor)
    }
    /**
     * 手机填写搜索登录态：与「手机输入」共用同一份一次性局域网表单。
     * 口令是上百个字符的串，在手机上粘贴比用遥控器一个字一个字敲快得多。
     */
    fun sessionToken(anchor: View?,save: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("搜索登录态","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"填写搜索登录态")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ token -> main.post { if(dialog===next) { next.dismiss(); save(token) } } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } },form=PhoneSearchServer.Form.sessionToken)
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码后用浏览器打开，粘贴 App 抓到的 x-tt-token（整段 Cookie 也可以）。\n只用于搜索请求；需连接同一局域网，关闭窗口即失效，最长 5 分钟。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启手机填写，请检查网络后重试。" } }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机备份与恢复")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val confirm=TvDialog(activity,"确认恢复本机记录")
                    confirm.message("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后看、${data.hidden.size} 部已隐藏推荐。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。")
                    // 开关用全站统一的按钮：选中态是「按进去」的凹槽（不带颜色），
                    // 系统 CheckBox 的勾选色是自带的一套，与「选框不带颜色」的规则冲突。
                    var restoreSettings=false
                    lateinit var settings: ToggleChip
                    settings=ToggleChip(activity,"同时恢复播放设置和内容分类 · 关",false) {
                        restoreSettings=!restoreSettings
                        settings.setActive(restoreSettings)
                        settings.setLabel("同时恢复播放设置和内容分类 · ${if(restoreSettings) "开" else "关"}")
                    }
                    confirm.content.addView(settings,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
                    confirm.button("取消") {}
                    confirm.button("合并恢复",ButtonTone.PRIMARY) { restore(data,restoreSettings) }
                    open(confirm,anchor)
                } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
