// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

/** 远端长列表（热播榜、首页「热门发现」集合页）底部「展示更多」按钮的 tag，同时用作「焦点留在该按钮上」的标记。 */
private const val LOAD_MORE="load:more"

/** 剧卡栅格一行放几张：与 [HomeScreen.slotWidth] 的六列一致，追加出来的行不会比首屏宽一档。 */
private const val GRID_COLUMNS=6

/** 远端长列表最多向下追加到第几页（与 [ContentRepository.home] 的 `require(page in 1..100)` 对齐）。 */
private const val MAX_CATALOG_PAGE=100

/**
 * 设置页「主题外观」一行放几个主题芯片。
 * 14 套材质排成 4 行（4+4+4+2），比「一行一个主题」省下三分之二的高度，
 * 芯片宽度按屏宽等分，中文名 + 英文名在 720p 画布上也放得下。
 */
private const val THEME_GRID_COLUMNS=4

/**
 * 播放设置面板的行号：清晰度 / 倍速 / 画面模式 / 弹幕 / 字幕大小 / 字幕透明度 / 定时。
 *
 * 面板里好几处「改完就地重画这一行、再把焦点按回同一格」的调用都按行号寻址，
 * 中间插一行就会全部错位（改了清晰度结果选中态落在倍速上）。写死数字的代价已经出现过一次，
 * 因此行号收敛成常量，以后插行只改这里。
 */
private const val MENU_ROW_QUALITY=0
private const val MENU_ROW_SPEED=1
private const val MENU_ROW_FRAME=2
private const val MENU_ROW_DANMAKU=3
private const val MENU_ROW_SCALE=4
private const val MENU_ROW_ALPHA=5
private const val MENU_ROW_SLEEP=6

/**
 * 列表滚动位置最多记住多少页。
 * key 里带着搜索词与页码，用户每搜一个新词就是一个新 key，不设上限就是一条只涨不跌的内存曲线。
 */
