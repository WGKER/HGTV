// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

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
import com.hgtv.star.core.*
import java.util.concurrent.Executors

/** 远端长列表（排行榜页的四个榜单、搜索结果）底部「展示更多」按钮的 tag，同时用作「焦点留在该按钮上」的标记。 */
private const val LOAD_MORE="load:more"

/**
 * 顶部四个页面入口的标签下标。
 *
 * 圆形入口的顺序是 **搜索 → 分类 → 榜单 → 设置**（`nav` 的下标 0..3，
 * 而 [MainActivity.navEntry] 用 `nav[tab-1]` 取，所以这四个标签从 1 开始编号）。
 * 首页是 0：品牌「红果TV」是标识不是按钮，不占入口位。
 */
private const val TAB_HOME=0
private const val TAB_SEARCH=1
private const val TAB_CATEGORY=2
private const val TAB_RANK=3
private const val TAB_SETTINGS=4

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
 * 播放设置面板的行号。
 *
 * 面板里好几处「改完就地重画这一行、再把焦点按回同一格」的调用都按行号寻址，
 * 中间插一行就会全部错位（改了清晰度结果选中态落在倍速上）。写死数字的代价已经出现过一次，
 * 因此行号收敛成常量，以后插行只改这里。
 *
 * 注意「弹幕大小 / 弹幕透明度」两行在**隐藏弹幕**时不存在（整行不添加），
 * 所以「定时」的行号不是固定的：用 [sleepRow] 现算，不要再写死 6。
 */
private const val MENU_ROW_QUALITY=0
private const val MENU_ROW_SPEED=1
private const val MENU_ROW_FRAME=2
private const val MENU_ROW_DANMAKU=3
private const val MENU_ROW_SCALE=4
private const val MENU_ROW_ALPHA=5

/**
 * 「定时」那一行的下标。
 *
 * 弹幕可见时它在两条外观项之后（[MENU_ROW_ALPHA] + 1），隐藏时那两行根本不添加，
 * 它直接跟在「弹幕」后面（[MENU_ROW_DANMAKU] + 1）。判定与设置页同一条规则
 * （[DanmakuMode.showsStyle]），只是这里要显式传进来——它是 Activity 的字段。
 */
