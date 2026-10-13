// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.core.content.FileProvider
import com.hgtv.star.core.AppUpdate
import com.hgtv.star.core.AppUpdateRepository
import com.hgtv.star.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 应用内更新：检查新版本 → 下载 → 校验 → 交给系统安装器覆盖安装。
 *
 * 设计约束（与项目既有约定一致）：
 * - **只在前台、且不在播放时**发请求：播放中、选集面板打开时绝不打扰。
 * - 检查走 GitHub Release 的 `update.json`；下载前先比 versionCode，再比 minSdk。
 * - 下载完必须过 SHA-256 与「包名 / 版本 / 签名一致」两道校验，任一不符直接阻止安装。
 * - 弹窗用 [TvDialog]，与全站按钮同一套外观与焦点规则；不引入系统 AlertDialog。
 */
class AppUpdater(private val activity: Activity, private val idle: () -> Boolean) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val repository = AppUpdateRepository()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")

    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var retryAfter = 0L
    private var promptedCode = 0L
    private var status = "当前版本 ${BuildConfig.VERSION_NAME}"

    private var dialog: TvDialog? = null
    private var statusView: android.widget.TextView? = null
    private var actionButton: TvButton? = null
    private var autoBox: android.widget.CheckBox? = null
    private var anchor: View? = null

    val showing get() = dialog?.isShowing == true
    private val auto get() = preferences.getBoolean("automatic", true)

    /**
     * 前台轮询：每 15 秒看一次，满足「空闲 + 没有弹窗 + 开了自动更新」才动。
     * 有新版先下载，下好再提示；距上次尝试超过 6 小时才重新联网检查。
     */
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (idle() && dialog == null && !busy && auto) {
                val now = System.currentTimeMillis()
                val last = preferences.getLong("last_attempt", 0)
                val update = available
                when {
                    ready && update != null && promptedCode != update.versionCode && !deferred(update) -> {
                        promptedCode = update.versionCode
                        show(activity.currentFocus)
                    }
                    update != null && !ready && now >= retryAfter -> download(false)
                    now < last || now - last >= 6 * 60 * 60 * 1000L -> check(false)
                }
            }
            main.postDelayed(this, 15000)
        }
    }

    fun resume() {
        active = true
        main.removeCallbacks(poll)
        main.postDelayed(poll, 8000)
        if (permissionRequested) {
            permissionRequested = false
            if (activity.packageManager.canRequestPackageInstalls()) install()
            else { status = "安装权限未开启，可在这里再次安装"; show(anchor) }
        }
    }

    fun pause() {
        active = false
        main.removeCallbacks(poll)
        interrupt()
        dialog?.dismiss()
    }

    fun interrupt() {
        generation++
        task?.cancel(true)
        task = null
        repository.cancel()
        busy = false
    }

    fun destroy() {
        pause()
        worker.shutdownNow()
        // TLS 连接的关闭可能发 close_notify，不能放在主线程上做。
        Thread({ repository.close() }, "hgtv-update-cleanup").start()
    }

    // —— 状态与文案 ——

    private fun updateStatus(text: String) {
        status = text
        statusView?.text = text
    }

    private fun deferred(update: AppUpdate) =
        preferences.getLong("deferred_code", 0) == update.versionCode &&
            preferences.getLong("deferred_until", 0) > System.currentTimeMillis()

    private fun renderActions() {
        actionButton?.text = when {
            busy -> "处理中…"
            ready -> "安装新版"
            available != null -> "下载新版"
            else -> "检查更新"
        }
    }

    // —— 弹窗 ——

    /** 打开「版本与更新」弹窗；[from] 是关闭后要收回焦点的控件。 */
    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()

        val body = activity.tvColumn()
        statusView = activity.tvText(status, 16f, TvStyle.text).apply {
            setLineSpacing(activity.tvDp(TvStyle.GAP_TIGHT).toFloat(), 1f)
        }
        body.addView(statusView, android.widget.LinearLayout.LayoutParams(-1, -2))

        autoBox = android.widget.CheckBox(activity).apply {
            text = "自动检查并下载更新"
            isChecked = auto
            setTextColor(TvStyle.textDim)
            textSize = 15f
            setPadding(0, activity.tvDp(TvStyle.GAP_BLOCK), 0, 0)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("automatic", enabled).apply()
                if (!enabled && busy) {
                    interrupt()
                    updateStatus("自动更新已关闭，可手动检查")
                    renderActions()
                }
            }
        }
        body.addView(autoBox, android.widget.LinearLayout.LayoutParams(-1, -2))

        body.addView(
            activity.tvText(
                "只在应用前台浏览时检查，播放中不打扰。下载后由系统安装器覆盖安装，收藏与观看记录保留。",
                13f, TvStyle.muted
            ).apply { setPadding(0, activity.tvDp(TvStyle.GAP_TIGHT), 0, 0) },
            android.widget.LinearLayout.LayoutParams(-1, -2)
        )

        val next = TvDialog(activity, "版本与更新 · ${BuildConfig.VERSION_NAME}").apply {
            content.addView(body, android.widget.LinearLayout.LayoutParams(-1, -2))
            // 「稍后」记下 24 小时，避免同一版本反复弹窗；「检查更新」点了不关框，留在里面刷新状态。
            button("稍后") {
                available?.let {
                    preferences.edit()
                        .putLong("deferred_code", it.versionCode)
                        .putLong("deferred_until", System.currentTimeMillis() + 24 * 60 * 60 * 1000L)
                        .apply()
                }
            }
            actionButton = button("检查更新", keepOpen = true) {
                when {
                    ready -> install()
                    available != null -> download(true)
                    else -> check(true)
                }
            }
        }
        dialog = next
        renderActions()
        next.show(from) { if (dialog === next) { dialog = null; statusView = null; actionButton = null; autoBox = null } }
    }

    // —— 后台任务 ——

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true
        renderActions()
        val ticket = ++generation
        task = worker.submit {
            try {
                val result = action()
                main.post { if (active && generation == ticket) { busy = false; done(result); renderActions() } }
            } catch (e: Exception) {
                main.post {
                    if (active && generation == ticket) {
                        busy = false
                        ready = false
                        retryAfter = System.currentTimeMillis() + 30 * 60 * 1000L
                        updateStatus(if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试")
                        renderActions()
                    }
                }
            }
        }
    }

    /** 检查新版本。[manual] 为用户主动点击。 */
    fun check(manual: Boolean) {
        if (busy || !active || !idle()) return
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…")
        work({ repository.latest() }) { update ->
            available = update.takeIf { it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT) }
            ready = false
            val found = available
            if (found == null) {
                updateStatus(
                    when {
                        update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT ->
                            "新版 ${update.versionName} 需要 Android API ${update.minSdk}，当前电视继续使用本版"
                        else -> "已是最新版本 ${BuildConfig.VERSION_NAME}"
                    }
                )
                worker.execute { apk.delete() }
            } else {
                updateStatus("发现 ${found.versionName} · ${"%.1f".format(found.size / 1048576.0)} MB\n${found.notes}")
                if (manual || auto) download(manual)
            }
        }
    }

    private fun download(manual: Boolean) {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}…")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent ->
                main.post { if (generation == ticket && active) updateStatus("正在下载 ${update.versionName} · $percent%") }
            }
            validateApk(update)
        }) {
            ready = true
            updateStatus("${update.versionName} 已下载并校验\n${update.notes}\n\n确认后由系统覆盖安装，保留本机记录。")
            if (idle() && dialog == null && (manual || !deferred(update))) {
                promptedCode = update.versionCode
                show(activity.currentFocus)
            }
        }
    }

    /**
     * 安装前的最后一道闸：哈希、包名、版本、最低系统版本、签名，五项全对才放行。
     * 签名比对用的是「已安装包的签名 == 候选包的签名」，因此换过证书的包会被挡住。
     */
    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
        if (candidate.packageName != activity.packageName ||
            version != update.versionCode ||
            candidate.versionName != update.versionName ||
            version <= BuildConfig.VERSION_CODE ||
            candidate.applicationInfo?.minSdkVersion != update.minSdk ||
            signers(installed).isEmpty() ||
            signers(installed) != signers(candidate)
        ) throw IOException("新版包名、版本或签名不匹配，已阻止安装")
    }

    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({ validateApk(update) }) {
            try {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    permissionRequested = true
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newRawUri("更新安装包", uri) }
                    )
                }
            } catch (_: Exception) {
                permissionRequested = false
                updateStatus("电视未提供安装或授权入口，请通过系统文件管理器安装新版")
            }
        }
    }
}