private const val SCROLL_MEMORY=24

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    // 主题可以在设置里随时切换，因此这几个高频令牌写成 getter 而不是构造期快照：
    // 换主题之后重建页面，新控件立刻读到新色值（旧控件的背景 Drawable 会被整页重建换掉）。
    private val bg get()=TvStyle.background
    private val white get()=TvStyle.text
    private val muted get()=TvStyle.muted
    /** 设置页里主题外观分组的四行：换主题后重建页面时按 [TvTheme] 重新填充。 */
    private val themeRows=linkedMapOf<TvTheme,TvButton>()
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    /** 弹幕单独一条线程：它要按 30 秒窗口连着拉几页，不能占住内容请求的线程池。 */
    private val danmakuWorker=Executors.newSingleThreadExecutor()
    /** 每次换集自增；在途的弹幕请求发现 token 变了就立刻收手。 */
    private val danmakuToken=java.util.concurrent.atomic.AtomicLong(0)
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var playerClock: TextView?=null
    /** 顶部标题条（剧名 · 第几集 + 时钟）：播放时不打扰，只在唤出播放界面时露出来。 */
    private var playbackTopBar: View?=null
    private val clockFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    private var directPlayback=false
    private var collection: String?=null
    /** 集合页标题，随进入它的剧架而定（热门推荐 / 热门发现共用同一份热门列表）。 */
    private var collectionTitle=""
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()

    /** 记住一页列表的滚动位置；超出 [SCROLL_MEMORY] 条就丢掉最早的一条。 */
    private fun rememberScroll(key: String,value: Int) {
        if(key.isBlank()) return
        if(key !in scrollPositions && scrollPositions.size>=SCROLL_MEMORY) scrollPositions.keys.firstOrNull()?.let(scrollPositions::remove)
        scrollPositions[key]=value
    }

    // —— 页头区域随列表滚动收起 ——
    /** 页头区域里的每一行：工具条、搜索行、榜单标题行、集合页标题行。 */
    private val topArea=mutableListOf<View>()
    /** 每行展开时的高度（像素），收起动画的起点、展开动画的终点。 */
    private val topAreaHeights=mutableMapOf<View,Int>()
    /** 每行展开时应该还原的 LayoutParams 高度（工具条是固定 TOOLBAR_HEIGHT，其余是 WRAP_CONTENT）。 */
    private val topAreaRestore=mutableMapOf<View,Int>()
    private val topAreaAnimators=mutableMapOf<View,android.animation.ValueAnimator>()
    private var topAreaHidden=false
    /**
     * 「加载更多」是同一页的原地重建：这一次重建要保留页头当前的收 / 展状态，
     * 否则每追加一页页头都会弹回来一次。切换标签 / 分类这类换页仍然从展开态开始。
     */
    private var keepTopAreaState=false
    private var listScrollY=0
    private var topAreaSettleUntil=0L

    /** 把一行页头登记进「随滚动收起」的区域。 */
    private fun registerTopAreaRow(row: View) {
        topArea+=row
        val restore=row.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaRestore[row]=restore
        if(topAreaHidden) {
            // 页头正收着，这一行也必须立刻收起来：先量出展开高度当基准，再按收起状态摆好，
            // 中间不留一帧展开态，否则重建时页头会闪一下。
            expandedHeightOf(row)
            applyTopAreaState(row,true)
        } else {
            // 布局完成后再量一次真实高度：收起动画的起点、展开动画的终点都按它算。
            row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
        }
    }

    /**
     * 列表滚动 → 页头收展。
     *
     * 收起用高度动画而不是 translationY：收掉之后列表真的多占这一条高度，顶部不会留一条空白，
     * 也不会出现「按钮浮在列表上面」的叠层。只有列表回到最上边、或者焦点回到页头里的控件时才展开：
     * 列表中间向上滚只保持当前状态，不会半路把页头弹回来。收起 / 展开之后有一小段静默期，
     * 避开视口变化引起的 scrollY 回夹，否则会在底部来回抖。
     */
    private fun onListScroll(y: Int) {
        if(android.os.SystemClock.elapsedRealtime()<topAreaSettleUntil) { listScrollY=y; return }
        val hide=when {
            // 焦点落进页头时必须立刻展开，否则焦点会停在看不见的按钮上。
            topAreaHasFocus() -> false
            // 回到列表最上边才展开。
            y<=dp(12) -> false
            y>listScrollY+dp(4) -> true
            else -> topAreaHidden
        }
        listScrollY=y
        setTopAreaHidden(hide)
        // 手指 / 遥控滚到离底部还有小半屏时就先把下一页取回来：
        // 真滚到底的时候内容已经在手上，接上是瞬间的，用户看不到「正在加载」。
        if(gridAtBottom(dp(240))) prefetchNextPage()
    }

    /** 列表是不是已经滚到距底部 [slack] 像素以内。没有栅格（首页剧架、设置页）时永远为 false。 */
    private fun gridAtBottom(slack: Int): Boolean {
        val scroll=gridScroll ?: return false
        val list=gridList ?: return false
        if(scroll.height<=0 || list.height<=0) return false
        return scroll.scrollY+scroll.height+slack>=list.height
    }

    private fun topAreaHasFocus()=topArea.any { it.findFocus()!=null }

    /**
     * 一行页头展开时的高度。工具条这类固定高度的行直接用 LayoutParams，
     * 其余（搜索行、榜单标题行、集合页标题行）按页面栅格宽度量一次自然高度，不用等布局。
     */
    private fun expandedHeightOf(row: View): Int {
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaHeights[row]?.takeIf { it>0 }?.let { return it }
        if(restore>0) { topAreaHeights[row]=restore; return restore }
        // 收起状态不影响这次测量：量的是内容在页面栅格宽度下的自然高度。
        val width=if(row.width>0) row.width
            else (resources.displayMetrics.widthPixels-2*(pageInset()-pageBleed())).coerceAtLeast(1)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        )
        val measured=row.measuredHeight
        if(measured>0) topAreaHeights[row]=measured
        // 量不出来（内容还没建好）就退回当前高度：这一行不做动画、直接到位，不会朝 0 缩。
        return measured.takeIf { it>0 } ?: row.height
    }

    /** 把一行页头直接摆到目标状态（不排队动画）：收起 = 高度归零 + 淡出 + 打开裁剪；展开反之。 */
    private fun applyTopAreaState(row: View,hide: Boolean) {
        (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
        row.animate().cancel()
        row.alpha=if(hide) 0f else 1f
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        row.layoutParams=row.layoutParams.apply { height=if(hide) 0 else restore }
    }

    private fun setTopAreaHidden(hide: Boolean) {
        if(topAreaHidden==hide || topArea.isEmpty()) return
        topAreaHidden=hide
        val span=if(TvStyle.animate) TvStyle.DURATION else 0L
        topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+span+120
        topArea.forEach { row ->
            val from=row.height
            val to=if(hide) 0 else expandedHeightOf(row)
            // 先摘掉再取消：被取消的那条动画结束回调不会再回头改这一行的高度。
            topAreaAnimators.remove(row)?.cancel()
            row.animate().cancel()
            row.animate().alpha(if(hide) 0f else 1f).setDuration(span).start()
            // 收起过程中不再让子控件画到行外，展开时立刻恢复（焦点描边要继续画得出来）。
            (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
            // 低内存设备（TvStyle.animate=false）直接到位，不排队动画；没有高度差时也一样。
            if(span==0L || from==to) { settleTopAreaRow(row,hide); return@forEach }
            topAreaAnimators[row]=android.animation.ValueAnimator.ofInt(from,to).apply {
                duration=span
                interpolator=android.view.animation.DecelerateInterpolator()
                addUpdateListener { animation ->
                    row.layoutParams=row.layoutParams.apply { height=animation.animatedValue as Int }
                }
                addListener(object: android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // 摘掉自己这一步必须是原子的：这条回调也可能由 cancel() 同步触发。
                        if(topAreaAnimators.remove(row)===this@apply) settleTopAreaRow(row,hide)
                    }
                })
                start()
            }
        }
    }

    /** 动画结束：收起的行高度归零，展开的行还原成登记时的 LayoutParams 高度。 */
    private fun settleTopAreaRow(row: View,hide: Boolean) {
        applyTopAreaState(row,hide)
        // 展开到位后重新量一次真实高度，作为下一次收起 / 展开的基准。
        if(!hide) row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
    }
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    /** 播放画面上的弹幕层；数据来自原 App 的评论接口（见 ContentRepository.danmaku）。 */
    private var danmakuView: DanmakuView?=null
    /** 已经拿到弹幕的那一集：换清晰度、播放重试这类「同一集重进」不重复拉一遍。 */
    private var danmakuFor=""
    private var danmakuLoaded=false
    /** 这一集取到多少条弹幕；-1 表示还没取回来。播放界面的状态行会显示它，便于确认弹幕到底有没有到。 */
    private var danmakuCount=-1
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteCheck: TextView?=null
    private var favoriteStatus: TextView?=null
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    private var ranking: ComicRanking?=null
    private var rankSubtitle: TextView?=null
    private var moreButton: View?=null
    /** 向下追加一页后焦点要落到的位置：新卡片 id、底部按钮（load:more）或 null（不指定）。 */
    private var moreFocus: String?=null

    // —— 向下追加（无感加载）——
    /**
     * 当前栅格列表的活引用。
     *
     * 「展示更多」过去是 `page++` 之后整页重建：页头、分类开关、每一张卡都重画一遍，
     * 再靠记下来的 scrollY 把位置恢复回去。两次整页重建之间位置一夹不准就跳回顶部，
     * 焦点又会在恢复位置和「新卡片」之间来回抢，表现出来就是加载时抖、加载完乱。
     * 现在追加只在这张列表的末尾插新行：视口上方的像素一个都不动，
     * ScrollView 不需要任何位置恢复，也就没有「恢复得对不对」这回事。
     */
    private var gridList: LinearLayout?=null
    private var gridScroll: ScrollView?=null
    /** 底部那一行（进度文字 + 「展示更多」），追加时先摘下来再放回去，始终待在最后。 */
    private var gridPaging: LinearLayout?=null
    private var moreCount: TextView?=null
    /** 底部那一行当前是不是「还有下一页」的形态；只有它翻转时才需要重建这一行。 */
    private var pagingHasMore=false
    /** 栅格里的每一行，按屏幕顺序；焦点链与追加都以它为准。 */
    private val gridRows=mutableListOf<LinearLayout>()
    private var gridCardWidth=0
    private var gridGap=0
    private var gridPosterHeight=0
    /** 列表重建期间产生的滚动（位置恢复、焦点回带、内容夹取）都不算用户滚动。 */
    private var gridRestoring=true
    /** 下一页请求在途：静默预取与用户按下共用这一把锁，同一页不会请求两次。 */
    private var moreInFlight=false
    private var moreInFlightPage=0
    /** 这次在途的请求是用户按出来的（底部显示「正在加载更多…」），还是静默预取（不打扰）。 */
    private var moreVisible=false
    /** 在途的请求落地之后要不要把焦点交给新行的第一张卡（静默预取途中被用户按下时也会打开）。 */
    private var moreFocusWanted=false
    /** 预取失败：底部改成重试文案，不再自动重试，免得断网时反复打接口。 */
    private var moreFailed=false
    private var gridPrefetchJob: java.util.concurrent.Future<*>?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: SeekIndicator
    private lateinit var controls: LinearLayout
    private var transportIcon: ImageView?=null
    private var qualityOption: TextView?=null
    private var speedOption: TextView?=null
    private var frameOption: TextView?=null
    private var danmakuOption: TextView?=null
    private var sleepOption: TextView?=null
    private var panel=false
    private var playbackMenu: PlaybackMenu?=null
    /** 播放设置面板的外壳（固定屏幕下三分之一、负责裁剪与背板渐变）；摘浮层要摘这一层。 */
    private var playbackMenuShell: View?=null
    /** 本次观看选中的定时项目下标（0=关闭），只用来决定菜单里的选中态。 */
    private var sleepChoice=0
    private val sleepLabels=listOf("关闭","15 分钟","30 分钟","60 分钟","90 分钟","本集播完","再 3 集","再 5 集")
    private var speedDialog: TvDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    /** 页面左右安全边距。剧架按同一数值计算行内可用宽度，避免整行填不满。 */
    /** 页面左右安全边距，对齐爆米花 77.5dp 栅格（1280dp 画布上左右各 78dp）。 */
    private fun pageInset()=dp(78)
    /**
     * 页面容器比内容边距再早开始 [TvStyle.FOCUS_BLEED]。
     *
     * 容器的边界就是裁剪边界：内容从 78dp 开始，容器从 71dp 开始，滚动容器再把这个余量补回来，
     * 于是最左 / 最右那一张卡片（或按钮）放大后的焦点描边仍有地方画，不会被容器裁掉。
     * 内容本身的位置完全不变。
     */
    private fun pageBleed()=dp(TvStyle.FOCUS_BLEED)
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    /**
     * 新拟态底板：凸起 / 凹陷 / 只描边 / 压图暗角。
     * 参数顺序固定，调用方一律用具名参数，避免把 band 和 radius 写反。
     */
    private fun softPanel(
        fill: Int = TvStyle.surface,
        radius: Int = TvStyle.RADIUS_CARD,
        band: Int = TvStyle.SOFT_BAND,
        style: SoftStyle = SoftStyle.RAISED,
        ring: Int = Color.TRANSPARENT,
        ringWidth: Int = 0,
        glow: Int = Color.TRANSPARENT,
        depth: Float = 1f
    ) = TvStyle.soft(this,fill,radius,band,style,ring,ringWidth,glow,depth)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; focusOverflow() }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; focusOverflow() }
    /**
     * 全站按钮只有一个实现：[TvButton]。高度、内边距、字号、圆角、焦点反馈都来自
     * [ButtonSize] / [ButtonTone]，页面代码不再自己拼样式。
     */
    private fun button(label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,onClick: ()->Unit): TvButton =
        TvButton(this,label,size,tone,action=onClick).apply { if(selected) setSelectedState(true) }
    /**
     * 往容器里放一个按钮，并保证间距只有一个来源：横向容器加左边距、纵向容器加上边距，
     * 第一个子控件不留边距。因此不会再有「两个按钮贴在一起」或「行尾多出 8dp 把整行推偏」。
     */
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_INLINE,onClick: ()->Unit): TvButton {
        val view=button(label,selected,tone,size,onClick)
        val params=lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT }
        if(parent.childCount>0) {
            if(parent.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
        }
        parent.addView(view,params); return view
    }
    /** 与 [addButton] 同一套间距规则，给需要自定义 LayoutParams 的行内控件用。 */
    private fun gapInto(row: LinearLayout,view: View,params: LinearLayout.LayoutParams,gap: Int=TvStyle.GAP_INLINE): View {
        if(row.childCount>0) {
            if(row.orientation==LinearLayout.HORIZONTAL) params.leftMargin=dp(gap) else params.topMargin=dp(gap)
        }
        row.addView(view,params); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // 主题要在建视图之前定下来：先落盘偏好 → 换色 → 丢掉柔光位图缓存（里面烤着上一套材质的投影）。
        // 窗口底色也跟着换，启动那一帧、页面切换的空档都不会闪出另一套材质。
        TvStyle.applyTheme(TvThemeStore.load(this))
        applyWindowBackground()
        // 低内存设备保留全部视觉层级，只关掉过渡动画、收紧柔光（投影更短更淡，生成也更快）。
        TvStyle.animate=getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice!=true
        Soft.quality=if(TvStyle.animate) 1f else .7f
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this)
        root=FrameLayout(this).apply { setBackgroundColor(bg); clipChildren=false; clipToPadding=false }
        setContentView(root)
        // 焦点回到页头（品牌 / 分类开关 / 顶部入口）时立刻展开，
        // 否则收起的页头高度是 0，焦点会落在看不见的按钮上。
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _,newFocus ->
            if(topAreaHidden && newFocus!=null && topArea.any { it===newFocus || it.findFocus()!=null }) setTopAreaHidden(false)
        }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) { applySessionToken(); showCatalog(load=true); favoriteMonitor.check() }
        }
    }
    /**
     * 搜索登录态（可选）跟着本机设置走：留空时搜索退回官网搜索，填上后直接使用 App 搜索接口。
     * 设置里改完立刻作用于后面的搜索请求。
     */
    private fun applySessionToken() { repository.sessionToken=library.searchToken.ifBlank { null } }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column().apply { clipChildren=false; clipToPadding=false }
        val horizontal=pageInset()-pageBleed(); val vertical=dp(12)
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) {
            // 二级界面（详情页加载中）不再放返回按钮：返回键与顶部导航就是返回入口，页头只留标题。
            // 页头行补回焦点余量，标题仍从内容线开始。
            val heading=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(6)) }
            heading.addView(text(title,20f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(TvStyle.BUTTON_NORMAL)).apply { weight=1f })
            container.addView(heading)
        }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { rememberScroll(catalogPageKey,it.scrollY) }
        catalogBody=null
        homeScreen=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; rankSubtitle=null; moreButton=null; favoriteBadges.clear()
        favoriteCheck=null; favoriteStatus=null
        catalogCards.clear(); cardImages.clear()
        // 栅格连同它的追加状态一起作废：在途的预取结果不能再往新页面上接。
        gridPrefetchJob?.cancel(true); gridPrefetchJob=null
        gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
        gridRows.clear(); gridCardWidth=0; gridGap=0; gridPosterHeight=0
        moreInFlight=false; moreInFlightPage=0; moreVisible=false; moreFocusWanted=false; moreFailed=false
        // 页头收展状态：同一页原地追加（加载更多）保留，其余换页都从展开态开始。
        val keepTopArea=keepTopAreaState
        keepTopAreaState=false
        if(!keepTopArea) topAreaHidden=false
        // 运行中的页头动画先快照、再清表、最后取消。
        // ValueAnimator.cancel() 会**同步**回调 onAnimationEnd，而那条回调会从这张表里摘掉自己；
        // 边遍历这张表边取消就是 ConcurrentModificationException——页头有两行以上
        // （搜索行 / 榜单标题行 / 集合页标题行）时必现，而收展动画那 200ms 里
        // 「展示更多」「刷新榜单」、切分类、进详情都会重建页面，表现出来就是偶发崩溃。
        val running=topAreaAnimators.values.toList()
        topAreaAnimators.clear()
        running.forEach { it.cancel() }
        topArea.clear(); topAreaHeights.clear(); topAreaRestore.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(pageBleed(),dp(28),pageBleed(),dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),0) })
        // 重试按钮与上面的提示文字共用同一条内容线：纵向间距由 addButton 给，横向自己补回焦点余量。
        addButton(parent,"重试",onClick=retry).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin=pageBleed(); requestFocus() }
    }
    private fun switchTab(next: Int) {
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        // 首页 / 搜索 / 排行榜三页的内容都跟着分类走，切分类时三页的缓存一起作废。
        for(index in 0..2) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null
        showCatalog(load=(tab==0 || tab==2 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null; collectionTitle=""
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String,title: String?=null) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; collectionTitle=title ?: collectionName(kind); page=1; catalogFocus=""; showCatalog()
    }
    /** 集合页标题：默认按类型，热门这种两个剧架共用的列表由调用方给出自己的名字。 */
    private fun collectionName(kind: String)=when(kind) {
        "favorites" -> "我的收藏"; "history" -> "历史记录"; "later" -> "稍后看"; else -> "热门推荐"
    }
    /**
     * 顶部导航栏左侧显示的页面名。
     *
     * 首页与搜索 / 设置页显示品牌「红果 TV」；二级界面（收藏 / 历史 / 稍后看 / 热门集合页）
     * 与排行榜显示自己的名字——这些页面原来的标题行因此可以整行去掉。
     */
    private fun pageTitle(): String = when {
        collection!=null -> collectionTitle.ifBlank { collectionName(collection.orEmpty()) }
        tab==2 -> "${library.contentType.label}热播榜"
        tab==3 -> "设置"
        else -> "红果 TV"
    }
    /**
     * 当前标签对应的顶部图标入口。
     *
     * 首页（tab 0）没有自己的入口：品牌「红果 TV」是标识而不是按钮，不参与焦点链，
     * 所以这里返回 null——调用方跳过聚焦即可（回主界面走返回键）。
     */
    private fun navEntry(index: Int = tab): View? = nav.getOrNull(index-1)
    /**
     * 榜单来源说明。漫剧读官网漫剧热播榜（带名次与热度）；
     * 短剧没有对应的官网榜单，用分类页的播放量排序，因此文案分开写，不假装有名次。
     */
    private fun rankSource()=if(library.contentType==ContentType.COMIC) "来源：红果漫剧热播榜" else "来源：红果短剧热门 · 按播放量排序"
    /**
     * 片库分类开关（短剧 / 漫剧）是否放进页头里。
     * 首页、搜索、排行榜三个页面用的是同一个判断，所以这两个按钮在三个页面里的位置完全一致：
     * 都在品牌「红果 TV」右侧；屏幕窄或字号放大时才退回页头下方单独一行。
     */
    private fun inlineTypeFilters()=tab<=2 && resources.configuration.fontScale<=1.2f && widthDp()>=900
    /**
     * 远端长列表：热播榜、从首页「热门推荐 / 热门发现」向右进入的热门集合页，以及搜索结果。
     * 这些列表都是「往下接」而不是「翻页」：已显示的剧集不动，新的一页接在末尾。
     */
    private fun appendCatalog()=collection=="hot" || (tab==2 && collection==null) || (tab==1 && collection==null)
    /**
     * 搜索结果不再给「展示更多」按钮：滚到列表底部（或焦点走到最后一行）就自动往下取，
     * 底部只留一行不可聚焦的进度文字。
     */
    private fun autoAppendCatalog()=tab==1 && collection==null
    /** 完全来自本机记录的列表：我的收藏、历史记录、稍后看这些集合页，仍然按页切换。 */
    private fun localCatalog()=collection!=null && collection!="hot"
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "favorites" -> library.favorites()
        "history" -> library.history().map { it.series }
        "later" -> library.later()
        else -> library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // 热播榜与热门集合页从第 2 页起是「向下追加」：保留已经显示的剧集，只把新的一页接在后面。
        val appending=load && appendCatalog() && page>1 && catalog.isNotEmpty()
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && collection==null && page==1) library.cachedHome(library.contentType) else null
            if(!appending) { catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null }
            homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        // 顶部导航栏：左侧是页面名（首页是品牌「红果 TV」，二级界面与榜单页显示自己的名字），
        // 右侧是这一页自己的动作与 3 个圆形图标入口（搜索 / 排行榜 / 设置）。
        // 整条**不画底板**：没有边框、也没有可以按进去的面板，文字直接落在内容线上；
        // 品牌是标识而不是按钮，因此不参与焦点链（不可选中、不可点击），回主界面一律走返回键。
        // 高度固定 TOOLBAR_HEIGHT：页头收展动画直接改这一行的高度，不用再跟 minimumHeight 抢。
        val top=row().apply {
            // 左边与内容线对齐；右边少让一条柔光带，圆形入口的可见圆盘才正好压在内容线上。
            setPadding(pageBleed(),0,pageBleed()-dp(TvStyle.SOFT_BAND),0)
        }
        top.addView(text(pageTitle(),30f,TvStyle.textStrong).apply {
            setTypeface(typeface,Typeface.BOLD)
            setPadding(0,0,dp(4),0)
        },lp(-2,-2))
        val filterSlot=row()
        gapInto(top,filterSlot,lp(-2,-2),TvStyle.GAP_GROUP)
        // 二级界面的动作跟着标题走同一条：标题进了导航栏，动作也放进来，页头下面不再单独占一行。
        if(collection=="favorites") favoriteCheck=addButton(top,"检查更新") { favoriteMonitor.check(force=true) }
        if(collection=="history" && library.history().isNotEmpty()) historyManage=addButton(top,"管理记录") {
            val rows=library.history()
            tvTools.choose("选择要管理的历史记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                tvTools.confirm("清空历史记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
            }
        }
        top.addView(Space(this),lp(0,1).apply { weight=1f })
        listOf(
            com.hongguotv.nativeapp.R.drawable.ic_toolbar_search to "搜索",
            com.hongguotv.nativeapp.R.drawable.ic_toolbar_rank to "排行榜",
            com.hongguotv.nativeapp.R.drawable.ic_toolbar_settings to "设置"
        ).forEachIndexed { index,entry ->
            val item=IconButton(this,entry.first,entry.second) { switchTab(index+1) }
            item.setActive(index+1==tab)
            nav+=item
            gapInto(top,item,lp(-2,-2),TvStyle.GAP_BLOCK)
        }
        container.addView(top,lp(-1,dp(TvStyle.TOOLBAR_HEIGHT)))
        registerTopAreaRow(top)
        if(tab==3) {
            val scroll=ScrollView(this).focusBleed(); val body=column(); scroll.addView(body); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
            listScrollY=scroll.scrollY
            scroll.setOnScrollChangeListener { _,_,y,_,_ -> onListScroll(y) }
            settings(body); if(focusNav) navEntry()?.requestFocus(); return
        }
        // 首页 / 搜索 / 排行榜都带同一组分类开关，位置也一致：品牌右侧、页头之内。
        if(tab<=2) {
            val types=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
            if(!inlineTypeFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                val chip=ToggleChip(this,type.label,library.contentType==type) { switchContentType(type) }
                navEntry()?.let { chip.nextFocusUpId=it.id }
                typeButtons[type]=chip
                gapInto(types,chip,lp(-2,-2))
            }
            if(inlineTypeFilters()) {
                types.setPadding(0,0,0,0); filterSlot.addView(types)
            } else {
                if(tab==0) gapInto(types,text("长按确认 · 管理片单",12f,muted),lp(-2,-2),TvStyle.GAP_BLOCK)
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==1) {
            val searchRow=row().apply { setPadding(pageBleed(),dp(2),pageBleed(),dp(2)) }
            // 搜索框与同一行的按钮共用一套尺寸令牌：48dp 底板、胶囊圆角、左右 28dp 内边距，
            // 因此输入框和「搜索 / 历史 / 手机输入」在一条基线上；差别只在「凹槽 vs 凸起」这一层语义。
            val searchBand=dp(TvStyle.SOFT_BAND)
            val input=EditText(this).apply {
                id=View.generateViewId(); hint="输入${library.contentType.label}名称或关键词"; setText(query)
                textSize=20f; setTypeface(typeface,Typeface.BOLD)
                setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted); isSingleLine=true; maxLines=1
                filters=arrayOf(android.text.InputFilter.LengthFilter(80))
                imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                includeFontPadding=false
                // 输入框是一条「压进底板」的凹槽：内边距里含柔光带，内阴影才有地方画。
                setPadding(dp(TvStyle.BUTTON_PAD_NORMAL)+searchBand,searchBand,dp(TvStyle.BUTTON_PAD_NORMAL)+searchBand,searchBand)
                background=softPanel(TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
                setOnFocusChangeListener { view,_ ->
                    val focused=view.hasFocus()
                    // 凹陷控件的焦点没法靠「打亮」表达，只能把槽压得更深、让这条中性细线加深。
                    // 线色取 45% 文字基色（浅色主题是深墨、深色主题是暖白），不是强调色——
                    // 选框不再有任何颜色。
                    view.background=softPanel(
                        TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET,
                        ring=if(focused) TvStyle.focusLine else TvStyle.outline,
                        ringWidth=1,
                        depth=if(focused) 1.6f else 1.1f)
                }
                // EditText 自己会把上下键吃掉做光标移动，焦点因此困在输入框里出不去。
                // 这两下在这里显式交回页面：上=分类开关，下=搜索结果的第一张卡（没有结果时退到顶部入口）。
                setOnKeyListener { view,keyCode,event ->
                    if(event.action!=KeyEvent.ACTION_DOWN || (keyCode!=KeyEvent.KEYCODE_DPAD_UP && keyCode!=KeyEvent.KEYCODE_DPAD_DOWN)) false
                    else {
                        val target=when {
                            keyCode==KeyEvent.KEYCODE_DPAD_DOWN -> root.findViewById<View>(view.nextFocusDownId)?.takeIf { it.isShown && it.isFocusable }
                                ?: gridCards().firstOrNull { it.isShown } ?: navEntry()?.takeIf { it.isShown }
                            else -> root.findViewById<View>(view.nextFocusUpId)?.takeIf { it.isShown && it.isFocusable }
                                ?: typeButtons[library.contentType]?.takeIf { it.isShown }
                        }
                        if(target==null) false
                        else {
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken,0)
                            view.clearFocus(); target.requestFocus(); true
                        }
                    }
                }
            }
            searchRow.addView(input,lp(0,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)).apply { weight=1f })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input
            val submit=addButton(searchRow,"搜索") { search() }
            searchButton=submit
            input.nextFocusUpId=typeButtons.getValue(library.contentType).id; searchButton?.nextFocusUpId=input.nextFocusUpId
            lateinit var recent: TvButton
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }
            recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TvButton
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }
            phone.nextFocusUpId=input.nextFocusUpId
            searchActions.addAll(listOf(recent,phone))
            typeButtons.values.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }
            container.addView(searchRow); registerTopAreaRow(searchRow)
        } else if(tab==2) {
            // 榜单名已经进了导航栏，这一行只剩来源 / 更新时间；
            // 「刷新榜单」按钮去掉：进页面、切分类、滚到顶都会重新取，不需要单独一个入口。
            val subtitle=text(if(load) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource(),13f,muted)
                .apply { setPadding(pageBleed(),0,pageBleed(),dp(8)) }
            rankSubtitle=subtitle
            container.addView(subtitle); registerTopAreaRow(subtitle)
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
        }
        if(load) {
            // 追加渲染要排在首页分支前面：热门集合页的 tab 也是 0，但它只往列表后面接内容。
            if(appending) catalogGrid(body,focusNav,focusType,loadingMore=true)
            else if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            else { message(body,"正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else navEntry()?.requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            work({
                // 排行榜跟着页头的分类开关走：漫剧读漫剧热播榜（带名次），短剧读分类页的播放量排序。
                val result=when(requestTab) { 0,2 -> repository.home(requestPage,requestType); else -> repository.search(requestQuery,requestPage,requestType) }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==0 && requestPage==1 && collection==null && (homeFromCache || tvTools.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    if(appending) {
                        // 追加：只接住还没出现过的剧集，并把这一页的名次与热度并进已有榜单。
                        val added=result.items.filterNot { item -> catalog.any { it.id==item.id } }
                        val wanted=moreFocus; moreFocus=null
                        catalog=catalog+added; ranking=mergeRanking(ranking,result.ranking); hasMore=result.hasMore
                        rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                        // 栅格还活着（空页那次重建之后又追加）就只往下接，不整页重画。
                        if(gridList!=null && catalogBody===body) appendGridItems(added,wanted)
                        else { body.removeAllViews(); catalogGrid(body,false) }
                    } else {
                        catalog=result.items; ranking=result.ranking; hasMore=result.hasMore
                        val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                        rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                        body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                    }
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1 && collection==null) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                if(appending) {
                    // 追加失败不动已经显示的剧集，页码退回待加载的那一页，底部按钮可以直接重试。
                    page=requestPage-1; moreFocus=LOAD_MORE
                    rankSubtitle?.text="加载更多失败 · 当前显示 ${catalog.size} 部"
                    Toast.makeText(this,"加载更多失败，请检查网络后重试",Toast.LENGTH_LONG).show()
                    body.removeAllViews(); catalogGrid(body,false)
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                }
            })
        } else catalogGrid(body,focusNav,focusType)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false,loadingMore: Boolean=false) {
        // 这一页会新建自己的栅格：旧的活引用先作废（首页剧架不是栅格，这里同样清干净），往下重建。
        gridList=null; gridScroll=null; gridPaging=null; moreCount=null; pagingHasMore=false
        gridRows.clear(); gridCardWidth=0; gridGap=0; gridPosterHeight=0
        gridRestoring=true
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        // 这一页会新建自己的页头行与底部按钮，先把上一页留下的引用清掉：
        // 让已经离树的控件继续当焦点目标，焦点请求会失败，焦点就可能落到页头的按钮上，
        // 页头于是被重新展开——观感就是「加载更多之后顶部那排按钮又显示出来」。
        val wanted=moreFocus; moreFocus=null
        moreButton=null
        // 原地重建列表（refreshLibraryCards）会把上一次的页头行摘下来，这里顺手清掉，别留着已经离树的引用。
        topArea.filter { it.parent==null }.forEach { row ->
            topAreaAnimators.remove(row)?.cancel()
            topAreaHeights.remove(row); topAreaRestore.remove(row)
        }
        topArea.removeAll { it.parent==null }
        catalogCards.clear(); cardImages.clear()
        // 集合页的标题与「检查更新 / 管理记录」都搬进了顶部导航栏，这里只剩收藏页的更新状态行。
        if(collection=="favorites") {
            val status=text(favoriteMonitor.status,13f,muted).apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
            favoriteStatus=status
            body.addView(status); registerTopAreaRow(status)
        }
        // 远端长列表是向下追加的同一份内容，滚动位置不能跟着页码走，否则每次「展示更多」都会跳回顶部。
        catalogPageKey="$tab:${collection.orEmpty()}:${if(appendCatalog()) 1 else page}:$query"
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false }.focusBleed(); catalogScroll=scroll
        val list=column(); scroll.addView(list); body.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
        // 页头收展：恢复上次滚动位置的那一次不算，免得一进页面页头就闪一下。
        listScrollY=scroll.scrollY
        // 列表重建期间产生的滚动（位置恢复、焦点回带、内容夹取）都不是用户滚动：
        // 静默期内不改页头状态，否则每次「展示更多」重建页面都会把这些滚动当成「滚到顶了」，
        // 页头于是又展开一次。
        topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+TvStyle.DURATION+120
        scroll.setOnScrollChangeListener { _,_,y,_,_ -> if(catalogScroll===scroll && !gridRestoring) onListScroll(y) }
        // 远端长列表不切页：catalog 就是已经向下追加出来的整份列表，一次全画出来。
        // 首页与热门隐藏掉本机标为「不感兴趣」的剧；搜索结果不做这层过滤（搜到就是要看）。
        val displayed=when {
            localCatalog() -> catalog.drop((page-1)*pageSize).take(pageSize)
            tab==0 || tab==2 -> catalog.filterNot { it.id in library.hidden() }
            appendCatalog() -> catalog
            else -> catalog.take(30)
        }
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when {
                tab==1 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关${library.contentType.label}，换个关键词试试"
                collection=="favorites" -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"
                collection=="history" -> "播放过的剧集会自动保存在这里"
                else -> "本页没有更多内容"
            })
            if(!appendCatalog() && localCatalog()) {
                if(page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
                if(hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            }
            // 一页内容都没有（空页）时没有栅格可追加，这里的入口退回整页重建。
            // 搜索结果不给按钮：往下滚就自动接上，这里只提示还剩多少没显示。
            if(appendCatalog() && hasMore && !autoAppendCatalog()) addButton(list,moreLabel()) { loadMore() }
            if(autoAppendCatalog() && hasMore) message(list,"向下滚动会自动加载更多")
            if(focusType) typeButtons[library.contentType]?.requestFocus() else navEntry()?.requestFocus()
            return
        }
        val gap=dp(6)
        val gridWidth=(resources.displayMetrics.widthPixels-2*pageInset()-2*dp(2)).coerceAtLeast(dp(320))
        val width=((gridWidth-gap*(GRID_COLUMNS-1))/GRID_COLUMNS).toInt()
        // 与 HomeScreen.slotHeight 同一算法：先扣掉卡片 5dp 内边距，再按 134:196 换算。
        val posterHeight=((width-dp(2*TvStyle.SOFT_BAND+8))/TvStyle.POSTER_ASPECT).toInt()
        gridList=list; gridScroll=scroll; gridCardWidth=width; gridGap=gap; gridPosterHeight=posterHeight
        addGridRows(list,displayed,width,gap,posterHeight)
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if(appendCatalog()) {
            // 热播榜与热门集合页去掉「上一页 / 下一页 / 下一批」：只向下追加，底部留一个向下的按钮按需加载下一页。
            gridPaging=paging
            fillPagingRow(paging)
            list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
            next=moreButton?.takeIf { it.isFocusable }
        } else if(tab==1 || localCatalog()) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1 }
            gapInto(paging,text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(95),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore }
            list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
        }
        wireGridFocus(prev,next)
        // 追加一页之后把焦点交给新出现的第一张卡片；没有新卡片或加载失败就留在底部按钮上。
        val appended=when {
            wanted==null -> null
            wanted==LOAD_MORE -> moreButton
            else -> gridCards().firstOrNull { it.tag==wanted }
        }
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) navEntry()?.requestFocus()
        else if(loadingMore) moreButton?.requestFocus()
        else if(appended!=null) appended.requestFocus()
        else (gridCards().firstOrNull { it.tag==catalogFocus } ?: gridCards().firstOrNull())?.requestFocus()
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            val fitted=minOf(posterHeight,(scroll.height-dp(56)).coerceAtLeast(dp(120)))
            gridCards().forEach { view -> (view as PosterCard).resizePoster(fitted) }
            // 位置恢复要等新列表量完再算：内容还没测量时 ScrollView 会把 scrollTo 夹成 0
            // （clamp 里 my >= child 直接返回 0），换页之后列表就跳回顶部、页头跟着展开。
            // 因此挂在列表自己的布局回调上：布局完成后用真实坐标恢复位置，并把焦点卡片带回可见区域。
            list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View,l: Int,t: Int,r: Int,b: Int,oldL: Int,oldT: Int,oldR: Int,oldB: Int) {
                    view.removeOnLayoutChangeListener(this)
                    scroll.post settle@{
                        if(catalogScroll!==scroll) return@settle
                        savedScroll?.let { scroll.scrollTo(0,it) }
                        gridCards().firstOrNull { it.hasFocus() }?.let { card -> revealGridLine(card,false) }
                        gridRestoring=false
                    }
                }
            })
            // 兜底：列表尺寸万一没有变化、布局回调不触发，静默期不能一直挂着。
            list.postDelayed({ if(catalogScroll===scroll) gridRestoring=false },400)
        }
        // 首屏刚铺好就先把下一页取回来：用户第一次按下键时内容已经在手上。
        prefetchNextPage()
    }
    /**
     * 底部按钮文案。
     * 静默预取不打扰用户（还是「展示更多 ↓」），只有用户自己按出来的加载才显示「正在加载更多…」。
     */
    private fun moreLabel(): String = when {
        moreFailed -> "加载失败 · 重试"
        moreVisible && moreInFlight -> "正在加载更多…"
        else -> "展示更多 ↓"
    }
    /**
     * 「向下展示更多」。
     *
     * 有活着的栅格就只往列表末尾接（视口上方的像素一个不动），没有栅格（空页）才退回整页重建。
     */
    private fun loadMore() {
        if(!hasMore || !appendCatalog()) return
        if(gridList==null) {
            moreFocus=null; page++; catalogFocus=""
            // 追加是同一页的原地重建：页头保持当前的收 / 展状态，不会因为加载更多又弹回来。
            keepTopAreaState=true
            showCatalog(true)
            return
        }
        requestNextPage(userInitiated=true)
    }
    /**
     * 静默取下一页：焦点走到倒数两行、或者列表滚到接近底部时触发。
     *
     * 取回来直接接在列表末尾（在视口下方，用户看不见），全程不显示加载态——
     * 这就是「无感」的来源：请求发生在用户看到底部之前。
     */
    private fun prefetchNextPage() {
        // [moreFailed] 之后不再自动重试：断网时滚动事件会把预取变成对着接口猛敲，
        // 等用户自己按下「加载失败 · 重试」再打一次就够了。
        if(screen!="catalog" || !appendCatalog() || !hasMore || moreInFlight || moreFailed || gridList==null) return
        if(page+1>MAX_CATALOG_PAGE) return
        requestNextPage(userInitiated=false)
    }
    /**
     * 把取回来的一页接在列表末尾。
     *
     * 关键在「只动下面」：先把底部行摘下来，把新行插在它前面再放回去，
     * 视口上方的所有内容一个像素都没动，ScrollView 因此不需要任何位置恢复，
     * 也就不会出现「加载更多之后跳回顶部 / 跳到别处」。
     * [userInitiated] 为 false 表示这是后台预取落地，不抢焦点、不滚动。
     */
    private fun applyNextPage(next: Int,result: CatalogPage,userInitiated: Boolean) {
        if(!hasMore || !appendCatalog() || gridList==null) return
        val added=result.items.filterNot { item -> catalog.any { it.id==item.id } }
        catalog=catalog+added
        ranking=mergeRanking(ranking,result.ranking)
        hasMore=result.hasMore
        page=next
        val target=if(userInitiated) added.firstOrNull()?.id ?: LOAD_MORE else null
        if(userInitiated) catalogFocus=added.firstOrNull()?.id.orEmpty()
        appendGridItems(added,target)
        // 只有用户已经站在底部附近才继续往后取：否则一次预取会连锁把整份目录都拉下来。
        if(hasMore && nearGridEnd()) prefetchNextPage()
    }
    /**
     * 用户是不是已经站在列表底部附近：焦点在倒数两行之内，或者视口已经接近列表末尾。
     * 「继续往后预取」与「一次性把整份目录拉下来」的分界线就在这个判断上。
     */
    private fun nearGridEnd(): Boolean {
        if(gridAtBottom(dp(240))) return true
        val focused=gridRows.indexOfFirst { it.findFocus()!=null }
        return focused>=0 && focused>=gridRows.size-2
    }
    /**
     * 去取第 `page+1` 页。
     *
     * [userInitiated] 为 true 时底部显示「正在加载更多…」，结果落地后焦点落到新行的第一张卡；
     * 静默预取只记在 [moreInFlight] 上，界面上什么都不变。
     * 两者共用同一把锁，所以同一页不会被请求两次；静默预取在途中被用户按下时，
     * 只是把 [moreFocusWanted] 打开，落地时照样把焦点交过去。
     */
    private fun requestNextPage(userInitiated: Boolean) {
        if(!hasMore || !appendCatalog() || gridList==null) return
        if(moreInFlight) {
            if(userInitiated) { moreVisible=true; moreFocusWanted=true; updateFooter() }
            return
        }
        val next=page+1
        if(next>MAX_CATALOG_PAGE) { hasMore=false; updateFooter(); return }
        moreInFlight=true; moreInFlightPage=next; moreVisible=userInitiated
        moreFocusWanted=userInitiated; moreFailed=false
        updateFooter()
        val ticket=generation; val type=library.contentType; val requestTab=tab; val scope=collection; val requestQuery=query
        gridPrefetchJob=io.submit {
            // 搜索结果也走这条「往下接」的路：第 2 页起按同一关键词继续取，页头与已显示的剧集都不动。
            val result=runCatching {
                if(requestTab==1 && scope==null) repository.search(requestQuery,next,type) else repository.home(next,type)
            }.getOrNull()
            main.post {
                if(isDestroyed || generation!=ticket) return@post
                if(moreInFlightPage==next) { moreInFlight=false; moreVisible=false }
                val wantsFocus=moreFocusWanted; moreFocusWanted=false
                if(result==null) {
                    if(wantsFocus) Toast.makeText(this,"加载更多失败，请检查网络后重试",Toast.LENGTH_LONG).show()
                    moreFailed=true; updateFooter()
                    return@post
                }
                // 只有「还是原来那一页」才接得上；中途切了标签 / 分类 / 集合，结果直接丢掉。
                if(scope==collection && requestTab==tab && requestQuery==query && library.contentType==type && screen=="catalog" && gridList!=null)
                    applyNextPage(next,result,wantsFocus)
                else updateFooter()
            }
        }
    }
    /**
     * 把新取回的一页接在列表末尾：摘掉底部行 → 铺新行 → 放回底部行 → 重排焦点链。
     * 内容只往视口下方长，滚动位置不需要（也不应该）做任何恢复。
     */
    private fun appendGridItems(added: List<Series>,focusTarget: String?) {
        val list=gridList ?: return
        val visible=added.filterNot { it.id in library.hidden() }
        if(visible.isNotEmpty()) {
            val paging=gridPaging
            if(paging!=null) list.removeView(paging)
            // 接着上一行的空位往下排：一页 20 部按六列切开，最后一行只有 2 张，
            // 新的一页要把那 4 个空位填满才继续开新行，否则列表中间会凭空多出一条断行。
            addGridRows(list,visible,gridCardWidth,gridGap,gridPosterHeight,gridRows.lastOrNull())
            if(paging!=null) list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
        }
        // hasMore 可能刚翻成 false（接上的是最后一页）：只有形态真的变了才重建底部这一行。
        // 没变就只改文案——重建会把焦点按着的「展示更多」按钮换掉，焦点会先掉一帧再回来。
        if(pagingHasMore!=hasMore) rebuildPagingRow() else updateFooter()
        wireGridFocus(null,moreButton?.takeIf { it.isFocusable })
        val target=when {
            focusTarget==null -> null
            focusTarget==LOAD_MORE -> moreButton
            else -> gridCards().firstOrNull { it.tag==focusTarget }
        }
        // 用户自己按出来的追加才动焦点：焦点先离开底部按钮，再重建底部那一行，免得中途焦点掉到页头。
        if(target!=null) focusGridCardAfterLayout(target,list)
    }
    /** 底部一行：已显示进度 + 「展示更多」。hasMore 变成 false 时只留进度文字。 */
    private fun fillPagingRow(paging: LinearLayout) {
        moreButton=null; moreCount=null
        pagingHasMore=hasMore
        paging.addView(text(moreCountText(),14f,muted).apply { gravity=Gravity.CENTER },lp(dp(200),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
        moreCount=paging.getChildAt(0) as? TextView
        // 搜索结果只要这一行进度文字：加载由滚动与焦点触发，不需要再按一个按钮。
        if(!hasMore || autoAppendCatalog()) return
        val more=button(moreLabel()) { loadMore() }.apply {
            tag=LOAD_MORE
            nextFocusLeftId=id; nextFocusRightId=id
            // 停在底部按钮上再按下键也是「继续往下看」，不用先按确认。
            setOnKeyListener { _,key,event ->
                if(key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_UP) { loadMore(); true } else false
            }
        }
        gapInto(paging,more,lp(-2,-2))
        moreButton=more
        updateFooter()
    }
    /** 重建底部那一行（只有 hasMore 翻转时需要，其它情况走 [updateFooter] 就地改文案）。 */
    private fun rebuildPagingRow() {
        val paging=gridPaging ?: return
        val keepFocus=moreButton!=null && moreButton!!.hasFocus()
        paging.removeAllViews()
        fillPagingRow(paging)
        if(keepFocus && moreButton==null) gridRows.lastOrNull()?.getChildAt(0)?.requestFocus()
    }
    /** 底部文案与按钮状态就地更新：不动子控件，焦点不会掉。 */
    private fun updateFooter() {
        moreCount?.text=moreCountText()
        val more=moreButton ?: return
        (more as? TvButton)?.setLabel(moreLabel())
        more.alpha=if(moreVisible && moreInFlight) TvStyle.DISABLED_ALPHA else 1f
    }
    /** 栅格里按屏幕顺序排列的全部卡片。 */
    private fun gridCards(): List<View> = gridRows.flatMap { line -> (0 until line.childCount).map(line::getChildAt) }
    /** 这一行是不是栅格里当前的最后一行（追加之后会变，所以每次现算）。 */
    private fun isGridTail(line: View)=gridRows.lastOrNull()===line
    /** 把某一行带进可视区所需的目标 scrollY；已经看得见就返回当前值。 */
    private fun revealY(line: View,scroll: ScrollView): Int = when {
        line.top<scroll.scrollY -> line.top
        line.bottom>scroll.scrollY+scroll.height -> (line.bottom-scroll.height).coerceAtLeast(0)
        else -> scroll.scrollY
    }
    /** 把某张卡片所在的行带进视口：只有真的在视口外才滚，看得见就不动。 */
    private fun revealGridLine(card: View,smooth: Boolean) {
        val scroll=gridScroll ?: return
        val line=card.parent as? View ?: return
        val target=revealY(line,scroll)
        if(target==scroll.scrollY) return
        if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target)
    }
    /**
     * 追加之后把焦点交给新行的第一张卡。
     *
     * 必须等列表量完再请求焦点：新行还没测量时坐标还是 0，
     * ScrollView 会按错误的坐标把列表滚到别处（这就是「加载完跳到奇怪的位置」的来源）。
     */
    private fun focusGridCardAfterLayout(target: View,list: LinearLayout) {
        list.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View,l: Int,t: Int,r: Int,b: Int,oldL: Int,oldT: Int,oldR: Int,oldB: Int) {
                view.removeOnLayoutChangeListener(this)
                if(gridList!==list || !target.isAttachedToWindow) return
                target.requestFocus()
                gridScroll?.post { if(gridList===list) revealGridLine(target,true) }
            }
        })
    }
    /**
     * 往栅格里铺卡片：从 [line] 已有的卡片后面接着排，排满 [GRID_COLUMNS] 张才开新行。
     *
     * 「接着上一行的空位」不是可选项：一页 20 部按六列切开，最后一行只有 2 张，
     * 下一页的头 4 部必须落进这 4 个空位里，否则整份列表中间会凭空多出一条断行。
     * 首屏与向下追加共用这一份实现，因此两边的尺寸、间距、焦点与点击行为完全一致。
     */
    private fun addGridRows(list: LinearLayout,items: List<Series>,width: Int,gap: Int,posterHeight: Int,line: LinearLayout?=null) {
        var target=line
        var column=target?.childCount ?: 0
        items.forEach { item ->
            if(target==null || column>=GRID_COLUMNS) { target=newGridRow(list); column=0 }
            val row=target!!
            row.addView(gridCard(row,item,posterHeight),lp(width,-2).apply { if(column<GRID_COLUMNS-1) rightMargin=gap })
            column++
        }
    }
    /** 开一行新的海报卡：宽度铺满、行间留 6dp，行本身登记进 [gridRows] 供焦点链使用。 */
    private fun newGridRow(list: LinearLayout): LinearLayout {
        val line=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
        list.addView(line,lp(-1,-2).apply { bottomMargin=dp(6) })
        gridRows+=line
        return line
    }
    /** 一张海报卡：数据绑定、点击 / 长按 / 菜单 / 焦点行为都在这里，首屏与追加共用。 */
    private fun gridCard(line: LinearLayout,item: Series,posterHeight: Int): PosterCard {
        val position=if(tab==2) ranking?.positions?.get(item.id) else null
        val rank=position?.rank
        val progress=if(collection=="history") library.progress(item.id) else null
        val statusText=when {
            collection=="favorites" -> library.favoriteLabel(item.id)
            // 短剧没有官网榜单：没有热度值就退回剧集信息，不再整屏都是「热度暂无」。
            tab==2 -> position?.heat?.takeIf { it.isNotBlank() } ?: item.badge.ifBlank { "热门${library.contentType.label}" }
            progress!=null -> if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
            else -> item.badge
        }
        val badgeText=when {
            tab==2 -> rank?.let { "第 $it 名" } ?: ""
            else -> item.badge.takeIf { it.isNotBlank() && it!=statusText }
                ?.let { value -> if(value.length<=9) value else value.take(8)+"…" } ?: ""
        }
        val card=PosterCard(this,posterHeight,::loadCover)
        card.bind(item,statusText,badgeText,
            progress?.takeIf { it.duration>0 }?.let { (it.position*100/it.duration).toInt().coerceIn(0,100) },
            rank!=null && rank<=3)
        card.tag=item.id; catalogCards[item.id]=card; cardImages[item.id]=card.image to item.cover
        if(collection=="favorites") favoriteBadges[item.id]=card.status
        card.setOnClickListener { catalogFocus=item.id; openDetail(item,collection=="history") }
        card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
        card.setOnKeyListener { _,key,event ->
            when {
                key==KeyEvent.KEYCODE_MENU -> {
                    if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }
                    true
                }
                // 停在最后一行再按下键就是「继续往下看」：不经过底部按钮，直接把下一页接上，
                // 新内容到了就落到新行的第一张卡上。预取命中时这一下是瞬时的。
                key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0 &&
                    isGridTail(line) && appendCatalog() && hasMore -> { loadMore(); true }
                else -> false
            }
        }
        card.setOnFocusChangeListener { _,focused ->
            card.setFocused(focused)
            if(focused) {
                catalogFocus=item.id; loadCover(card.image,item.cover)
                // 焦点走到倒数两行就把下一页取回来，用户真按下去时内容已经在手上。
                if(gridRows.indexOf(line)>=gridRows.size-2) prefetchNextPage()
                if(!gridRestoring) {
                    val scroll=gridScroll
                    scroll?.post { if(gridScroll===scroll) scroll.smoothScrollTo(0,revealY(line,scroll)) }
                }
            }
        }
        return card
    }
    /**
     * 重算整张栅格的焦点链。
     *
     * 行数不多（一页 20 部、六列），整体重算比增量维护更不容易出错；
     * 追加之后也走同一条路，因此新行与旧行的上下左右关系永远一致——
     * 这就是「不会排列混乱」在焦点层面的保证。
     */
    private fun wireGridFocus(prev: View?,next: View?) {
        val rows=gridRows.filter { it.parent!=null }
        if(rows.isEmpty()) return
        val headUp=searchInput?.id ?: typeButtons[library.contentType]?.id
            ?: favoriteCheck?.id ?: historyManage?.id ?: navEntry()?.id
        val first=rows.first().getChildAt(0)?.id ?: return
        rows.forEachIndexed { rowIndex,line ->
            val cards=(0 until line.childCount).map(line::getChildAt)
            cards.forEachIndexed { columnIndex,card ->
                card.nextFocusLeftId=cards[(columnIndex-1).coerceAtLeast(0)].id
                card.nextFocusRightId=cards[(columnIndex+1).coerceAtMost(cards.lastIndex)].id
                card.nextFocusUpId=if(rowIndex==0) (headUp ?: card.id) else rows[rowIndex-1].let { up ->
                    up.getChildAt(columnIndex.coerceAtMost(up.childCount-1)).id
                }
                card.nextFocusDownId=when {
                    rowIndex<rows.lastIndex -> rows[rowIndex+1].let { down ->
                        down.getChildAt(columnIndex.coerceAtMost(down.childCount-1)).id
                    }
                    next?.isFocusable==true -> next.id
                    prev?.isFocusable==true -> prev.id
                    else -> card.id
                }
            }
        }
        nav.forEach { it.nextFocusDownId=first }
        historyManage?.nextFocusDownId=first
        favoriteCheck?.nextFocusDownId=first
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: first }
        searchInput?.nextFocusDownId=first
        val shortcut=rows.first().getChildAt(minOf(4,rows.first().childCount-1)).id
        searchButton?.nextFocusDownId=shortcut
        searchActions.forEach { it.nextFocusDownId=shortcut }
        // 列表最后一行按下键落在底部「展示更多」上，再按一次（确认或下键）就追加下一页。
        moreButton?.nextFocusUpId=rows.last().getChildAt(0).id
    }
    /**
     * 已显示进度。榜单自带总页数，所以能给「N / M 部」；
     * 首页热门只知道自己追加到第几页，就只报已显示数量，加载完再明确说「全部」。
     */
    private fun moreCountText(): String {
        val total=(ranking?.totalPages ?: 0)*pageSize
        return when {
            !hasMore -> "已显示全部 ${catalog.size} 部"
            total>catalog.size -> "已显示 ${catalog.size} / $total 部"
            else -> "已显示 ${catalog.size} 部"
        }
    }
    /** 追加一页时把两页的名次与热度合起来，否则先加载的那几页会丢掉名次。 */
    private fun mergeRanking(previous: ComicRanking?,next: ComicRanking?): ComicRanking? = when {
        previous==null -> next
        next==null -> previous
        else -> next.copy(positions=previous.positions+next.positions)
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val top=typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,pageInset(),::loadCover,
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        // 首页剧架的滚动也走同一套页头收展：向下浏览时把品牌 / 分类开关 / 顶部入口收起来。
        listScrollY=0; home.onScroll={ onListScroll(it) }
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { showCollection("hot","更多热门") } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        if(inlineTypeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) navEntry()?.requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val history=library.history(); val favorites=library.favorites()
        val later=library.later(); val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOfNotNull(
            // 剧架顺序固定为：历史记录 → 我的收藏 → 热门推荐 → 热门发现（+ 有内容才出现的稍后看）。
            // 每栏最多十部，本机没有数据或远端没有下一页的行不渲染 / 不留「查看全部」。
            // 历史记录按最近观看排序，未看完的条目确认后直接续播，整剧看完的条目只显示状态。
            HomeScreen.Shelf("history","历史记录",history.take(10).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "整剧已看完" else formatTime(p.position)}",!p.completed,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null,
                if(p.duration>0) "${formatTime(p.position)} / ${formatTime(p.duration)}" else "第 ${p.episodeIndex+1} 集") },
                if(history.size>10) { { showCollection("history") } } else null),
            HomeScreen.Shelf("favorites","我的收藏",favorites.take(10).map { HomeScreen.Entry(it,library.favoriteLabel(it.id)) },
                if(favorites.size>10) { { showCollection("favorites") } } else null),
            // 热门推荐取热门列表前十部，行末「查看全部」进热门集合页继续向下加载。
            hot.take(10).takeIf { it.isNotEmpty() }?.let { picks ->
                HomeScreen.Shelf("feature","热门推荐",
                    picks.map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" },time=it.badge) },
                    if(hot.size>10 || hasMore) { { showCollection("hot","热门推荐") } } else null)
            },
            // 热门发现接在热门推荐后面，从第 11 部开始，两个分区不重复。
            HomeScreen.Shelf("hot","热门发现 · ${library.contentType.label}",
                hot.drop(10).take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>20 || hasMore) { { showCollection("hot","热门发现") } } else null),
            // 稍后看只在用户加过内容时作为最后一栏出现。
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); return }
        if(removed && (localCatalog() || tab==0)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            rememberScroll(catalogPageKey,catalogScroll?.scrollY ?: 0)
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0) labels+="不在热门推荐中显示"
        if(collection=="history") labels+="管理历史记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="favorites"); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(collection=="history") manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        favoriteStatus?.text=favoriteMonitor.status
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            val shelves=homeShelves(); val focusedKey=currentFocus?.tag?.toString().orEmpty()
            // 焦点停在剧架卡片上时就地重建，按 key 恢复选择，不把用户挪走。
            if(!tvTools.showing && shelves.any { focusedKey.startsWith(it.key+":") }) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(shelves)
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    /** 窗口底色跟着主题走：启动那一帧、页面之间的空档不会闪出另一套材质。 */
    private fun applyWindowBackground() {
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(TvStyle.background))
    }
    /**
     * 切换主题：落盘 → 换色（含丢掉柔光位图缓存）→ 重建当前页面。
     *
     * 重建这一步不能省：已经建好的 View 上挂着旧的背景 Drawable，里面是上一套材质的
     * 投影位图与色值。设置页的重建很轻（`showCatalog()` 在 tab==3 时只重画设置页），
     * 因此这里直接重建，再把焦点按回刚点的那一行主题。
     */
    private fun switchTheme(value: TvTheme) {
        if(value==TvStyle.theme) { themeRows[value]?.requestFocus(); return }
        TvThemeStore.save(this,value)
        TvStyle.applyTheme(value)
        applyWindowBackground()
        showCatalog()
        // 重建之后把焦点按回刚点的那一行：先同步要一次，再在下一帧要一次——
        // 新视图要等这一帧布局完成，滚动容器才把它带进可见区（设置页在 720p 画布上可能刚好要滚一点）。
        themeRows[value]?.let { row -> row.requestFocus(); row.post { row.requestFocus() } }
        Toast.makeText(this,"主题已切换为 ${value.label}",Toast.LENGTH_SHORT).show()
    }
    private fun settings(parent: LinearLayout) {
        // 页面名「设置」已经进了顶部导航栏，这里不再重复一行标题，只留一句说明。
        parent.addView(text("偏好自动保存，下一部剧继续使用。",14f,TvStyle.muted).apply { setPadding(0,dp(10),0,dp(20)) })
        val groups=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
        val playback=column().apply { clipChildren=false; clipToPadding=false }
        val device=column().apply { clipChildren=false; clipToPadding=false }
        val groupBand=dp(TvStyle.SOFT_BAND_LARGE)
        /** 一个设置分组：大号凸起面板，内容四周留出柔光带。 */
        fun groupPanel(group: LinearLayout) {
            group.setPadding(dp(24)+groupBand,dp(20)+groupBand,dp(24)+groupBand,dp(20)+groupBand)
            group.background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
        }
        listOf(playback,device).forEachIndexed { i,group ->
            groupPanel(group)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(TvStyle.GAP_GROUP) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
            group.addView(text(subtitle,14f,TvStyle.muted).apply { setPadding(0,dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_BLOCK)) })
        }
        /**
         * 设置项就是整行左对齐的常规按钮：高度、圆角、焦点反馈与全站按钮完全一致，只是文字靠左。
         * 行距由 GAP_INLINE 统一给出，不再靠每一项自己写 bottomMargin。
         */
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TvButton {
            val view=TvButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,alignStart=true,action=action)
            group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
            return view
        }
        /**
         * 滑动条设置项：左侧项目名与上面几行同宽，右侧是可滑的控件。
         *
         * 与播放设置面板里那两条共用同一个 [DanmakuSlider]，改的是同一份本机偏好，
         * 因此设置页调好之后进播放界面立刻就是新的字号 / 透明度。
         */
        fun sliderSetting(group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,onValue: (Float)->Unit): DanmakuSlider {
            val line=row().apply { clipChildren=false; clipToPadding=false }
            line.addView(
                text(label,18f,TvStyle.text).apply { gravity=Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=TextUtils.TruncateAt.END },
                lp(dp(110),-2)
            )
            val slider=DanmakuSlider(this,label,min,max,step,value,onValue)
            line.addView(slider,lp(0,-2).apply { weight=1f })
            group.addView(line,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
            return slider
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        // 这些设置项都是全站统一的常规按钮，声明成 TvButton 才能参与下面三列网格的焦点串联。
        lateinit var qualityButton: TvButton
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TvButton
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TvButton
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var danmakuSetting: TvButton
        danmakuSetting=setting(playback,danmakuLabel("弹幕显示  ·  ")) { showDanmakuPicker(danmakuSetting,false) }
        lateinit var autoNextButton: TvButton
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        // 弹幕文字的两项外观（字号缩放 / 不透明度）：连续量，用滑动条而不是离散档位。
        lateinit var danmakuScaleRow: DanmakuSlider
        danmakuScaleRow=sliderSetting(playback,"字幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
            library.danmakuScale=value; danmakuView?.textScale=value
        }
        lateinit var danmakuAlphaRow: DanmakuSlider
        danmakuAlphaRow=sliderSetting(playback,"字幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
            library.danmakuAlpha=value; danmakuView?.textAlpha=value
        }
        lateinit var backup: TvButton
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TvButton
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        // 可选：填上 App 抓到的口令后，搜索直接使用 App 搜索接口；留空时退回官网搜索。
        lateinit var session: TvButton
        fun sessionLabel()="搜索登录态  ·  ${if(library.searchToken.isBlank()) "未设置" else "已设置"}"
        session=setting(device,sessionLabel()) {
            tvTools.searchSession(session,library.searchToken,{ token ->
                library.searchToken=token; applySessionToken(); session.text=sessionLabel()
                Toast.makeText(this,"搜索登录态已保存",Toast.LENGTH_SHORT).show()
            },{
                library.searchToken=""; applySessionToken(); session.text=sessionLabel()
                Toast.makeText(this,"搜索登录态已清除",Toast.LENGTH_SHORT).show()
            })
        }
        // 本版不含应用内更新：这一行只读出版本号，没有网络请求、没有下载，也不申请安装权限。
        lateinit var versionRow: TvButton
        versionRow=setting(device,"版本信息  ·  ${BuildConfig.VERSION_NAME}") {
            tvTools.info("版本信息","当前版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n本版不含应用内更新：新版 APK 请自行下载后用系统安装器覆盖安装，本机记录保留。",versionRow)
        }
        lateinit var license: TvButton
        license=setting(device,"开源许可") {
            tvTools.info("开源许可","本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV-updates。",license)
        }
        // —— 主题外观：整宽分组 + 一行 THEME_GRID_COLUMNS 个芯片 ——
        // 14 套材质排成 4 行，比「一行一个主题」省下三分之二的高度；
        // 芯片就是全站统一的按钮（紧凑档），选中态是「按进去」的凹槽，凹下去的那一枚就是当前主题。
        val appearance=column().apply { clipChildren=false; clipToPadding=false }
        groupPanel(appearance)
        parent.addView(appearance,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
        heading(appearance,"主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
        val themeGrid=column().apply { clipChildren=false; clipToPadding=false }
        appearance.addView(themeGrid,lp(-1,-2))
        themeRows.clear()
        TvTheme.entries.chunked(THEME_GRID_COLUMNS).forEachIndexed { rowIndex, line ->
            val row=row().apply { if(rowIndex>0) setPadding(0,dp(TvStyle.GAP_INLINE),0,0) }
            line.forEachIndexed { index, option ->
                val chip=TvButton(this,option.label,ButtonSize.COMPACT,ButtonTone.SECONDARY) { switchTheme(option) }
                chip.setSelectedState(option==TvStyle.theme)
                chip.contentDescription=option.label+"，"+option.scene+if(option==TvStyle.theme) "，当前主题" else ""
                row.addView(chip,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
                themeRows[option]=chip
            }
            // 最后一行不满时用空位占住列宽，芯片不会被拉成半屏宽。
            repeat(THEME_GRID_COLUMNS-line.size) { row.addView(Space(this),lp(0,1).apply { weight=1f }) }
            themeGrid.addView(row,lp(-1,-2))
        }
        appearance.addView(
            text("当前：${TvStyle.theme.label} · ${TvStyle.theme.scene}",13f,TvStyle.textDim)
                .apply { setPadding(0,dp(TvStyle.GAP_BLOCK),0,0) },
            lp(-1,-2)
        )
        // —— 焦点串联 ——
        // 上面两列：列内上下、列间左右按行号对齐；行数不足时停在原地，焦点不会跑到框外。
        val columns=listOf(
            listOf<View>(qualityButton,defaultSpeed,frame,danmakuSetting,autoNextButton,danmakuScaleRow,danmakuAlphaRow),
            listOf<View>(backup,hidden,session,versionRow,license)
        )
        columns.forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: (navEntry()?.id ?: View.NO_ID)
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        columns.forEachIndexed { column,items -> items.forEachIndexed { index,view ->
            view.nextFocusLeftId=(columns.getOrNull(column-1)?.getOrNull(index) ?: view).id
            view.nextFocusRightId=(columns.getOrNull(column+1)?.getOrNull(index) ?: view).id
        } }
        // 主题网格：行内左右相连、行间上下相连，两端停住；第一行往上回到上面两列的最后一行
        // （左半边回到「播放偏好」的末项，右半边回到「片单与设备」的末项）。
        val themeLines=TvTheme.entries.mapNotNull { themeRows[it] }.chunked(THEME_GRID_COLUMNS)
        themeLines.forEachIndexed { rowIndex, line ->
            line.forEachIndexed { index, chip ->
                chip.nextFocusLeftId=line.getOrNull(index-1)?.id ?: chip.id
                chip.nextFocusRightId=line.getOrNull(index+1)?.id ?: chip.id
                chip.nextFocusUpId=themeLines.getOrNull(rowIndex-1)?.getOrNull(index)?.id
                    ?: if(index<THEME_GRID_COLUMNS/2) danmakuAlphaRow.id else license.id
                chip.nextFocusDownId=themeLines.getOrNull(rowIndex+1)?.getOrNull(index)?.id ?: chip.id
            }
        }
        danmakuAlphaRow.nextFocusDownId=(themeRows[TvTheme.entries.first()] ?: danmakuAlphaRow).id
        license.nextFocusDownId=(themeRows[TvTheme.entries[THEME_GRID_COLUMNS/2]] ?: license).id
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        parent.addView(text("红果 TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
        navEntry()?.requestFocus()
    }
    private fun runSearch(value: String,type: ContentType=library.contentType) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        if(library.contentType!=type) { library.contentType=type; for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) } }
        library.rememberSearch(clean,type); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { "${it.type.label} · ${it.query}" },anchor,{ index -> runSearch(history[index].query,history[index].type) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除历史记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        // 详情页不留页头：顶部是完整竖版海报 + 剧集信息（78dp 栅格内），
        // 下方选集网格整幅贴底，与播放器选集面板同一份实现。
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column().apply { clipChildren=false; clipToPadding=false }
        root.addView(container,FrameLayout.LayoutParams(-1,-1))
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false } // focus-bleed-exempt: 边界就是整屏宽，最近的焦点控件（操作按钮 / 选集外壳）离屏幕边缘 ≥71dp
        val content=column().apply { clipChildren=false; clipToPadding=false }
        scroll.addView(content); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
        val body=column().apply {
            clipChildren=false; clipToPadding=false
            setPadding(pageInset(),dp(18),pageInset(),dp(14))
        }
        // 顶部：完整竖版海报 + 剧集信息左右分栏。
        // 封面按原始比例 FIT_CENTER 整张显示（接口封面约 7:10），不再铺满裁切；
        // 文字改到海报右侧的纯色页面上，因此也不需要压暗层或文字投影。
        // 海报高度吃掉「屏高 − 选集块 − 页面内边距」的剩余空间，整页因此铺满屏幕，
        // 底部不会留出一块空白；估算偏差由下面的弹簧 Space 吸收，内容过高时才滚动。
        val screenHeight=resources.displayMetrics.heightPixels
        // 选集网格的单元格高度含柔光带，因此整块比改版前高约 30dp；预留给它的空间跟着加。
        val episodesHeight=dp(206+2*TvStyle.SOFT_BAND+20)
        val posterHeight=(screenHeight-dp(32)-episodesHeight)
            .coerceIn(dp(240),(screenHeight*.72f).toInt())
        val posterWidth=(posterHeight*7f/10f).toInt()+2*dp(TvStyle.SOFT_BAND)
        val hero=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
        // 海报卡座：一块凸起底板，海报按柔光带内缩嵌在里面，不再是「描边 + 通栏图」。
        val posterBand=dp(TvStyle.SOFT_BAND)
        val posterBox=FrameLayout(this).apply {
            background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_CARD,depth=1.15f)
            clipChildren=true; clipToPadding=true
            setPadding(posterBand,posterBand,posterBand,posterBand)
        }
        roundCorners(posterBox,TvStyle.RADIUS_CARD)
        val backdrop=ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        roundCorners(backdrop,TvStyle.RADIUS_POSTER)
        posterBox.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
        loadCover(backdrop,data.series.cover)
        hero.addView(posterBox,lp(posterWidth,posterHeight).apply { rightMargin=dp(44) })
        val info=column().apply {
            clipChildren=false; clipToPadding=false
            setPadding(0,dp(4),0,0)
        }
        hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,34f).apply {
            setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END
        })
        val meta=row().apply { setPadding(0,dp(14),0,dp(14)) }
        meta.addView(text("★",16f,TvStyle.tips))
        meta.addView(text("全 ${data.episodes.size} 集",16f,TvStyle.textDim).apply { setPadding(dp(8),0,dp(16),0) })
        meta.addView(text(data.series.tags,16f,TvStyle.textDim).apply {
            maxLines=1; ellipsize=TextUtils.TruncateAt.END
        })
        info.addView(meta)
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,16f,TvStyle.textDim).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        // 按钮行四边各留 FOCUS_BLEED，放大后的焦点底色才不会在这一行里被切平；
        // 行本身用负外边距抵回去，按钮的位置与原来完全一致（仍从内容线开始）。
        val actions=row().apply {
            setPadding(dp(TvStyle.FOCUS_BLEED),dp(TvStyle.GAP_BLOCK),dp(TvStyle.FOCUS_BLEED),dp(TvStyle.FOCUS_BLEED))
        }
        info.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        // 这一行的三个按钮同档同高（56dp），不再出现「主按钮 56、旁边两个 48」的高低差；
        // 强调色仍然只给主操作，尺寸与语义分开管。
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看",tone=ButtonTone.PRIMARY,size=ButtonSize.HERO) { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TvButton
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏",size=ButtonSize.HERO) {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TvButton
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介",size=ButtonSize.HERO) {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        body.addView(hero,lp(-1,-2))
        content.addView(body,lp(-1,-2))
        // 弹簧：内容不足一屏时把选集推到屏幕底部，而不是在页面底部留一块空白。
        content.minimumHeight=screenHeight
        content.addView(Space(this),lp(-1,0).apply { weight=1f })
        // 选集与播放界面共用同一个 EpisodeGrid：这里整幅贴在页面末尾（不加页面栅格内边距），
        // 标题行、集数行、单元格尺寸与播放器弹窗一致，翻页也是同一套四向逻辑。
        // 第一页第一行按上时网格没有上一页可翻，EpisodeGrid.turn() 返回 false 把按键交回焦点系统，
        // 焦点因此仍然能回到上面的「继续观看 / 收藏 / 完整简介」这一行（详情页不留页头，上面只有这一行）。
        val episodes=EpisodeGrid(this,data.episodes.size,episodeIndex,player=false,
            { index -> playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) })
        // 简介页的选集不套背板：它就是页面里的一块内容，底板会读成「一个可以按进去的大盒子」。
        // 播放界面里同一份网格仍然用浮层渐变（播放界面不套新拟态，见 EpisodeGrid 的注释）。
        // 选集整幅贴底，但左右要与页面内容线对齐：网格自己的横向内边距只留给播放界面
        // （那是整幅压屏的浮层），简介页用一个补回焦点余量的外壳把它托到内容线上，
        // 「选集」这一行与第一格于是和上面的海报、按钮同一条竖线。
        val episodeHost=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
        episodeHost.addView(episodes,lp(-1,-2))
        content.addView(episodeHost,lp(-1,-2))
        if(focusEpisode) episodes.focusCurrent() else play.requestFocus()
    }
    /**
     * 取本集弹幕（原 App 播放时看到的那一份，见 [ContentRepository.danmaku]）。
     *
     * 只有播放画面上的弹幕层会用到它：取不到、这一集没有、平台不再返回，都只是「没有弹幕」，
     * 不弹提示、不影响播放。[danmakuToken] 每换一集自增一次，
     * 在途的窗口循环发现 token 变了就停手，旧剧的弹幕不会接到新一集上。
     */
    private fun loadDanmaku(episodeId: String,seriesId: String) {
        val view=danmakuView ?: return
        view.resetCursor(); view.mode=library.danmakuMode
        // 同一集重复进入（换清晰度、播放重试）不重复拉弹幕：已经拿到的这一份直接沿用，
        // 只把播放位置指针重置回开头。空结果也算「拿到」——这一集确实没有弹幕。
        if(danmakuFor==episodeId && danmakuLoaded) return
        val token=danmakuToken.incrementAndGet()
        danmakuFor=episodeId
        danmakuLoaded=false
        danmakuCount=-1
        view.clear()
        if(library.danmakuMode==DanmakuMode.HIDDEN) return
        danmakuWorker.execute {
            val items=runCatching {
                // 边拉边画：一个 30 秒窗口到手就先铺上屏。整集要拉几十个窗口、每个之间还要留频控间隔，
                // 等全量再画等于前十几秒一条弹幕都没有，看上去和「弹幕功能没生效」一样。
                repository.danmaku(episodeId,seriesId,0,{ danmakuToken.get()!=token }) { partial ->
                    main.post {
                        if(isDestroyed || danmakuToken.get()!=token || screen!="player") return@post
                        danmakuView?.setDanmaku(partial)
                    }
                }
            }
                .onFailure { android.util.Log.w("HongguoTV","Danmaku fetch failed episode=$episodeId",it) }
                .getOrDefault(emptyList())
            main.post {
                if(isDestroyed || danmakuToken.get()!=token || screen!="player") return@post
                danmakuLoaded=true; danmakuCount=items.size
                android.util.Log.i("HongguoTV","Danmaku episode=$episodeId count=${items.size} mode=${library.danmakuMode.storedValue}")
                danmakuView?.setDanmaku(items)
                // 一条都没取到才提示一次：平台上只有部分剧集对匿名设备放出弹幕，
                // 不说明的话「屏幕上没有弹幕」会被当成功能坏了。
                if(items.isEmpty()) Toast.makeText(this,"本集没有弹幕（平台只对部分剧集开放匿名弹幕）",Toast.LENGTH_SHORT).show()
                updatePlaybackText()
            }
        }
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        dismissPlaybackMenu()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            // 弹幕层压在画面之上、标题条与控制层之下：隐藏弹幕时整层不可见，也不参与绘制。
            // 播放位置与播放状态都从播放器现取，因此暂停 / 跳转 / 倍速都不会和画面脱节。
            val overlay=DanmakuView(this).apply {
                mode=library.danmakuMode
                // 字号与透明度是本机偏好（设置面板里滑出来的），换集、换清晰度重建这一层时都要带上。
                textScale=library.danmakuScale
                textAlpha=library.danmakuAlpha
                positionMillis={ player?.currentPosition ?: 0L }
                playing={ player?.isPlaying==true }
            }
            danmakuView=overlay
            root.addView(overlay,FrameLayout.LayoutParams(-1,-1))
            // 顶部标题条：对齐爆米花 activity_player —— 整宽 96dp、50% 黑 → 透明，
            // 左侧 24sp 粗体剧名、右侧同字号时钟，左右安全边距 78dp。
            // 播放界面压在视频上，文字固定用暖白（TvStyle.playerText），不跟主题：
            // 浅色主题下如果跟着变成深色字，这条标题条就完全看不见了。
            playbackTitle=text("",24f,TvStyle.playerText).apply {
                maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD)
                setShadowLayer(5f,0f,2f,0x80000000.toInt())
            }
            val clock=text("",24f,TvStyle.playerText).apply { setShadowLayer(5f,0f,2f,0x80000000.toInt()) }
            playerClock=clock
            // 顶部标题条：整宽 96dp、50% 黑 → 透明，左侧 24sp 粗体剧名、右侧同字号时钟。
            // 这一条压在画面上，加柔光底板反而会挡住画面、也不符合「播放界面尽量不打扰」的取向。
            // 播放时不打扰：默认收起，只有唤出播放界面（底部控制条 / 播放菜单 / 选集面板）才出现，
            // 平时屏幕上只剩画面和弹幕。
            val topBar=row().apply {
                setPadding(dp(78),0,dp(78),0)
                background=TvStyle.gradient(0x80000000.toInt(),Color.TRANSPARENT)
                visibility=View.GONE
            }
            topBar.addView(playbackTitle,lp(0,-2).apply { weight=1f })
            topBar.addView(clock,lp(-2,-2))
            root.addView(topBar,FrameLayout.LayoutParams(-1,dp(96),Gravity.TOP))
            playbackTopBar=topBar

            // 底部控制层保持原样：透明 → 60% 黑 → 90% 黑的三段渐变，压在画面上自然暗下去。
            // 播放界面（标题条 + 控制层 + 进度条）不套新拟态：任何柔光底板都会和画面抢注意力，
            // 这一层只负责让画面暗下来、让控制项读得清。
            // 第一行 = 42.5dp 播放键 + 进度条 + 右对齐时间；第二行 = 20sp 状态项。
            hud=column().apply {
                setPadding(dp(78),dp(70),dp(78),0)
                background=TvStyle.panel()
            }
            val transport=row()
            transportIcon=ImageView(this).apply {
                setImageResource(R.drawable.ic_player_play)
                scaleType=ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setOnClickListener { togglePlayback() }
            }
            transport.addView(transportIcon,lp(dp(43),dp(43)))
            progressBar=SeekIndicator(this)
            transport.addView(progressBar,lp(0,dp(43)).apply { weight=1f; leftMargin=dp(13); rightMargin=dp(26) })
            playbackText=text("",18f,TvStyle.playerText).apply { setShadowLayer(4f,0f,1f,0x80000000.toInt()) }
            transport.addView(playbackText,lp(-2,-2))
            hud.addView(transport,lp(-1,dp(47)))
            // 状态行只显示当前值、只做点击入口（遥控器走 上=播放设置菜单、下=选集），因此不抢焦点。
            val options=row().apply { setPadding(0,dp(20),0,0) }
            fun option(label: String,action: ()->Unit): TextView {
                val item=text(label,20f,TvStyle.playerDim).apply {
                    gravity=Gravity.CENTER; maxLines=1
                    setOnClickListener { action() }
                    // 状态项的焦点只做「更亮一档」，同样不引入颜色。
                    setOnFocusChangeListener { view,focused -> (view as TextView).setTextColor(if(focused) TvStyle.playerText else TvStyle.playerDim) }
                }
                options.addView(item,lp(-2,-2).apply { rightMargin=dp(34) })
                return item
            }
            // 点击任一状态项直接打开竖向菜单的对应项目。
            qualityOption=option("清晰度 ${library.maxQuality}P") { showPlaybackMenu(MENU_ROW_QUALITY) }
            speedOption=option("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}") { showPlaybackMenu(MENU_ROW_SPEED) }
            frameOption=option("画面 ${library.frameMode.label}") { showPlaybackMenu(MENU_ROW_FRAME) }
            danmakuOption=option("弹幕 ${library.danmakuMode.label}") { showPlaybackMenu(MENU_ROW_DANMAKU) }
            sleepOption=option("定时 ${sleepTimer.label()}") { showPlaybackMenu(MENU_ROW_SLEEP) }
            hud.addView(options,lp(-1,-2))
            // 底部居中的下拉箭头（爆米花 scroll_indicator）：点击直接打开选集。
            hud.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_down)
                    scaleType=ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnClickListener { showEpisodePanel(hud) }
                },
                lp(dp(75),dp(38)).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(8) }
            )
            controls=row().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            // 这个滚动容器高度是 WRAP_CONTENT，内容永远不超过自己，因此不需要外壳裁剪；
            // 容器自己也不裁剪，播放器按钮放大后的底色才能画出来。
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM)) // focus-bleed-exempt: 边界就是整屏宽，播放器按钮离边缘 ≥78dp
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        loadDanmaku(data.episodes[episodeIndex],data.series.id)
        playbackText.text="正在获取播放地址…"; progressBar.progress=0f; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showHud() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    if(!active()) return
                    if(hud.visibility==View.VISIBLE) showHud()
                    syncMediaSession()
                    // 弹幕层的动画循环在暂停时停下，重新播放要把它叫醒。
                    if(playing) danmakuView?.invalidate()
                }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(!active()) return
                    syncMediaSession()
                    // 跳转之后弹幕要跟着画面走：指针从头重找一次，再画一帧。
                    danmakuView?.resetCursor(); danmakuView?.invalidate()
                }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        // 出错界面要让用户看清是哪一集，标题条这时必须露出来。
        playbackTopBar?.visibility=View.VISIBLE
        dismissPlaybackMenu()
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; dismissPlaybackMenu(); controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
        // 标题条跟底部控制条同一套显隐：播放时屏幕上不留任何文字。
        playbackTopBar?.visibility=View.GONE
    }
    private fun showHud() {
        // 设置菜单打开时底部控制条保持收起，避免和菜单抢同一块画面。
        if(screen!="player" || playbackMenu!=null) return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        playbackTopBar?.visibility=View.VISIBLE
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        playerClock?.text=clockFormat.format(java.util.Date())
        if(screen!="player") return
        // 播放键图标与选项行的当前值：爆米花把状态放在图标和选项文字里，不额外占一行。
        transportIcon?.setImageResource(if(player?.playWhenReady==true) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        qualityOption?.text="清晰度 "+if(quality.isBlank()) "${library.maxQuality}P" else quality
        speedOption?.text="倍速 "+PlaybackSpeed.label(if(playbackReady) player?.playbackParameters?.speed ?: library.playbackSpeed else library.playbackSpeed)
        frameOption?.text="画面模式 "+library.frameMode.label
        // 状态行只放显示方式：弹幕条数放在「设置 → 弹幕显示」那一行，
        // 播放界面的这一排已经够长，再加数字会被屏宽裁掉。
        danmakuOption?.text="弹幕 "+library.danmakuMode.label
        sleepOption?.text="定时 "+sleepTimer.label()
        if(playError) return
        if(sleepStopped) { playbackText.text="定时停止 · 按播放继续"; return }
        if(!playbackReady) { playbackText.text=if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val span="${formatSpan(position)} / ${formatSpan(duration)}"
        playbackText.text=if(p.playbackState==Player.STATE_BUFFERING) "缓冲中 · $span" else if(p.playbackState==Player.STATE_ENDED) "本集已结束 · $span" else span
        progressBar.progress=if(duration>0) position.toFloat()/duration else 0f
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        sleepChoice=0
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(playbackMenu!=null) showPlaybackMenu(MENU_ROW_SLEEP); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    /** 定时项目的 8 个选项：关闭排在最前，选中态跟随本次观看已生效的定时。 */
    private fun sleepChoices(menu: PlaybackMenu): List<PlaybackMenu.Choice> {
        val current=if(sleepTimer.active) sleepChoice else 0
        return sleepLabels.mapIndexed { index,label ->
            PlaybackMenu.Choice(label,current==index) {
                sleepChoice=index
                when(index) {
                    0 -> sleepTimer.cancel()
                    in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[index-1])
                    else -> sleepTimer.afterEpisodes(listOf(1,3,5)[index-5])
                }
                menu.select(MENU_ROW_SLEEP,index); updatePlaybackText()
            }
        }
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    /**
     * 弹幕显示方式。
     *
     * 三档：全屏（整幅画面从上到下都是弹幕轨道）、上方（只在画面上部约三分之一滚动）、隐藏。
     * 弹幕数据本身就是原 App 播放时用的那一份（见 [ContentRepository.danmaku]）。
     */
    private fun showDanmakuPicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("弹幕显示方式",listOf("全屏弹幕 · 整幅画面从上到下都是弹幕轨道","上方弹幕 · 只在画面上部滚动","隐藏弹幕 · 完全不显示弹幕"),anchor,{ index ->
            library.danmakuMode=DanmakuMode.entries[index]; danmakuView?.mode=library.danmakuMode
            if(anchor.tag!="playback-settings") anchor.text=danmakuLabel(if(inPlayer) "弹幕 " else "弹幕显示  ·  ")
        })
    }
    /**
     * 「弹幕显示」那一行的文案：显示方式 + 最近一集实际取到多少条。
     *
     * 平台只对部分剧集放开匿名弹幕，屏幕上「一条都没有」和「功能没生效」从画面上分不出来，
     * 这一行就是给这件事一个明确的读数（播放界面那排状态项太窄，放不下数字）。
     */
    private fun danmakuLabel(prefix: String)=prefix+library.danmakuMode.label+
        if(danmakuCount<0) "" else "（上一集 ${if(danmakuCount>0) "$danmakuCount 条" else "无弹幕"}）"
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun dismissPlaybackMenu() {
        playbackMenu?.let { menu -> if(menu.hasFocus()) menu.clearFocus() }
        // 面板现在是「外壳 → 滚动容器 → 面板」三层，直接 removeView(menu) 摘不掉任何东西
        // （menu 的父级是滚动容器），浮层会留在画面上。这里按最外层摘。
        playbackMenuShell?.let { shell -> (shell.parent as? ViewGroup)?.removeView(shell) }
        playbackMenuShell=null
        playbackMenu=null
    }
    /**
     * 遥控器「上」（或菜单键）打开底部设置面板：
     * 清晰度 / 倍速 / 画面模式 / 弹幕 / 字幕大小 / 字幕透明度 / 定时。
     *
     * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
     * 面板高度**固定为屏幕下方三分之一**，装不下的行留在滚动容器里，遥控器焦点走到哪一行
     * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此七行全都能按到。
     *
     * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
     * [focusChoice] 为负数时落在该项目当前选中的选项上。
     */
    private fun showPlaybackMenu(focusRow: Int=0,focusChoice: Int=-1) {
        if(screen!="player" || playError) return
        dismissPlaybackMenu()
        // 先建面板再逐行追加：选项的点击回调要引用面板本身，不能在构造链里读还没赋值的变量。
        val menu=PlaybackMenu(this)
        menu
            .addRow("清晰度",PlaybackQuality.options.mapIndexed { index,value ->
                PlaybackMenu.Choice("${value}P",value==library.maxQuality) {
                    val changed=library.maxQuality!=value
                    library.maxQuality=value
                    // 换清晰度要重新解析播放地址，播放器复用后菜单需要按新状态重建。
                    if(changed) { playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay); showPlaybackMenu(MENU_ROW_QUALITY,index) }
                    else { menu.select(MENU_ROW_QUALITY,index); updatePlaybackText() }
                }
            })
            .addRow("倍速",PlaybackSpeed.options.mapIndexed { index,value ->
                PlaybackMenu.Choice(PlaybackSpeed.label(value),value==library.playbackSpeed) {
                    library.playbackSpeed=value; player?.setPlaybackSpeed(value)
                    menu.select(MENU_ROW_SPEED,index); updatePlaybackText()
                }
            })
            .addRow("画面模式",VideoFrameMode.entries.mapIndexed { index,mode ->
                PlaybackMenu.Choice(mode.label,mode==library.frameMode) {
                    library.frameMode=mode; playerView?.resizeMode=frameResizeMode()
                    menu.select(MENU_ROW_FRAME,index); updatePlaybackText()
                }
            })
            // 弹幕显示方式：全屏 / 上方 / 隐藏。选完立刻作用在画面上的弹幕层，并存进本机设置。
            .addRow("弹幕",DanmakuMode.entries.mapIndexed { index,mode ->
                PlaybackMenu.Choice(mode.label,mode==library.danmakuMode) {
                    val previous=library.danmakuMode
                    library.danmakuMode=mode
                    danmakuView?.mode=mode
                    // 隐藏档不发请求：从隐藏切回可见时如果这一集还没取过，这里补一次；
                    // 已经取过的（raw 还在）只换画法，由 mode 的 setter 重排轨道，不再请求。
                    if(previous==DanmakuMode.HIDDEN && mode!=DanmakuMode.HIDDEN && !danmakuLoaded) {
                        detail?.let { data -> data.episodes.getOrNull(episodeIndex)?.let { episode -> loadDanmaku(episode,data.series.id) } }
                    } else if(mode!=DanmakuMode.HIDDEN) danmakuView?.invalidate()
                    menu.select(MENU_ROW_DANMAKU,index); updatePlaybackText()
                }
            })
            // 弹幕文字的两项连续外观：字号缩放与不透明度。左右键滑动，改完立刻作用在当前画面上。
            .addSlider("字幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
                library.danmakuScale=value; danmakuView?.textScale=value
            }
            .addSlider("字幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
                library.danmakuAlpha=value; danmakuView?.textAlpha=value
            }
            .addRow("定时",sleepChoices(menu))
            .wire()
        playbackMenu=menu
        panel=true
        // 设置菜单打开时收起底部控制条：参考界面里菜单就是这一层唯一的浮层。
        main.removeCallbacks(hideHud)
        hud.visibility=View.GONE
        // 菜单占着下半屏，标题条这时留在上面才读得出「在放哪一集」。
        playbackTopBar?.visibility=View.VISIBLE
        // 整宽贴在画面下方，**高度锁死在屏幕高度的三分之一**，超出的行靠上下键滚出来。
        // 面板本身就是滚动内容，渐变背板画在外壳上：否则背板会跟着内容一起被拉长，
        // 滚到下半段时面板顶部会变成半黑，而不是「从透明渐渐暗下去」。
        val shell=tvScrollHost(ScrollView(this).apply {
            isVerticalScrollBarEnabled=false
            isFocusable=false
            clipChildren=false
            clipToPadding=false
            // 上下各留一点余量：焦点放大 1.05 的那圈底色画在行的边界之外，留白不够会被裁掉。
            setPadding(0,dp(TvStyle.SOFT_BAND),0,dp(TvStyle.SOFT_BAND))
            addView(menu,FrameLayout.LayoutParams(-1,-2))
        }).apply { background=TvStyle.overlayPanel() }
        // 面板自己不再画背板（背板已经在外壳上），否则两层渐变叠起来会比选集面板深一档。
        menu.background=null
        playbackMenuShell=shell
        root.addView(shell,FrameLayout.LayoutParams(-1,resources.displayMetrics.heightPixels/3,Gravity.BOTTOM))
        if(focusChoice<0) menu.focusSelected(focusRow) else menu.focus(focusRow,focusChoice)
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        // 与播放器菜单同一套选项按钮：整行左对齐的胶囊，当前值用强调色标出。
        val picker=TvDialog(this,"全局默认倍速").choices(speeds.map(PlaybackSpeed::label),selected) { index ->
            library.playbackSpeed=speeds[index]
            if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
            if(inPlayer) updatePlaybackText()
        }
        picker.button("取消")
        speedDialog=picker
        picker.show(anchor) {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val picker=TvDialog(this,"全局默认清晰度").choices(options.map { "${it}P" },options.indexOf(library.maxQuality)) { index ->
            val target=options[index]; val changed=library.maxQuality!=target
            library.maxQuality=target
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
            if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
        }
        picker.button("取消")
        picker.show(anchor)
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        // 选集面板也要知道在放哪一集，标题条跟着面板一起露出来。
        playbackTopBar?.visibility=View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        // 面板内容与简介页共用同一个 EpisodeGrid，只有宿主不同（贴底弹窗 / 页面末尾），
        // 翻页方式一致：左右到行两端、上下到第一行 / 最后一行到边缘翻页。
        val grid=EpisodeGrid(this,data.episodes.size,episodeIndex,player=true,
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            })
        grid.fillHeight=true
        val next=EpisodePanel(this,grid) {
            episodePanel=null
            if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                if(anchor.isAttachedToWindow) anchor.requestFocus()
                if(overlayWasVisible) showHud() else hidePlaybackOverlay()
            }
        }
        episodePanel=next; next.show()
    }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    /** 播放器时间轴：爆米花固定 hh:mm:ss，短剧也只有几分钟也不会跳字宽。 */
    private fun formatSpan(millis: Long): String {
        val seconds=millis.coerceAtLeast(0)/1000
        return "%02d:%02d:%02d".format(seconds/3600,(seconds%3600)/60,seconds%60)
    }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        dismissPlaybackMenu()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; player?.release(); player=null; playbackReady=false
        // 在途的弹幕请求作废，弹幕层连同它的轨道一起丢掉。
        danmakuToken.incrementAndGet(); danmakuFor=""; danmakuLoaded=false; danmakuCount=-1; danmakuView=null
        playbackTopBar=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepChoice=0; sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { confirmExit(); return }
        when(screen) {
            "player" -> if(playbackMenu!=null || (hud.visibility==View.VISIBLE && !playError)) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            // 品牌不再是按钮，返回键就是唯一的「回主界面」入口：集合页回到它所在的标签（首页），
            // 搜索 / 排行榜 / 设置直接切回首页，已经在首页时再按才弹退出确认。
            else -> when {
                collection!=null -> { leaveCollection(); showCatalog() }
                tab!=0 -> switchTab(0)
                nav.any { it.hasFocus() } -> confirmExit()
                else -> nav.firstOrNull()?.requestFocus()
            }
        }
    }
    /**
     * 主界面按返回不再直接退出：先弹一个确认框，确认后才 finish。
     * 弹窗走全站统一的 [TvDialog]，焦点默认落在「取消」上，误按返回键不会退出应用；
     * 取消后焦点回到首页入口，再按返回才会重新弹出。
     */
    private fun confirmExit() {
        tvTools.confirm("退出红果 TV","收藏与观看进度已经保存在本机，确定要退出吗？",nav.firstOrNull() ?: currentFocus) { finish() }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            // 菜单打开时方向键一律交给焦点系统：PlaybackMenu.wire() 已把四向锁在菜单内部，
            // 停在最后一行（定时）再按下键，焦点按 nextFocusDownId 落在自己身上、原地不动。
            // 这里**不能**再接管下键去弹选集：菜单是「上键」打开的浮层，用户在里面按到边缘
            // 只应该表示「到头了」，而不是顺带换一个界面；选集仍然只由菜单关闭后的下键、
            // 底部居中的下拉箭头，以及返回键回到简介页进入。
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPlaybackMenu()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); sleepChoice=0; favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 柔光位图是可重建的纯缓存，内存吃紧时先丢它。
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Soft.trim()
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); danmakuWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