private fun sleepRow(showsStyle: Boolean)=if(showsStyle) MENU_ROW_ALPHA+1 else MENU_ROW_DANMAKU+1

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
    /**
     * 本机记录集合页（收藏 / 历史 / 稍后观看）。
     *
     * 「热门集合页」整个去掉了：首页四个「热播…」剧架的行末「查看全部」直接进**对应的榜单页**
     * （见 [rankBoard]），与排行榜页读的是同一份数据，不再有第二套远端列表。
     */
    private var collection: String?=null
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    /**
     * 页面内的一行提示（替代 Toast）。
     *
     * 应用里不再弹系统 Toast：它是独立于界面的一层浮窗，样式、字号、焦点行为都不受本应用控制，
     * 电视上还会盖在画面正中。需要「刚刚发生了什么」这类反馈时，改成在列表上方显示一行淡字
     * （[catalogHint]），随下一次翻页 / 刷新自动消失；不需要额外说明的（收藏、切主题、删除记录等）
     * 直接靠界面本身的变化表达。
     */
    private var catalogHint: String?=null
    private var catalogHintView: TextView?=null
    private val hideHint=Runnable { catalogHint=null; catalogHintView?.visibility=View.GONE }
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
    /** 应用内更新：前台空闲时检查新版，下载校验后交给系统安装器。 */
    private lateinit var appUpdater: AppUpdater
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
                android.util.Log.i("HGTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
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
    /**
     * 排行榜页当前看的是哪一个榜单。首页四个剧架的「查看全部」也会改它，
     * 因此「从首页进榜单」与「在榜单页里切换」是同一条路径、同一份数据。
     */
    private var rankBoard=RankBoard.COMIC
    /** 首页四个剧架的内容（榜单第 1 页），按榜单分别存。 */
    private var homeFeed=mutableMapOf<RankBoard,CatalogPage>()
    /**
     * 这一页的内容落地后，焦点是不是要落到第一张卡上。
     *
     * **不要靠「现在焦点在哪」去猜**：进榜单页 / 切榜单 / 切分类时，中间那段「正在加载…」
     * 曾经把焦点临时交给页头的控件，落地时再看「页头有没有焦点」就会把焦点又交回页头——
     * 表现出来就是「进榜单、切榜单之后焦点停在榜单按钮上」。因此改成显式的意图：
     * 换了一份列表（换榜单 / 换分类 / 从首页剧架进榜单）就置 true，落地时落到第一张卡并清掉。
     */
    private var focusFirstCardOnLanding=false
    /** 剧架 key → 榜单（[HomeLayout.board]）：首页四个「热播…」栏目与排行榜页共用同一个映射。 */
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
    /** 排行榜页的四个榜单切换芯片（漫剧 / 短剧 / 真人 / AI）。 */
    private val boardButtons=mutableMapOf<RankBoard,View>()
    /**
     * 分类页当前选中的小类（`CategoryCatalog` 里的路径，例如 `comic-drama/fantasy`）。
     * 默认是第一组的「全部」；选择记在本机，下次进分类页还是这一条。
     */
    private var categoryPath=CategoryCatalog.defaultTag.path
    /**
     * 分类页的大类与小类芯片。
     *
     * 大类一行（漫剧 / 真人剧 / AI剧）、小类一行（全部 + 该大类下的小类）。
     * 两行都是互斥选择，点了就重新取第 1 页（分类页是「往下接」的长列表）。
     */
    private val categoryGroupButtons=mutableMapOf<CategoryGroup,View>()
    private val categoryTagButtons=mutableMapOf<String,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var synopsisExpanded=false
    /** 详情页是否正在看「相关剧集」：展开时海报缩到与「开始观看」底边平齐，下方的选集换成相关剧集。 */
    private var relatedOpen=false
    /**
     * 相关剧集的取数状态（后台预取，用户无感）。
     *
     * 流程：**详情一取回来就先渲染内容**，同时把相关剧集丢到后台去查（[prefetchRelated]）。
     * 用户点「相关剧集」时不再发新请求——取好了直接铺，还在途中就等它落地
     * （那点时间差通常察觉不到）。相关剧集要发几次搜索请求，但整页的可见时间不受影响，
     * 实测详情本身 0.5s 左右，相关剧集 2~4s 在后台跑完。
     */
    private var relatedWorks: RelatedWorks?=null
    private var relatedLoading=false
    private var relatedFailed=false
    /** 相关剧集这条请求自己的令牌与句柄：它不能走 [work]（那条路会被本页重排取消掉）。 */
    private val relatedToken=java.util.concurrent.atomic.AtomicLong(0)
    private var relatedJob: java.util.concurrent.Future<*>?=null
    /** 正在预取 / 已预取的是哪一部剧，避免同一部剧重复发请求。 */
    private var relatedFor=""
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
    private fun button(label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,pad: Int?=null,onClick: ()->Unit): TvButton =
        TvButton(this,label,size,tone,action=onClick,pad=pad).apply { if(selected) setSelectedState(true) }
    /**
     * 往容器里放一个按钮，并保证间距只有一个来源：横向容器加左边距、纵向容器加上边距，
     * 第一个子控件不留边距。因此不会再有「两个按钮贴在一起」或「行尾多出 8dp 把整行推偏」。
     */
    /**
     * 行内按钮。三种宽度策略，按需选一种：
     *
     * - 默认：按文字宽度自适应（[LinearLayout.LayoutParams.WRAP_CONTENT]）；
     * - [width]：固定像素宽度（很少用，像素要自己算，容易算错）；
     * - [weight]：**等分父容器的剩余宽度**（简介页那一排入口用的就是它）。
     *   宽度由父容器实测后分配，因此不用去猜「这一行到底有多少可用宽度」——
     *   简介页的按钮行在海报右侧的信息列里，不是整屏宽，按整屏算就会溢出。
     */
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,tone: ButtonTone=ButtonTone.SECONDARY,size: ButtonSize=ButtonSize.NORMAL,gap: Int=TvStyle.GAP_INLINE,width: Int?=null,weight: Float?=null,pad: Int?=null,onClick: ()->Unit): TvButton {
        val view=button(label,selected,tone,size,pad=pad,onClick=onClick)
        if(weight!=null) {
            // 等分时清掉最小宽度：`TvButton` 默认有 92dp 的最小宽度，等分到的宽度比它小时
            // 控件会按最小宽度量出来，四个加起来就顶破父容器（屏幕上就是「超出去了」）。
            // 这里让等分说了算，文字放不下由 ellipsize 收尾。
            view.minimumWidth=0
        }
        val params=lp().apply {
            if(weight!=null) { this.width=0; this.weight=weight } else this.width=width ?: LinearLayout.LayoutParams.WRAP_CONTENT
        }
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
        appUpdater=AppUpdater(this) {
            // 「空闲」= 在前台、不在播放界面、没有模态框。播放中与弹窗里绝不打扰。
            foreground && screen!="player" && !tvTools.showing
        }
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
            if(!isDestroyed && foreground) { applySessionToken(); applySavedCategory(); showCatalog(load=true); favoriteMonitor.check() }
        }
    }
    /**
     * 搜索登录态（可选）跟着本机设置走：留空时搜索退回官网搜索，填上后直接使用 App 搜索接口。
     * 设置里改完立刻作用于后面的搜索请求。
     */
    private fun applySessionToken() { repository.sessionToken=library.searchToken.ifBlank { null } }
    /**
     * 恢复上次选中的分类。存的是 `CategoryCatalog` 里的路径，认不出（站点改了标签、
     * 或从没选过）就退回默认的「漫剧 · 全部」——分类页永远有一个合法的选中项。
     */
    private fun applySavedCategory() {
        val saved=library.categoryPath
        categoryPath=if(saved.isNotBlank() && CategoryCatalog.groupOf(saved)!=null) saved else CategoryCatalog.defaultTag.path
    }
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
        nav.clear(); boardButtons.clear()
        categoryGroupButtons.clear(); categoryTagButtons.clear()
        searchInput=null; searchButton=null; searchActions.clear()
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
        if(next!=TAB_RANK && tab==TAB_RANK) boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
        if(next!=TAB_CATEGORY && tab==TAB_CATEGORY) {
            (categoryGroupButtons.values + categoryTagButtons.values).firstOrNull { it.hasFocus() }?.clearFocus()
        }
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        // 首页的四个剧架就在内存里（[homeFeed]），切回来不需要重新打四次网络；
        // 想要最新的一份按顶部圆形「刷新」。搜索页与榜单页仍然是「没内容才去取」。
        val needsLoad=when(next) {
            TAB_HOME -> homeFeed.isEmpty()
            TAB_RANK, TAB_CATEGORY -> catalog.isEmpty()
            TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
            else -> false
        }
        // 切到「内容页」时（首页四栏、榜单、分类、搜索），焦点落到内容第一张卡，
        // 不停在顶部那一排圆形入口上——用户是来看内容的。
        if(needsLoad) focusFirstCardOnLanding=true
        showCatalog(load=needsLoad)
    }
    /**
     * 切换排行榜页看的榜单。首页四个剧架的「查看全部」走的也是这里——
     * 它们与榜单页读的是同一份数据（[ContentRepository.rank]），只是入口不同。
     *
     * [fromHome] 为真表示这是从首页剧架进来的：顺手把顶部标签切到排行榜，
     * 让用户看到的就是榜单页本身（页面名、来源行、底部加载都是同一套）。
     */
    private fun switchRankBoard(board: RankBoard,fromHome: Boolean=false) {
        if(rankBoard==board && !fromHome && tab==TAB_RANK) return
        boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
        rankBoard=board
        boardButtons.forEach { (option,chip) -> (chip as? ToggleChip)?.setActive(option==board) }
        // 榜单页的内容与页码都跟着榜单走：换榜就是换一份列表。
        tabState.remove(TAB_RANK); tabFocus.remove(TAB_RANK)
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
        if(fromHome) tab=TAB_RANK
        // 换榜单就是换一份列表：落地后焦点落到第一张卡（不管是从首页剧架进来的，
        // 还是在榜单页里点了另一枚榜单芯片）。用户按了「看这个榜」，落点就该是榜单内容本身。
        focusFirstCardOnLanding=true
        showCatalog(load=true)
    }
    /**
     * 切换分类页的大类：选这个大类下的「全部」，并重新取第 1 页。
     *
     * 大类与它的小类是一套联动：换大类时小类那一行整行换掉（见 `showCatalog` 里的分类页分支），
     * 因此这里只管改选择与页码。
     */
    private fun switchCategoryGroup(group: CategoryGroup) {
        val tag=group.tags.firstOrNull() ?: return
        if(tag.path==categoryPath && tab==TAB_CATEGORY) return
        selectCategory(tag.path)
    }
    /** 切换分类页的小类（同一个大类内）。 */
    private fun switchCategoryTag(tag: CategoryTag) {
        if(tag.path==categoryPath && tab==TAB_CATEGORY) return
        selectCategory(tag.path)
    }
    /**
     * 选中一个分类并重新取第 1 页。
     *
     * 分类页是「往下接」的长列表：换分类等于换一份列表，因此页码与滚动位置都从头开始，
     * 焦点在内容落地后落到第一张卡（与换榜单同一条规则）；
     * 芯片的选中态由重建页面时按 [categoryPath] 重新算。
     */
    private fun selectCategory(path: String) {
        categoryPath=path
        library.categoryPath=path
        tabState.remove(TAB_CATEGORY); tabFocus.remove(TAB_CATEGORY)
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
        if(tab!=TAB_CATEGORY) tab=TAB_CATEGORY
        // 换分类就是换一份列表：与换榜单同一条规则，落地后焦点落到第一张卡。
        focusFirstCardOnLanding=true
        showCatalog(load=true)
    }
    /** 本机记录集合页（收藏 / 历史 / 稍后观看）；远端列表只有榜单页一种，不再有「热门集合页」。 */
    private fun showCollection(kind: String) {
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    /** 集合页标题（本机记录页专用）。 */
    private fun collectionName(kind: String)=when(kind) {
        "favorites" -> "我的收藏"; "history" -> "历史记录"; "later" -> "稍后观看"; else -> "本机记录"
    }
    /**
     * 顶部导航栏左侧显示的页面名。
     *
     * 首页与搜索 / 设置页显示品牌「红果TV」；二级界面（收藏 / 历史 / 稍后观看）显示自己的名字，
     * 排行榜页显示**当前榜单的名字**——这些页面原来的标题行因此可以整行去掉。
     */
    private fun pageTitle(): String = when {
        collection!=null -> collectionName(collection.orEmpty())
        tab==TAB_RANK -> "热播榜"
        tab==TAB_CATEGORY -> "分类"
        tab==TAB_SETTINGS -> "设置"
        else -> "红果TV"
    }
    /**
     * 当前标签对应的顶部图标入口。`nav` 是四个页面入口（搜索 / 分类 / 榜单 / 设置），
     * 顺序与 [TAB_SEARCH]…[TAB_SETTINGS] 一一对应。
     *
     * 首页（[TAB_HOME]）没有自己的入口：品牌「红果TV」是标识而不是按钮，不参与焦点链，
     * 这里返回 null——调用方跳过聚焦即可（回主界面走返回键）。
     */
    private fun navEntry(index: Int = tab): View? = nav.getOrNull(index-1)

    /** 顶部这一排图标入口里当前真正显示的那些。 */

    private fun navIcons(): List<View> = nav.filter { it.isShown }
    /**
     * 页头里「把焦点交回页头」时该落到哪一枚控件。
     *
     * 榜单页是四个榜单芯片、分类页是大类芯片（两页都有自己的切换行），其余页面退到顶部圆形入口。
     * 首页返回 null：品牌不是按钮，调用方改落到第一栏第一张卡。
     */
    private fun firstHeadControl(): View? = when(tab) {
        TAB_RANK -> boardButtons[rankBoard]
        TAB_CATEGORY -> categoryGroupButtons.values.firstOrNull { it.isShown }
        // 搜索页的内容入口就是输入框：焦点落在它上面，而不是顶部那排圆形入口。
        TAB_SEARCH -> searchInput
        TAB_HOME -> null
        else -> navEntry()
    }
    /** 榜单来源说明：四个榜单都来自官网自己的热播榜，带名次与热度，不再有「按播放量排序」的替代品。 */
    private fun rankSource()=rankBoard.source
    /**
     * 四个榜单的切换芯片是否放进页头里（榜单名那一行的右侧）。
     * 屏幕窄或字号放大时退回榜单名下方单独一行。
     */
    private fun inlineBoardFilters()=tab==TAB_RANK && resources.configuration.fontScale<=1.2f && widthDp()>=900
    /**
     * 远端长列表：排行榜页的四个榜单，以及搜索结果。
     * 这些列表都是「往下接」而不是「翻页」：已显示的剧集不动，新的一页接在末尾。
     */
    private fun appendCatalog()=collection==null && (tab==TAB_RANK || tab==TAB_CATEGORY || tab==TAB_SEARCH)
    /**
     * 搜索结果不再给「展示更多」按钮：滚到列表底部（或焦点走到最后一行）就自动往下取，
     * 底部只留一行不可聚焦的进度文字。
     */
    private fun autoAppendCatalog()=tab==TAB_SEARCH && collection==null
    /** 完全来自本机记录的列表：我的收藏、历史记录、稍后观看这些集合页，仍然按页切换。 */
    private fun localCatalog()=collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "favorites" -> library.favorites()
        "history" -> library.history().map { it.series }
        "later" -> library.later()
        else -> library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        // 不取远端内容的这一次重建没有「内容落地」这一步，意图要清掉：
        // 否则它会留到下一次无关的落地，把焦点莫名其妙地推到第一张卡上。
        if(!load) focusFirstCardOnLanding=false
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // 榜单页与搜索结果从第 2 页起是「向下追加」：保留已经显示的剧集，只把新的一页接在后面。
        val appending=load && appendCatalog() && page>1 && catalog.isNotEmpty()
        // 首页的四个剧架不是「一页列表」：它们来自四个榜单各自的第 1 页，
        // 因此首屏先把上次缓存铺上（四个榜单各一份），后台再更新。
        val home=tab==TAB_HOME && collection==null && page==1
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            if(home) {
                val cached=RankBoard.entries.mapNotNull { board -> library.cachedHome(board)?.let { board to it } }.toMap()
                homeFeed=LinkedHashMap(cached)
                homeFromCache=cached.isNotEmpty()
                catalog=emptyList(); hasMore=false; ranking=null
            } else if(!appending) { catalog=emptyList(); hasMore=false; ranking=null }
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        // 顶部导航栏：左侧是页面名（首页是品牌「红果TV」，二级界面与榜单页显示自己的名字），
        // 右侧是这一页自己的动作与 4 个圆形图标入口（刷新 / 搜索 / 排行榜 / 设置）。
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
        // 顶部只有四个圆形入口，顺序固定：搜索 → 分类 → 榜单 → 设置。
        // 「刷新」按钮整个去掉了：首页、榜单、分类、搜索都是「进页面 / 滚到顶 / 换榜单 / 换分类」时自己重取，
        // 留着它只是多一个入口（职责已经被这些动作覆盖）。
        listOf(
            com.hgtv.star.R.drawable.ic_toolbar_search to "搜索",
            com.hgtv.star.R.drawable.ic_toolbar_category to "分类",
            com.hgtv.star.R.drawable.ic_toolbar_rank to "榜单",
            com.hgtv.star.R.drawable.ic_toolbar_settings to "设置"
        ).forEachIndexed { index,entry ->
            val item=IconButton(this,entry.first,entry.second) { switchTab(index+1) }
            item.setActive(index+1==tab)
            nav+=item
            gapInto(top,item,lp(-2,-2),TvStyle.GAP_BLOCK)
        }
        container.addView(top,lp(-1,dp(TvStyle.TOOLBAR_HEIGHT)))
        registerTopAreaRow(top)
        // 页面内提示行：应用里不弹系统 Toast，需要「刚刚发生了什么」这类反馈时显示这一行淡字。
        // 它不参与焦点、不盖画面，几秒后自己消失；页面重建时按 [catalogHint] 复原。
        val hintView=text("",14f,TvStyle.textDim).apply {
            setPadding(pageBleed(),dp(4),pageBleed(),dp(2))
            visibility=if(catalogHint==null) View.GONE else View.VISIBLE
            text=catalogHint.orEmpty()
        }
        catalogHintView=hintView
        container.addView(hintView,lp(-1,-2))
        if(tab==TAB_SETTINGS) {
            val scroll=ScrollView(this).focusBleed(); val body=column(); scroll.addView(body); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
            listScrollY=scroll.scrollY
            scroll.setOnScrollChangeListener { _,_,y,_,_ -> onListScroll(y) }
            settings(body); if(focusNav) navEntry()?.requestFocus(); return
        }
        // 榜单页的四个榜单切换芯片：放进榜单名那一行（屏幕窄 / 字号大时退回下面单独一行）。
        // 首页不再有「漫剧 / 短剧」开关：四个榜单本身就是首页的四栏，分类由栏目标题表达。
        if(tab==TAB_RANK) {
            val boards=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(4)) }
            val boardChips=mutableListOf<View>()
            RankBoard.entries.forEach { board ->
                // 四个按钮分别是 漫剧 / 短剧 / 真人 / AI；页面主标题固定写「热播榜」（见 [pageTitle]）。
                val chip=ToggleChip(this,board.label,rankBoard==board) { switchRankBoard(board) }
                navEntry()?.let { chip.nextFocusUpId=it.id }
                boardButtons[board]=chip
                boardChips+=chip
                gapInto(boards,chip,lp(-2,-2))
            }
            // 行内左右邻居显式串起来（理由同分类页的小类芯片）：走到两端停在原地。
            boardChips.forEachIndexed { index,chip ->
                chip.nextFocusLeftId=boardChips[(index-1).coerceAtLeast(0)].id
                chip.nextFocusRightId=boardChips[(index+1).coerceAtMost(boardChips.lastIndex)].id
            }
            if(inlineBoardFilters()) {
                boards.setPadding(0,0,0,0); filterSlot.addView(boards)
            } else {
                container.addView(boards)
            }
            navIcons().forEach { it.nextFocusDownId=boardButtons.getValue(rankBoard).id }
        }
        if(tab==TAB_SEARCH) {
            val searchRow=row().apply { setPadding(pageBleed(),dp(2),pageBleed(),dp(2)) }
            // 搜索框与同一行的按钮共用一套尺寸令牌：48dp 底板、胶囊圆角、左右 28dp 内边距，
            // 因此输入框和「搜索 / 历史 / 手机输入」在一条基线上；差别只在「凹槽 vs 凸起」这一层语义。
            val searchBand=dp(TvStyle.SOFT_BAND)
            val input=EditText(this).apply {
                id=View.generateViewId(); hint="输入剧名或关键词"; setText(query)
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
                                ?: navEntry()?.takeIf { it.isShown }
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
            input.nextFocusUpId=navEntry()?.id ?: input.id; searchButton?.nextFocusUpId=input.nextFocusUpId
            lateinit var recent: TvButton
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }
            recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TvButton
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }
            phone.nextFocusUpId=input.nextFocusUpId
            searchActions.addAll(listOf(recent,phone))
            input.setOnEditorActionListener { _,_,_-> search(); true }
            container.addView(searchRow); registerTopAreaRow(searchRow)
            // 进搜索页、还没有关键词时焦点直接落在输入框上：这一页要「等用户输入」，
            // 焦点停在顶部圆形入口上等于让用户先按一下右键才能打字。
            // 有关键词（刚搜完切回这一页）时不抢焦点：那时焦点应该在结果或它原来的位置上。
            if(query.isBlank() && !load && !focusNav) searchInput?.requestFocus()
        } else if(tab==TAB_CATEGORY) {
            // 分类页的两行芯片：第一行三个大类，第二行是该大类下面的小类（第一个固定「全部」）。
            // 页面主标题写「分类」，当前选中的小类写在下面这一行的右侧（一行淡字）。
            val group=CategoryCatalog.groupOf(categoryPath) ?: CategoryCatalog.groups.first()
            val groupRow=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(2)) }
            val groupChips=mutableListOf<View>()
            CategoryCatalog.groups.forEach { option ->
                val chip=ToggleChip(this,option.label,option==group) { switchCategoryGroup(option) }
                navEntry()?.let { chip.nextFocusUpId=it.id }
                categoryGroupButtons[option]=chip
                groupChips+=chip
                gapInto(groupRow,chip,lp(-2,-2))
            }
            groupChips.forEachIndexed { index,chip ->
                chip.nextFocusLeftId=groupChips[(index-1).coerceAtLeast(0)].id
                chip.nextFocusRightId=groupChips[(index+1).coerceAtMost(groupChips.lastIndex)].id
            }
            gapInto(groupRow,text(CategoryCatalog.describe(categoryPath),12f,muted),lp(-2,-2),TvStyle.GAP_BLOCK)
            container.addView(groupRow); registerTopAreaRow(groupRow)
            // 小类那一行的间距比大类收一档：芯片本身已经小一档，间距不变会显得松散。
            val tagRow=row().apply { setPadding(pageBleed(),0,pageBleed(),dp(2)) }
            val tagChips=mutableListOf<View>()
            group.tags.forEach { tag ->
                // 小类（二级分类）整体小一档：高度 40dp、字号 15sp、左右内边距 18dp（大类是 48/16/28）。
                val chip=ToggleChip(this,tag.label,tag.path==categoryPath,ButtonSize.COMPACT) { switchCategoryTag(tag) }
                chip.nextFocusUpId=categoryGroupButtons.getValue(group).id
                categoryTagButtons[tag.path]=chip
                tagChips+=chip
                gapInto(tagRow,chip,lp(-2,-2))
            }
            // 小类芯片的左右邻居显式串起来：行内横向滚动容器自己不会按「芯片宽度」算焦点，
            // 不写死邻接关系时遥控器右移会跳到行外的控件上。走到两端停在原地。
            tagChips.forEachIndexed { index,chip ->
                chip.nextFocusLeftId=tagChips[(index-1).coerceAtLeast(0)].id
                chip.nextFocusRightId=tagChips[(index+1).coerceAtMost(tagChips.lastIndex)].id
            }
            // 小类多的目录（真人剧 24 个）一行放不下：整行横向可滚，左右键走到行末继续推。
            val tagScroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false
                clipChildren=false; clipToPadding=false
            }
            tagScroll.addView(tagRow)
            container.addView(tagScroll); registerTopAreaRow(tagScroll)
            navIcons().forEach { it.nextFocusDownId=categoryGroupButtons.getValue(group).id }
        } else if(tab==TAB_RANK) {
            // 榜单名已经进了导航栏，这一行只剩来源 / 更新时间；
            // 「刷新榜单」按钮去掉：进页面、切榜单、滚到顶都会重新取，不需要单独一个入口。
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
            if(appending) catalogGrid(body,focusNav,focusType,loadingMore=true)
            else if(home) catalogGrid(body,focusNav,focusType,loading=true)
            else {
                message(body,"正在加载…")
                // 加载期间焦点必须有一个**确定的落点**：整页重建之后，刚按下的那枚芯片已经离树，
                // 不管的话系统会把焦点交给新布局里第一个可聚焦控件（页头那排入口 / 芯片），
                // 落地时看起来就是「焦点停在榜单按钮上」。这里显式落在页头控件上，
                // 内容一到就由 [focusFirstCardOnLanding] 把焦点交给第一张卡。
                if(focusType || focusFirstCardOnLanding) firstHeadControl()?.requestFocus()
            }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestBoard=rankBoard
            val requestCategory=categoryPath
            work({
                // 首页一次取四个榜单（并发，见 homeFeed）；榜单页只取当前这一个；
                // 分类页取当前小类的第 N 页；搜索页走搜索。四者都用同一个「请求 key → 页面」的形状，
                // 因此下面的落地逻辑（追加 / 换页 / 报错）只有一份。
                when(requestTab) {
                    TAB_HOME -> repository.homeFeed()
                    TAB_RANK -> mapOf(requestBoard to repository.rank(requestPage,requestBoard))
                    TAB_CATEGORY -> mapOf(requestBoard to repository.category(requestCategory,requestPage))
                    else -> mapOf(requestBoard to repository.search(requestQuery,requestPage))
                }
            }, { result ->
                if(requestTab==TAB_HOME) {
                    // 四个剧架一起换。首屏可能是上次缓存（[homeFromCache]），
                    // 新内容到了**就地更新剧架**（[HomeScreen.updateShelves] 会保留当前卡片与滚动位置），
                    // 而不是整页重画——用户正在看的那一栏不会跳。
                    val first=homeFeed.isEmpty()
                    homeFeed=LinkedHashMap(result)
                    result.forEach { (board,page) -> library.cacheHome(board,page.items,page.hasMore) }
                    val keepNav=nav.any { it.hasFocus() }
                    val homeView=homeScreen
                    // 首屏（还没有任何剧架）整页铺；已经有剧架就地更新——`updateShelves` 默认
                    // 「焦点本来在剧架上就把焦点按回原卡片」，因此用户正在看的那一栏不会跳、也不会丢焦点。
                    focusFirstCardOnLanding=false
                    if(first || homeView==null) {
                        // 首页四栏：焦点交给第一栏第一张卡（keepNav 为真时留在顶部入口）。
                        body.removeAllViews(); catalogGrid(body,keepNav || focusNav)
                    } else homeView.updateShelves(homeShelves())
                    return@work
                }
                val page=result[requestBoard] ?: CatalogPage(emptyList(),false)
                if(appending) {
                    // 追加：只接住还没出现过的剧集，并把这一页的名次与热度并进已有榜单。
                    val added=page.items.filterNot { item -> catalog.any { it.id==item.id } }
                    val wanted=moreFocus; moreFocus=null
                    catalog=catalog+added; ranking=mergeRanking(ranking,page.ranking); hasMore=page.hasMore
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                    // 栅格还活着（空页那次重建之后又追加）就只往下接，不整页重画。
                    if(gridList!=null && catalogBody===body) appendGridItems(added,wanted)
                    else { body.removeAllViews(); catalogGrid(body,false) }
                } else {
                    catalog=page.items; ranking=page.ranking; hasMore=page.hasMore
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { rankSource() } ?: rankSource()
                    // 焦点落点按**显式意图**决定（见 [focusFirstCardOnLanding]）：换了一份列表
                    // （换榜单 / 换分类 / 从首页进榜单）就不要留在页头——`focusNav` 置 false 之后，
                    // catalogGrid 末尾的兜底会把焦点交给第一张卡（`catalogFocus` 在换列表时已清空）。
                    // 其余情况保留用户当前在页头的位置（切标签回来看同一页等）。
                    val landFirst=focusFirstCardOnLanding; focusFirstCardOnLanding=false
                    val keepNav=!landFirst && nav.any { it.hasFocus() }
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav)
                }
            }, { problem ->
                if(requestTab==TAB_HOME) {
                    if(homeFeed.isNotEmpty()) { showHint("热门暂时取不到，仍在显示上次内容"); return@work }
                    // 一栏都没有：只剩提示行会是一个空页面，这里给出错误说明与「重试」。
                    body.removeAllViews(); error(body,problem) { showCatalog(true) }
                    return@work
                }
                if(appending) {
                    // 追加失败不动已经显示的剧集，页码退回待加载的那一页，底部按钮可以直接重试。
                    page=requestPage-1; moreFocus=LOAD_MORE
                    rankSubtitle?.text="加载更多失败 · 当前显示 ${catalog.size} 部"
                    body.removeAllViews(); catalogGrid(body,false)
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null
                rankSubtitle?.text=if(tab==TAB_SEARCH) "搜索失败" else "榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    showHint("搜索结果已过期，已回到第 1 页刷新")
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
        if(tab==TAB_HOME && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
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
        // 向下追加的长列表（榜单 / 分类 / 搜索）位置不跟着页码走，因此 key 里页码固定为 1；
        // 分类页还要带上当前小类，否则换分类会沿用上一条的滚动位置。
        catalogPageKey=buildString {
            append(tab); append(':'); append(collection.orEmpty()); append(':')
            append(if(appendCatalog()) 1 else page); append(':')
            if(tab==TAB_CATEGORY) append(categoryPath).append(':')
            append(query)
        }
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
            tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY -> catalog.filterNot { it.id in library.hidden() }
            appendCatalog() -> catalog
            else -> catalog.take(30)
        }
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when {
                tab==TAB_SEARCH -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关剧集，换个关键词试试"
                tab==TAB_CATEGORY -> "${CategoryCatalog.describe(categoryPath)}暂时没有内容"
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
            // 空页没有卡片可落，只能退回页头控件——绝不能不管：焦点留在刚被摘掉的控件上，
            // 系统会把它交给新布局里第一个可聚焦控件，位置不确定。
            focusFirstCardOnLanding=false
            (firstHeadControl() ?: navEntry())?.requestFocus()
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
            // 榜单页与搜索结果去掉「上一页 / 下一页 / 下一批」：只向下追加，底部留一个向下的按钮按需加载下一页。
            gridPaging=paging
            fillPagingRow(paging)
            list.addView(paging,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND+TvStyle.GAP_TIGHT)))
            next=moreButton?.takeIf { it.isFocusable }
        } else if(tab==TAB_SEARCH || localCatalog()) {
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
        if(focusType) firstHeadControl()?.requestFocus() else if(focusNav) navEntry()?.requestFocus()
        else if(loadingMore) moreButton?.requestFocus()
        else if(appended!=null) appended.requestFocus()
        // 记住过焦点的卡片（同一页重建）优先；其次落到第一张卡——
        // 「换榜单 / 换分类 / 从首页进榜单」这类换列表的动作走的就是这一条。
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
     *
     * 静默预取不打扰用户（还是「展示更多 ↓」），只有用户自己按出来的加载才显示「正在加载更多…」。
     * 分类页与榜单页都有这个按钮（它们不是自动追加）；搜索结果没有按钮，滚到底就自动接。
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
        val ticket=generation; val board=rankBoard; val category=categoryPath
        val requestTab=tab; val scope=collection; val requestQuery=query
        gridPrefetchJob=io.submit {
            // 榜单、分类、搜索都走这条「往下接」的路：第 2 页起接着取同一份列表，
            // 页头与已显示的剧集都不动（搜索结果按同一关键词继续取）。
            val result=runCatching {
                when {
                    requestTab==TAB_SEARCH && scope==null -> repository.search(requestQuery,next)
                    requestTab==TAB_CATEGORY && scope==null -> repository.category(category,next)
                    else -> repository.rank(next,board)
                }
            }.getOrNull()
            main.post {
                if(isDestroyed || generation!=ticket) return@post
                if(moreInFlightPage==next) { moreInFlight=false; moreVisible=false }
                val wantsFocus=moreFocusWanted; moreFocusWanted=false
                if(result==null) {
                    if(wantsFocus) showHint("加载更多失败，请检查网络后重试")
                    moreFailed=true; updateFooter()
                    return@post
                }
                // 只有「还是原来那一页」才接得上；中途切了标签 / 榜单 / 集合，结果直接丢掉。
                if(scope==collection && requestTab==tab && requestQuery==query && rankBoard==board &&
                    (requestTab!=TAB_CATEGORY || categoryPath==category) && screen=="catalog" && gridList!=null)
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
        val position=if(tab==TAB_RANK) ranking?.positions?.get(item.id) else null
        val rank=position?.rank
        val progress=if(collection=="history") library.progress(item.id) else null
        val statusText=when {
            collection=="favorites" -> library.favoriteLabel(item.id)
            // 榜单偶尔整页没有热度值（服务端 SSR 抽风）：退回剧集自己的角标，不再整屏都是「热度暂无」。
            tab==TAB_RANK -> position?.heat?.takeIf { it.isNotBlank() } ?: item.badge.ifBlank { rankBoard.label }
            progress!=null -> if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
            else -> item.badge
        }
        val badgeText=when {
            tab==TAB_RANK -> rank?.let { "第 $it 名" } ?: ""
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
        // 栅格第一行按上键回到它正上方那一行：搜索页是输入框、分类页是当前小类芯片、
        // 榜单页是当前榜单芯片、本机记录页是它自己的动作按钮，其余页面退到顶部圆形入口。
        val headUp=searchInput?.id
            ?: categoryTagButtons[categoryPath]?.id
            ?: boardButtons[rankBoard]?.id
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
        navIcons().forEach { it.nextFocusDownId=first }
        historyManage?.nextFocusDownId=first
        favoriteCheck?.nextFocusDownId=first
        boardButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: first }
        categoryGroupButtons.values.forEach { it.nextFocusDownId=categoryTagButtons[categoryPath]?.id ?: first }
        categoryTagButtons.values.forEach { it.nextFocusDownId=first }
        searchInput?.nextFocusDownId=first
        val shortcut=rows.first().getChildAt(minOf(4,rows.first().childCount-1)).id
        searchButton?.nextFocusDownId=shortcut
        searchActions.forEach { it.nextFocusDownId=shortcut }
        // 列表最后一行按下键落在底部「展示更多」上，再按一次（确认或下键）就追加下一页。
        moreButton?.nextFocusUpId=rows.last().getChildAt(0).id
    }
    /**
     * 已显示进度。榜单自带总页数，所以能给「N / M 部」；
     * 搜索结果只知道自己追加到第几页，就只报已显示数量，加载完再明确说「全部」。
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
        // 首页没有自己的顶部入口（品牌是标识），剧卡按上键回到第一枚圆形入口。
        val top=navEntry() ?: nav.firstOrNull() ?: root
        val home=HomeScreen(this,top,pageInset(),::loadCover,
            { item,resume -> openDetail(item,resume) },{ item,anchor,shelf -> quickActions(item,anchor,shelf) },{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        // 首页剧架的滚动也走同一套页头收展：向下浏览时把品牌 / 顶部入口收起来。
        listScrollY=0; home.onScroll={ onListScroll(it) }
        // 首页底部不再挂「更多热门」：四个「热播…」剧架的行末「查看全部」直接进对应的榜单页，
        // 底部再放一个全局入口是重复的。
        // 首页没有切换芯片：焦点不在顶部入口上时直接落到第一栏第一张卡。
        home.render(homeShelves(),catalogFocus,!focusNav,homePosition)
        if(loading && !homeFromCache) showHint("正在更新热门…")
        navIcons().forEach { it.nextFocusDownId=home.firstId() }
        // 首页没有自己的顶部入口（品牌是标识、不参与焦点链），所以要「把焦点留在页头」时
        // 只能退到第一栏第一张卡：焦点始终有落点，不会凭空消失。
        if(focusNav) (navEntry() ?: home.firstCard())?.requestFocus()
        if(homeFeed.values.any { it.items.isNotEmpty() } && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HGTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HGTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    /**
     * 首页七个栏目，顺序固定：
     *
     * 1. 历史记录 —— 本机观看进度，按最近观看排序；未看完的条目确认后直接续播。
     * 2. 我的收藏 —— 本机收藏，带「更新至第几集」的状态行。
     * 3. 稍后观看 —— 本机稍后观看清单。
     * 4. 热播漫剧 / 5. 热播短剧 / 6. 热播真人 / 7. 热播AI —— 官网的四个热播榜，
     *    与排行榜页的四个榜单**是同一份数据**：行末「查看全部」直接进对应的榜单页。
     *
     * 每栏最多十部；本机没有记录的三栏、远端取不到的榜单都不占位（[HomeScreen.render] 过滤空架子）。
     */
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val history=library.history(); val favorites=library.favorites()
        val later=library.later()
        // 栏目的顺序与名字只有一个来源（[HomeLayout.columns]，有单元测试钉住）；
        // 这里只负责按 key 往每一栏里填内容，某一栏没有内容就返回 null（界面不渲染空架子）。
        val shelves=mapOf<String,HomeScreen.Shelf?>(
            "history" to HomeScreen.Shelf("history",HomeLayout.title("history"),history.take(10).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "整剧已看完" else formatTime(p.position)}",!p.completed,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null,
                if(p.duration>0) "${formatTime(p.position)} / ${formatTime(p.duration)}" else "第 ${p.episodeIndex+1} 集") },
                if(history.size>10) { { showCollection("history") } } else null),
            "favorites" to HomeScreen.Shelf("favorites",HomeLayout.title("favorites"),favorites.take(10).map { HomeScreen.Entry(it,library.favoriteLabel(it.id)) },
                if(favorites.size>10) { { showCollection("favorites") } } else null),
            "later" to HomeScreen.Shelf("later",HomeLayout.title("later"),later.take(10).map { HomeScreen.Entry(it,"稍后观看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null)
        ) + RankBoard.entries.associate { board -> "rank-${board.storedValue}" to rankShelf(board) }
        return HomeLayout.columns.mapNotNull { column -> shelves[column.key] }
    }
    /**
     * 首页的一个「热播…」栏目：内容是该榜单第 1 页的前十部（已过滤本机隐藏的剧集）。
     *
     * 栏目名与榜单名不一样（见 [RankBoard]）：栏目按用户认得的片库分类写（热播短剧…），
     * 榜单页里写的才是官网自己的榜名（红果热播榜）。
     */
    private fun rankShelf(board: RankBoard): HomeScreen.Shelf? {
        val page=homeFeed[board]
        val items=page?.items.orEmpty().filterNot { it.id in library.hidden() }
        if(items.isEmpty()) return null
        return HomeScreen.Shelf("rank-${board.storedValue}",board.homeTitle,
            items.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" },time=it.badge) },
            // 行末「查看全部」直接进这一栏对应的榜单页——榜单与它是同一份数据，不是另做一套列表。
            { switchRankBoard(board,fromHome=true) })
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); return }
        // 首页剧架与排行榜页都直接按「本机隐藏」过滤，所以在这两处隐藏一部剧要就地重画列表
        // （本机记录页里的条目本身就不是热播内容，隐藏那一项在那里不出现）。
        if(removed && (localCatalog() || tab==TAB_HOME || tab==TAB_RANK)) {
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
    /**
     * 剧卡菜单（长按确认 / 遥控器菜单键）。
     *
     * 有哪些项、什么顺序、文案怎么随状态变，都由 `core` 的 [CardMenu.items] 算出来——
     * 它不依赖 Android，因此「历史上下文里必须有删除记录、本机记录页不出现『不在热播榜中显示』」
     * 这几条规则被单元测试锁住，中间插一项也不会让后面的分支错位。
     *
     * [shelf] 是卡片所在剧架的 key：首页「历史记录」剧架（`shelf=="history"`）与历史集合页
     * （`collection=="history"`）是同一个上下文，两条路径都能就地删掉这一条本机观看记录。
     *
     * 派发按语义动作走（[CardAction]），不按下标——文案随状态变（收藏 / 取消收藏），
     * 动作不变，因此「显示什么」与「执行什么」不会再错位。
     */
    private fun quickActions(series: Series,anchor: View,shelf: String?=null) {
        val inHistory=shelf=="history" || collection=="history"
        val items=CardMenu.items(
            hasProgress=library.progress(series.id)!=null,
            favorite=library.favorite(series.id),
            queued=library.queued(series.id),
            inHistory=inHistory,
            // 「不在热播榜中显示」只对**热播榜**有意义：首页四个热播剧架与排行榜页保留，
            // 本机记录页（收藏 / 历史 / 稍后观看）与详情页的相关剧集都不出现这一项。
            hotList=screen=="catalog" && collection==null && (tab==TAB_RANK || (tab==TAB_HOME && shelf?.startsWith("rank-")==true)),
            historyCollection=collection=="history")
        tvTools.choose(series.title,items.map { it.label },anchor,{ index ->
            when(items[index].action) {
                CardAction.RESUME -> openDetail(series,true)
                CardAction.FAVORITE -> {
                    val added=library.toggle(series)
                    refreshLibraryCards(series.id,!added && collection=="favorites")
                    if(added) favoriteMonitor.check()
                }
                CardAction.LATER -> {
                    val added=library.toggleLater(series)
                    refreshLibraryCards(series.id,!added && collection=="later")
                }
                CardAction.DETAIL -> openDetail(series)
                CardAction.DELETE_HISTORY -> deleteHistoryRecord(series,anchor)
                CardAction.HIDE_FROM_HOT -> {
                    library.hide(series.id)
                    refreshLibraryCards(series.id,true)
                }
                CardAction.MANAGE_HISTORY -> manageHistory(series,anchor)
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        favoriteStatus?.text=favoriteMonitor.status
        val home=homeScreen
        if(screen=="catalog" && tab==TAB_HOME && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // 首页没有底部按钮了，收藏更新直接就地重建剧架：按卡片 key 恢复选择，
            // 焦点不在剧架上时（比如停在顶部图标上）不抢焦点。
            homeUpdateCount=library.updatedFavorites()
            val shelves=homeShelves(); val focusedKey=currentFocus?.tag?.toString().orEmpty()
            home.updateShelves(shelves,shelves.any { focusedKey.startsWith(it.key+":") })
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
     * 投影位图与色值。设置页的重建很轻（`showCatalog()` 在设置页只重画设置页），
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
         * 把「弹幕显示」的子项（弹幕大小 / 弹幕透明度）右移一档：
         * 这两项只对弹幕文字生效，缩进之后一眼能看出它们归属上面那一行。
         */
        fun indentSetting(view: View) {
            val params=view.layoutParams as? LinearLayout.LayoutParams ?: return
            params.leftMargin=dp(TvStyle.GAP_BLOCK)
            view.layoutParams=params
        }
        /**
         * 滑动条设置项：左侧项目名与上面几行同宽，右侧是可滑的控件。
         *
         * 返回（滑块，整行容器）：调用方要能按需把**整行**收起来（弹幕隐藏时），
         * 只藏滑块会在左边留一条空标签。
         *
         * 与播放设置面板里那两条共用同一个 [DanmakuSlider]，改的是同一份本机偏好，
         * 因此设置页调好之后进播放界面立刻就是新的字号 / 透明度。
         */
        fun sliderSetting(group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,onValue: (Float)->Unit): Pair<DanmakuSlider,View> {
            val line=row().apply { clipChildren=false; clipToPadding=false }
            // 标签字号与上面那些整行设置项一致（[TvStyle.BUTTON_TEXT_NORMAL]），不再单独放大一档。
            line.addView(
                text(label,TvStyle.BUTTON_TEXT_NORMAL,TvStyle.text).apply {
                    gravity=Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=TextUtils.TruncateAt.END
                },
                lp(dp(110),-2)
            )
            val slider=DanmakuSlider(this,label,min,max,step,value,onValue)
            line.addView(slider,lp(0,-2).apply { weight=1f })
            group.addView(line,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
            return slider to line
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
        lateinit var autoNextButton: TvButton
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        // 弹幕显示方式，以及它下面的两项文字外观（字号缩放 / 不透明度）。
        // 顺序是「自动连播 → 弹幕显示 → 弹幕大小 → 弹幕透明度」：字号与透明度是弹幕显示这一项的
        // 子项，因此紧跟在它下面、并按 [indentSetting] 缩进一档，和上面的平级设置项区分开。
        lateinit var danmakuSetting: TvButton
        // 回调里要用的 syncDanmakuStyleRows 在下面才定义（它要引用两条外观行），
        // 因此这里先建好这一行，等外观行齐了再补上回调。
        danmakuSetting=setting(playback,danmakuLabel("弹幕显示  ·  ")) {}
        // 两条外观项连行一起收起来：`DanmakuSlider` 自己只占右半边，行还在的话左边会留一条空标签。
        // 用 GONE 而不是 INVISIBLE——收起来之后这两行不该再占位置，焦点链也一并跳过它们。
        lateinit var danmakuScaleLine: View
        lateinit var danmakuScaleRow: DanmakuSlider
        val scaleLine=sliderSetting(playback,"弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
            library.danmakuScale=value; danmakuView?.textScale=value
        }
        danmakuScaleRow=scaleLine.first
        danmakuScaleLine=scaleLine.second
        lateinit var danmakuAlphaLine: View
        lateinit var danmakuAlphaRow: DanmakuSlider
        val alphaLine=sliderSetting(playback,"弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
            library.danmakuAlpha=value; danmakuView?.textAlpha=value
        }
        danmakuAlphaRow=alphaLine.first
        danmakuAlphaLine=alphaLine.second
        indentSetting(danmakuScaleLine); indentSetting(danmakuAlphaLine)
        /**
         * 隐藏弹幕时收起这两项，选回可见档时再放出来。
         *
         * 判定只有一份（[DanmakuMode.showsStyle]），设置页与播放设置面板共用同一条规则。
         *
         * 这里要同时做三件事，缺一件都会出问题：
         * 1. 两行连行一起 `GONE`（只藏滑块会在左边留一条空标签）；
         * 2. 把「弹幕显示 → 弹幕大小 → 弹幕透明度」这条上下路径按当前档位重新指一遍——
         *    隐藏时「弹幕显示」的下键直接去主题网格，可见时再指回两条外观项，
         *    否则切回可见档之后这两行收不到上下键（链是单向写死的，不会自己长回来）；
         * 3. 主题网格首行的上行出口跟着变：隐藏时回「弹幕显示」，可见时回「弹幕透明度」。
         * 焦点若正停在被收起的那一行上，退回「弹幕显示」。
         */
        fun syncDanmakuStyleRows() {
            val shown=library.danmakuMode.showsStyle
            listOf(danmakuScaleLine,danmakuAlphaLine).forEach { line ->
                line.visibility=if(shown) View.VISIBLE else View.GONE
            }
            val themeHead=themeRows[TvTheme.entries.first()] ?: danmakuAlphaRow
            if(shown) {
                danmakuSetting.nextFocusDownId=danmakuScaleRow.id
                danmakuScaleRow.nextFocusUpId=danmakuSetting.id
                danmakuScaleRow.nextFocusDownId=danmakuAlphaRow.id
                danmakuAlphaRow.nextFocusUpId=danmakuScaleRow.id
                danmakuAlphaRow.nextFocusDownId=themeHead.id
            } else {
                danmakuSetting.nextFocusDownId=themeHead.id
                danmakuScaleRow.nextFocusUpId=danmakuSetting.id
                danmakuScaleRow.nextFocusDownId=danmakuSetting.id
                danmakuAlphaRow.nextFocusUpId=danmakuSetting.id
                danmakuAlphaRow.nextFocusDownId=danmakuSetting.id
            }
            // 主题网格首行左半边（前 THEME_GRID_COLUMNS/2 列）的上行出口。
            val upTo=if(shown) danmakuAlphaRow.id else danmakuSetting.id
            TvTheme.entries.take(THEME_GRID_COLUMNS/2).mapNotNull { themeRows[it] }.forEach { it.nextFocusUpId=upTo }
            if(!shown && (danmakuScaleLine.hasFocus() || danmakuAlphaRow.hasFocus())) danmakuSetting.requestFocus()
        }
        danmakuSetting.setOnClickListener { showDanmakuPicker(danmakuSetting,false) { syncDanmakuStyleRows() } }
        lateinit var backup: TvButton
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    showHint("记录已合并恢复")
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TvButton
        hidden=setting(device,"恢复隐藏的剧集  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏的剧集  ·  0 部"
        }
        // 可选：填上 App 抓到的口令后，搜索直接使用 App 搜索接口；留空时退回官网搜索。
        lateinit var session: TvButton
        fun sessionLabel()="搜索登录态  ·  ${if(library.searchToken.isBlank()) "未设置" else "已设置"}"
        session=setting(device,sessionLabel()) {
            tvTools.searchSession(session,library.searchToken,{ token ->
                library.searchToken=token; applySessionToken(); session.text=sessionLabel()
            },{
                library.searchToken=""; applySessionToken(); session.text=sessionLabel()
            })
        }
        // 「版本与更新」：打开更新弹窗，可以手动检查、下载并交给系统安装。
        // 有新版本时进入应用会主动提示一次；选过「暂不更新」后不再主动提示，
        // 直到装上任何新版本（见 AppUpdater.declined）。
        lateinit var versionRow: TvButton
        versionRow=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") {
            appUpdater.show(versionRow)
        }
        lateinit var license: TvButton
        license=setting(device,"开源许可") {
            tvTools.info("开源许可",licenseText(),license)
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
                val chip=TvButton(this,option.label,ButtonSize.COMPACT,ButtonTone.SECONDARY,action={ switchTheme(option) })
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
        // 播放偏好那一列**始终**把两条外观项算进去：它们只是隐藏档下不可见，
        // 但「弹幕显示 → 弹幕大小 → 弹幕透明度」这条上下路径必须先在链上，
        // 否则在弹窗里切回可见档之后这两行还是选不中（早先按档位裁剪列内容的写法就栽在这里）。
        // 隐藏档下由 [syncDanmakuStyleRows] 把「弹幕显示」的下行出口直接指到主题网格。
        val playbackItems=mutableListOf<View>(qualityButton,defaultSpeed,frame,autoNextButton,
            danmakuSetting,danmakuScaleRow,danmakuAlphaRow)
        val columns=listOf(playbackItems,listOf<View>(backup,hidden,session,versionRow,license))
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
                // 第一行的上行出口交给 [syncDanmakuStyleRows] 按当前弹幕档位指过去
                // （左半边到「弹幕显示」或「弹幕透明度」，右半边到「开源许可」）。
                chip.nextFocusUpId=themeLines.getOrNull(rowIndex-1)?.getOrNull(index)?.id
                    ?: if(index<THEME_GRID_COLUMNS/2) danmakuSetting.id else license.id
                chip.nextFocusDownId=themeLines.getOrNull(rowIndex+1)?.getOrNull(index)?.id ?: chip.id
            }
        }
        // 左半边的下行出口由 syncDanmakuStyleRows 按当前弹幕档位指过去；右半边固定回「开源许可」。
        license.nextFocusDownId=(themeRows[TvTheme.entries[THEME_GRID_COLUMNS/2]] ?: license).id
        syncDanmakuStyleRows()
        navIcons().forEach { it.nextFocusDownId=qualityButton.id }
        parent.addView(text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
        navEntry()?.requestFocus()
    }
    /**
     * 页面内提示（替代 Toast）。
     *
     * 系统 Toast 是独立于界面的一层浮窗，样式与字号不受本应用控制，电视上还会盖在画面正中；
     * 需要给一句「刚刚发生了什么」时改用它——页头下面一行淡字，不参与焦点、几秒后自己消失。
     */
    private fun showHint(message: String) {
        catalogHint=message
        catalogHintView?.let { view -> view.text=message; view.visibility=View.VISIBLE }
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint,4000)
    }
    /**
     * 「开源许可」弹窗正文。
     *
     * 信息按当前仓库写：派生来源、源码仓库、GPL-3.0-only 的许可范围、第三方来源
     * （对照 `NOTICE.md`），以及本机记录的边界。发布地址指向本仓库的 Releases，
     * 不再引用已经不存在的公开镜像仓库。
     *
     * **派生来源这一条与 `NOTICE.md` 第 1 节一一对应**：fork 关系、GPL-3.0 派生、
     * 自 2026-10-06 起在上游基础上修改、上游著作权归其作者。改 `NOTICE.md` 时同步改这里。
     */
    private fun licenseText(): String = listOf(
        "红果TV ${BuildConfig.VERSION_NAME} · GPL-3.0-only",
        "",
        "源码与许可证：github.com/pan8664716/HGTV",
        "  许可证全文见 LICENSE，第三方声明见 NOTICE.md",
        "",
        "派生自 N3urda/hongguoTV（GPL-3.0），自 2026-10-06 起在上游基础上修改；",
        "  上游著作权归其作者，逐项改动见 NOTICE.md 与 docs/CHANGELOG.md。",
        "",
        "内容协议与加密处理移植自 drpys（commit 22261ad，GPL-3.0）。",
        "",
        "第三方组件（完整清单见 NOTICE.md）：",
        "  Kotlin / AndroidX Media3 / OkHttp / ZXing：Apache-2.0",
        "  Bouncy Castle / jsoup / Microsoft Fluent Emoji：MIT",
        "  desugar_jdk_libs：GPL-2.0 with Classpath Exception",
        "",
        "本机记录（收藏 / 观看进度 / 设置）只保存在这台设备上，不上传。"
    ).joinToString("\n")
    /**
     * 搜索：**不分内容类型**（顶部那两个「漫剧 / 短剧」开关已经去掉）。
     * 漫剧与短剧 / 真人剧的搜索结果合并去重后一起显示，由 [ContentRepository.search] 负责。
     */
    private fun runSearch(value: String) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        library.rememberSearch(clean); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches()
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { it.query },anchor,{ index -> runSearch(history[index].query) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> deleteHistoryRecord(series,anchor)
            }
        })
    }
    /**
     * 删除一条本机观看记录：选中即删，不再二次确认。
     *
     * 菜单里这一项本身就是明确的删除意图（要先长按 / 按菜单键、再把焦点移到「删除记录」、
     * 最后按确认），再叠一层弹窗只是把遥控器上的操作变成三次。首页历史剧架的「删除记录」
     * 与历史集合页「管理记录 → 删除这条记录」共用这一份实现，两条路径行为一致。
     *
     * 删完就地刷新所在页面——首页重建剧架（历史栏少一部，空了整栏消失并保留相邻卡片的焦点），
     * 集合页重新按本机列表取页。只删观看进度：收藏、稍后观看、已隐藏的剧集都不动。
     */
    private fun deleteHistoryRecord(series: Series,anchor: View?) {
        library.removeHistory(series.id)
        if(collection=="history") { catalogFocus=""; showCatalog() } else refreshLibraryCards(series.id,true)
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null
        synopsisExpanded=false; relatedOpen=false
        relatedWorks=null; relatedLoading=false; relatedFailed=false; relatedFor=""
        relatedToken.incrementAndGet(); relatedJob?.cancel(true); relatedJob=null
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            // 内容已经在屏幕上（下面这一句就渲染），相关剧集丢后台去查：用户点开时多半已经就绪。
            prefetchRelated(series.id)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    /**
     * 详情页。
     *
     * [relatedOpen] 为真时走「相关剧集」布局：海报高度收到与「开始观看」底边平齐，
     * 下方的选集整块换成横向的相关剧集列表。[focusTarget] 用来在重排之后把焦点按回刚点的按钮。
     */
    /**
     * 后台预取相关剧集（详情内容已经上屏之后才调用）。
     *
     * 三个要点：
     *
     * 1. **不阻塞界面**：内容先渲染，这一条在 [io] 线程池上慢慢跑，用户看不到任何等待；
     * 2. **同一部剧不重复请求**：已经在跑或已经取到（[relatedFor]）就直接返回，
     *    因此用户点「相关剧集」时不会再发一次请求——取好了直接铺，还在途中就等它落地；
     * 3. **不走 [work]**：`work` 会取消上一次在途的请求（详情页重排、切集都会触发），
     *    而相关剧集是「同一部剧的附加内容」，被自己页面的重排取消掉就会永远停在加载态。
     *    这里单独持有 [relatedToken] + [relatedJob]：新剧作废旧剧，
     *    结果落地前再确认「还是同一部剧、还停在详情页」——中途换了剧或退出去，结果直接丢掉。
     */
    private fun prefetchRelated(id: String) {
        if(relatedFor==id && (relatedWorks!=null || relatedLoading)) return
        val token=relatedToken.incrementAndGet()
        relatedJob?.cancel(true)
        relatedFor=id; relatedLoading=true; relatedFailed=false
        relatedJob=io.submit {
            val result=runCatching { repository.related(id) }
            main.post {
                if(isDestroyed || relatedToken.get()!=token) return@post
                relatedLoading=false
                result.onSuccess { works -> relatedWorks=works }.onFailure { relatedFailed=true }
                // 用户此刻正好停在「相关剧集」这一栏里，就地铺上；否则什么也不用做。
                if(relatedOpen && screen=="detail" && detail?.series?.id==id) showDetail(false)
            }
        }
    }
    /**
     * 点「相关剧集」时调用：预取还没开始（或上一次失败了）才补一次请求。
     * 正常路径下这里什么都不做——请求早在详情上屏时就已经发出去了。
     */
    private fun ensureRelated(id: String) {
        if(relatedWorks!=null) return
        if(relatedLoading && relatedFor==id) return
        prefetchRelated(id)
    }
    private fun showDetail(focusEpisode: Boolean, focusTarget: View?=null) {
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
        // 四个入口（播放 / 收藏 / 完整简介 / 相关剧集）**等宽**：按钮文字长短不一
        // （「继续第 12 集」与「收藏」差一倍），按内容宽度排出来这一行会参差不齐。
        //
        // 用 weight=1 让父容器等分，**不要自己按屏宽算像素**：这一行在海报右侧的信息列里，
        // 可用宽度是「屏宽 − 两侧边距 − 海报宽 − 间距」，按整屏算会直接溢出屏幕（踩过一次）。
        // 文字放不下时 TvButton 自己省略号收尾。
        val actionGap=dp(TvStyle.GAP_INLINE)
        // 等宽之后每个按钮的可用宽度只有整行的四分之一，HERO 档的 36dp 内边距会把
        // 「继续第 12 集」挤成省略号，因此这一行单独把左右内边距收到 14dp（字号与高度不变）。
        val actionPad=TvStyle.GAP_INLINE+2
        info.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
        val progress=library.progress(data.series.id)
        val watched=library.watched(data.series.id)
        // 播放按钮的**文案固定为「播放」**，不随状态变（曾按状态显示「开始观看 / 继续第 N 集 / 重新观看」，
        // 用户要求统一成一个词）。续播目标仍然照算——`ResumePlayback.target` 处理了
        // 「上一集看完 → 从下一集开头」「看到一半 → 回到那一集的那个位置」两种情况，
        // 文案与动作因此仍然同源，只是不再把集数写在按钮上。
        val target=ResumePlayback.target(data.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
        // 这一行的三个按钮同档同高（56dp），不再出现「主按钮 56、旁边两个 48」的高低差；
        // 强调色仍然只给主操作，尺寸与语义分开管。
        // 播放按钮不再用 PRIMARY（常亮的亮面板）：简介页这一行是四个平级入口，
        // 只有一个亮着会让「收藏 / 完整简介 / 相关剧集」看起来像次要操作。
        // 四个按钮统一常规档 + 等宽，靠位置与文案区分主次。
        val play=addButton(actions,"播放",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
            if(watched) playEpisode(0,0) else playEpisode(target.index,target.position)
        }
        lateinit var favorite: TvButton
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TvButton
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        // 「相关剧集」排在「完整简介」之后：点开时海报缩到与「开始观看」底边平齐，
        // 下方的选集整块换成横向的相关作品；再点一次收起，回到原样。
        lateinit var relatedButton: TvButton
        relatedButton=addButton(actions,if(relatedOpen) "收起相关" else "相关剧集",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
            relatedOpen=!relatedOpen
            showDetail(false,relatedButton)
            if(relatedOpen) ensureRelated(data.series.id)
        }
        body.addView(hero,lp(-1,-2))
        content.addView(body,lp(-1,-2))
        // 详情页没有页头，上下移动只能靠按钮行与下方那一块（选集 / 相关剧集）互相指：
        // 按钮行里「相关剧集」与「完整简介」按下键进下方那一块，下方第一行的上键回到按钮行。
        relatedButton.nextFocusDownId=relatedButton.id
        // 弹簧：内容不足一屏时把选集推到屏幕底部，而不是在页面底部留一块空白。
        content.minimumHeight=screenHeight
        content.addView(Space(this),lp(-1,0).apply { weight=1f })
        // 下方这一整块：默认是选集网格，点开「相关剧集」之后换成横向的相关作品列表。
        // 两者互斥——相关剧集展开时不显示选集，收起时恢复选集。
        if(relatedOpen) {
            val works=relatedWorks
            if(relatedLoading || (works==null && !relatedFailed)) {
                content.addView(text("正在查找同系列…",16f,TvStyle.textDim).apply {
                    setPadding(pageInset(),dp(28),pageInset(),dp(18))
                },lp(-1,-2))
            } else if(relatedFailed) {
                // 失败给一个可重试的入口，而不是静默空列表。
                val retry=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(18)) }
                val label=text("相关剧集加载失败",16f,TvStyle.textDim).apply { setPadding(0,0,dp(TvStyle.GAP_BLOCK),0) }
                retry.addView(label,lp(-2,-2))
                addButton(retry,"重试") { prefetchRelated(data.series.id) }
                content.addView(retry,lp(-1,-2))
            } else if(works==null || works.items.isEmpty()) {
                // 接口没给相关剧集（或这一部确实没有）：如实说明，不留一块空白。
                content.addView(text("这一部暂时没有相关剧集",16f,TvStyle.textDim).apply {
                    setPadding(pageInset(),dp(28),pageInset(),dp(18))
                },lp(-1,-2))
            } else {
                val related=HomeScreen(this,posterBox,pageInset(),::loadCover,
                    { item,_ -> openDetail(item) },{ item,anchor,shelf -> quickActions(item,anchor,shelf) },{})
                // 同系列各季排在前面并标注「第 N 季」（正在看的那一季写「正在看」），
                // 之后是官网的相关短剧（状态行用它自己的角标）。标题按有没有同系列区分。
                val seasonIds=works.seasons.map { it.id }.toSet()
                val title=if(works.seasons.size>1) "同系列 ${works.seasons.size} 季 · 相关剧集" else "相关剧集"
                related.renderCompact(listOf(HomeScreen.Shelf("related",title,
                    works.items.map { item ->
                        val season=if(item.id in seasonIds) SeriesSeasons.season(item.title) else 0
                        // 「第 N 季」放在海报角标上（状态行那点字号读不出季数），同系列一眼可辨。
                        val badge=when {
                            item.id==data.series.id -> "正在看"
                            season>0 -> "第 $season 季"
                            else -> ""
                        }
                        HomeScreen.Entry(item,item.badge.ifBlank { "查看剧集" },badge=badge)
                    })),"",false)
                val host=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
                host.addView(related,lp(-1,-2))
                content.addView(host,lp(-1,-2))
                related.post {
                    related.firstCard()?.let { card ->
                        // 按钮行按下键落到相关作品的第一张卡；卡片按上键回到「相关剧集」按钮。
                        synopsisButton.nextFocusDownId=card.id
                        relatedButton.nextFocusDownId=card.id
                        card.nextFocusUpId=relatedButton.id
                        if(focusTarget==null) card.requestFocus()
                    }
                }
            }
        } else {
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
            // 按钮行按下键进选集网格；网格第一行的上键回到「相关剧集」（网格自己会按需要交还按键）。
            val firstCell=episodes.firstCell()
            synopsisButton.nextFocusDownId=(firstCell?.id ?: synopsisButton.id)
            relatedButton.nextFocusDownId=(firstCell?.id ?: relatedButton.id)
            firstCell?.nextFocusUpId=relatedButton.id
            if(focusEpisode) episodes.focusCurrent() else play.requestFocus()
        }
        // 海报高度：相关剧集展开时与右边按钮行的底边平齐（= 信息区的高度），
        // 收起时按原来的算法铺满剩余屏高。信息区高度要等布局完成才量得准，因此在这里回填。
        // 宽度跟着高度按 7:10 一起缩，否则封面会在变矮的卡座里左右留出两条空白（FIT_CENTER 不会拉伸）。
        fun fitPoster(attempt: Int=0) {
            val target=if(relatedOpen) info.height else posterHeight
            // 首帧里信息区可能还没量完（height==0）：再等一帧，最多两次，避免海报停在错误高度上。
            if(target<=0 && attempt<2) { hero.post { fitPoster(attempt+1) }; return }
            if(target<=0 || target==posterBox.layoutParams.height) return
            val width=(target*7f/10f).toInt()+2*posterBand
            posterBox.layoutParams=(posterBox.layoutParams as LinearLayout.LayoutParams).apply {
                height=target; this.width=width
            }
            posterBox.requestLayout()
        }
        hero.post { fitPoster() }
        if(focusTarget!=null) focusTarget.post { focusTarget.requestFocus() }
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
                .onFailure { android.util.Log.w("HGTV","Danmaku fetch failed episode=$episodeId",it) }
                .getOrDefault(emptyList())
            main.post {
                if(isDestroyed || danmakuToken.get()!=token || screen!="player") return@post
                danmakuLoaded=true; danmakuCount=items.size
                android.util.Log.i("HGTV","Danmaku episode=$episodeId count=${items.size} mode=${library.danmakuMode.storedValue}")
                danmakuView?.setDanmaku(items)
                // 一条都没取到才提示一次：平台上只有部分剧集对匿名设备放出弹幕，
                // 不说明的话「屏幕上没有弹幕」会被当成功能坏了。

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
            sleepOption=option("定时 ${sleepTimer.label()}") { showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)) }
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
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HGTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
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
                        android.util.Log.i("HGTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
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
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hgtv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle
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
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HGTV","Next episode prepared near end")
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
        android.util.Log.w("HGTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
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
        else { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
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
        showHud(); if(playbackMenu!=null) showPlaybackMenu(sleepRow(library.danmakuMode.showsStyle)); syncMediaSession()

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
                menu.select(sleepRow(library.danmakuMode.showsStyle),index); updatePlaybackText()
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
    /**
     * 弹幕显示方式：全屏 / 上方 / 隐藏。
     *
     * [onChanged] 在档位真的变了之后回调一次——设置页用它就地收起 / 放出「弹幕大小」「弹幕透明度」
     * 两行（[DanmakuMode.showsStyle]）；播放设置面板走的是整块重建，不需要这个回调。
     */
    private fun showDanmakuPicker(anchor: TextView,inPlayer: Boolean,onChanged: ()->Unit={}) {
        tvTools.choose("弹幕显示方式",listOf("全屏弹幕 · 整幅画面从上到下都是弹幕轨道","上方弹幕 · 只在画面上部滚动","隐藏弹幕 · 完全不显示弹幕"),anchor,{ index ->
            val changed=library.danmakuMode!=DanmakuMode.entries[index]
            library.danmakuMode=DanmakuMode.entries[index]; danmakuView?.mode=library.danmakuMode
            if(anchor.tag!="playback-settings") anchor.text=danmakuLabel(if(inPlayer) "弹幕 " else "弹幕显示  ·  ")
            if(changed) onChanged()
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
     * 清晰度 / 倍速 / 画面模式 / 弹幕 / 弹幕大小 / 弹幕透明度 / 定时。
     *
     * 「弹幕大小 / 弹幕透明度」只在弹幕可见时存在：隐藏档下整层不画、也不发请求，
     * 这两项改完看不到任何变化，因此那两行**直接不添加**（行号见 [sleepRow]）。
     *
     * 功能竖排、选项横排，上下换功能、左右换选项，确认立即生效，返回收起整个浮层。
     * 面板高度**固定为屏幕下方三分之一**，装不下的行留在滚动容器里，遥控器焦点走到哪一行
     * 就自动把哪一行带进可视区（ScrollView 自己的 `requestChildFocus` 行为），因此所有行都能按到。
     *
     * [focusRow] 与 [focusChoice] 用来在改清晰度重建菜单、或点 HUD 状态行时定位焦点；
     * [focusChoice] 为负数时落在该项目当前选中的选项上。
     */
    private fun showPlaybackMenu(focusRow: Int=0,focusChoice: Int=-1) {
        if(screen!="player" || playError) return
        dismissPlaybackMenu()
        // 先建面板再逐行追加：选项的点击回调要引用面板本身，不能在构造链里读还没赋值的变量。
        val menu=PlaybackMenu(this)
        // 隐藏弹幕时整层不画、也不发请求，字号与透明度改完看不到任何变化——那两行直接不添加，
        // 面板从「弹幕」直接跳到「定时」。判定与设置页同一条规则（[DanmakuMode.showsStyle]）。
        val showsStyle=library.danmakuMode.showsStyle
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
                    // 档位切换会改变面板的行数（隐藏时没有两条外观项），因此整块重建，
                    // 焦点按回刚改的这一行、落在同一个档位上。
                    if(previous!=mode) showPlaybackMenu(MENU_ROW_DANMAKU,index)
                    else { menu.select(MENU_ROW_DANMAKU,index); updatePlaybackText() }
                }
            })
        // 弹幕文字的两项连续外观：字号缩放与不透明度。左右键滑动，改完立刻作用在当前画面上。
        if(showsStyle) menu
            .addSlider("弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
                library.danmakuScale=value; danmakuView?.textScale=value
            }
            .addSlider("弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
                library.danmakuAlpha=value; danmakuView?.textAlpha=value
            }
        menu
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
    /**
     * 播放器底部的选集面板（遥控器「↓」打开）。
     *
     * **打开面板不暂停播放**：选集是一层贴底的浮层，画面与声音照常走，用户挑完再切集。
     * 因此这里不动播放状态，关闭时也不需要「恢复播放」——只把焦点还给底部控制条 / 下拉箭头，
     * 并按打开前的可见性恢复浮层。播放位置照旧定期落盘（[saveProgress] 由播放进度回调负责）。
     */
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        // 选集面板也要知道在放哪一集，标题条跟着面板一起露出来。
        playbackTopBar?.visibility=View.VISIBLE
        // 面板内容与简介页共用同一个 EpisodeGrid，只有宿主不同（贴底弹窗 / 页面末尾），
        // 翻页方式一致：左右到行两端、上下到第一行 / 最后一行到边缘翻页。
        val grid=EpisodeGrid(this,data.episodes.size,episodeIndex,player=true,
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { episodePanel?.dismiss(); playEpisode(selected) }
            })
        grid.fillHeight=true
        val next=EpisodePanel(this,grid) {
            episodePanel=null
            if(foreground && generation==ticket && screen=="player") {
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
                collection!=null -> { collection=null; catalogFocus=""; showCatalog() }
                tab!=TAB_HOME -> switchTab(TAB_HOME)
                nav.any { it.hasFocus() } -> confirmExit()
                else -> (navEntry() ?: nav.firstOrNull())?.requestFocus()
            }
        }
    }
    /**
     * 主界面按返回不再直接退出：先弹一个确认框，确认后才 finish。
     * 弹窗走全站统一的 [TvDialog]，焦点默认落在「取消」上，误按返回键不会退出应用；
     * 取消后焦点回到首页入口，再按返回才会重新弹出。
     */
    private fun confirmExit() {
        tvTools.confirm("退出红果TV","收藏与观看进度已经保存在本机，确定要退出吗？",navEntry() ?: nav.firstOrNull() ?: currentFocus) { finish() }
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
        appUpdater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    // 首页判空看的是四个剧架（[homeFeed]）：它的 catalog 本来就是空的，
                    // 用 catalog 判断会让每次回到前台都重打四个榜单。
                    else -> showCatalog(load=when {
                        localCatalog() -> false
                        tab==TAB_HOME -> homeFeed.isEmpty()
                        tab==TAB_RANK -> catalog.isEmpty()
                        tab==TAB_CATEGORY -> catalog.isEmpty()
                        tab==TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
                        else -> false
                    })
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); appUpdater.pause()
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
        favoriteMonitor.destroy(); tvTools.destroy(); appUpdater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); danmakuWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hgtv-network-cleanup").start()
        super.onDestroy()
    }
}
