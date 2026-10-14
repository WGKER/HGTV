// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)

/**
 * 简介页下方那一栏的内容：同系列各季在前（按季号升序），官网的相关短剧在后，已去重。
 *
 * **不随详情一起返回**：找同系列要额外发搜索请求，而详情页每次进都要开一次，
 * 把它挂在详情上会让「进简介页」变慢。界面点开「相关剧集」时才调 [ContentRepository.related]。
 */
data class RelatedWorks(
    val items: List<Series>,
    /** 其中属于**同一部剧**的那些季（按季号升序，含正在看的这一季）：界面据此标注「第 N 季」。 */
    val seasons: List<Series>
)
/**
 * 一条搜索提示词（联想词）。
 *
 * [series] 非空表示这条提示词**本身就是一部剧**（服务端给了 `video_data.series_id`）：
 * 点它可以跳过搜索直接进详情；为空时它只是一个查询词（`common_query` / `title` / `album` /
 * 分类这类），点它就拿 [name] 去搜。
 */
data class SearchSuggestion(
    val name: String,
    val series: Series? = null,
    /** 服务端的 `word_type`：`short_play_name` / `title` / `common_query` / `album` / `category` 等。 */
    val wordType: String = ""
)

data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    /**
     * 提示词接口要求的应用号（缺了它服务端返回 500）。
     * 8662 就是「红果短剧」在番茄系里的 aid（与 [VendorConstants.device] 里的 `aid` 同值）。
     */
    private val SUGGEST_APP_ID = "8662"
    /**
     * 提示词接口的 `web_id`：官网上是每次页面加载生成的一个随机标识。
     * 这里整个进程生成一次即可（服务端并不校验它与会话的对应关系）。
     */
    private val webId = java.util.UUID.randomUUID().toString()
    /**
     * 热搜词的种子词（见 [hotSearches]）：都是题材向的泛词，联想接口对它们返回的候选里
     * 混着一批真正的查询词（`30集短剧` / `都市逆袭` / `甜宠` 这类）。
     * 只取前三个：每个种子一次请求，串行约 200ms，再多就只是把同一批词换几个说法。
     */
    private val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    /**
     * 热搜词的词类型优先级（见 [hotSearches]）。
     *
     * 题材 / 分类这类（`short_play_category` / `category`）读起来最像「大家在搜的题材」，
     * 排最前；`common_query` 是平台自己的搜索建议（`短剧最热榜前十名` 这种）排其次；
     * `title` / `album` 更像是书名 / 专辑名，放最后。
     */
    private val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    /**
     * 热搜词的并发池：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     * 池子很小且只在取热搜词时用一下，因此按需创建、线程是守护线程（不挡进程退出）。
     */
    private val SUGGEST_POOL = java.util.concurrent.Executors.newFixedThreadPool(SUGGEST_SEEDS.size) { runnable ->
        Thread(runnable,"hgtv-hotword").apply { isDaemon=true }
    }
    /** 热搜词的兜底表：提示接口整体不可用时用（搜索页不能空着）。 */
    private val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    private data class DanmakuPage(val items: List<Danmaku>,val nextStart: Long,val cursor: String,val hasMore: Boolean,
                                   val raw: List<org.json.JSONObject> = emptyList())
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    /**
     * 搜索已经给过界面的剧集 id（按「类型 + 关键词」分组）。
     *
     * 官网搜索与片库目录会重复给出同一部剧（同一部剧的不同季、目录分页本身也会重复），
     * 这里替界面记住已经发过的条目，后面补页时只回新片名；
     * 一组补页里一个新条目都没有，就说明这个关键词在片库里已经找完了。
     */
    private val catalogSeen=linkedMapOf<String,MutableSet<String>>()
    private fun catalogKey(types: List<ContentType>,keyword: String)="${types.joinToString("+") { it.storedValue }}:$keyword"
    /**
     * App 搜索接口还能不能用：空响应体说明平台对匿名设备已经不给数据了（实测所有线路、多组设备参数都一样）。
     * null = 还没试过。只有确认它「活着或未知」时才按游标过期处理漫剧翻页，
     * 否则漫剧搜索会卡在第 1 页，永远走不到官网搜索 + 片库目录补页那条路。
     */
    @Volatile private var appSearchAlive: Boolean?=null
    /**
     * 搜索登录态（可选）：App 搜索接口对匿名设备返回空响应体，带上 App 抓到的 `x-tt-token`
     * 后它会按该账号返回数据（第 1 页起就是完整结果，不再依赖官网搜索与片库目录）。
     * 留空时按匿名设备请求，搜索仍然可用——退回官网搜索 + 片库目录补页。
     */
    @Volatile var sessionToken: String?=null
    /**
     * 弹幕接口是播放器接口，走 App 域名（与搜索 / 详情同一套设备参数）。
     * 站点有多条线路，单条失败时依次换线，最后一条仍失败才放弃本窗口。
     */
    /**
     * 找同系列各季时最多扫几页片库目录（每页 24 部、共 34 页）。
     *
     * 官网搜索对同一关键词返回的 10 条**不稳定**（同一部剧的某一季有时在前 10、有时不在），
     * 片库目录则是稳定分页的，因此这里是召回的最后一道保险：实测 3 页能捞回第九季，
     * 6 页能把「聚宝仙盆之杂灵根才是真BOSS」的 13 季凑齐。这几页是并发请求的，代价可控。
     */
    private val SEASON_CATALOG_PAGES=6
    /** 除完整基名外，还依次去掉 1~3 个尾字各搜一次：官网搜索换关键词就是换一批结果。 */
    private val SEASON_SEARCH_TRIMS=3
    /** 沿季号逐季搜索的上限（季号），以及「连续几个季号没命中就停」的阈值。 */
    private val SEASON_SEARCH_LIMIT=40
    private val SEASON_MISS_LIMIT=3
    /** 季号搜索的上探余量：搜 1 到「当前季 + 这个数」，用来捞当前季之后新上线的季。 */
    private val SEASON_LOOKAHEAD=2
    /** 相关剧集取数用的小线程池：这些请求彼此独立，串行发要一分多钟。 */
    private val relatedPool=java.util.concurrent.Executors.newFixedThreadPool(4) { task ->
        Thread(task,"hgtv-related").apply { isDaemon=true }
    }
    /** 同系列结果的内存缓存条数：按基名索引，界面重复点开同一部剧时直接命中。 */
    private val SEASON_CACHE_LIMIT=16
    private val seasonCache=linkedMapOf<String,List<Series>>()
    private val commentHosts=listOf("api5-normal-sinfonlinea.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-hl.fqnovel.com")
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    /** 搜索 / 弹幕这类 App 接口共用的设备参数：与播放器版本对齐，避免平台按旧版本拒绝。 */
    private fun appValues(): LinkedHashMap<String,String> {
        val values=VendorConstants.device.toMutableMap()
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]="72232"
        values["version_name"]="7.2.2.32"
        return LinkedHashMap(values)
    }
    /**
     * 给搜索请求补上登录态。平台对匿名设备返回空响应体，只有带上登录口令才有数据；
     * 直接粘贴整段 Cookie（形如 `sessionid=…`）时同时按 `Cookie` 头发一次。
     */
    private fun Signer.Request.withSession(): Signer.Request {
        val token=sessionToken?.trim().orEmpty()
        if(token.isEmpty()) return this
        val extra=linkedMapOf("x-tt-token" to token)
        if(token.contains('=')) extra["Cookie"]=token
        return copy(headers=headers+extra)
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    /**
     * 榜单页：四个热播榜（[RankBoard]）共用一份解析（见 [RankPage]），只有路由与 `rankKey` 不同。
     *
     * 首页的四个「热播…」剧架与排行榜页的四个榜单读的是**同一份数据**，
     * 因此剧架行末的「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
     */
    fun rank(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        return RankPage.parse(text("$site/rank/${board.route}?page=$page"),page,board)
    }
    /**
     * 首页四个「热播…」剧架的第 1 页：四个榜单彼此独立，串行要等四份 RTT，因此并发取。
     *
     * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），四个都失败才抛错。
     * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途的四个请求会一起收手。
     */
    fun homeFeed(): Map<RankBoard,CatalogPage> {
        val scope=RequestScope.current()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(RankBoard.entries.size) { task ->
            Thread(task,"hgtv-homefeed").apply { isDaemon=true }
        }
        return try {
            val futures=RankBoard.entries.map { board ->
                board to pool.submit(java.util.concurrent.Callable { RequestScope.withScope(scope) { runCatching { rank(1,board) }.getOrNull() } })
            }
            val result=linkedMapOf<RankBoard,CatalogPage>()
            futures.forEach { (board,future) -> future.get()?.let { page -> result[board]=page } }
            if(result.values.none { it.items.isNotEmpty() }) throw IOException("热门榜暂不可用")
            result
        } finally {
            pool.shutdownNow()
        }
    }
    /**
     * 分类目录的一页：`/category/{path}?page=N`（`path` 来自 [CategoryCatalog]，例如 `comic-drama/fantasy`）。
     *
     * 结构与片库目录同构（`loaderData.category_$` 里的 `recommendList` + `pagination`），
     * 24 部一页；分类页**没有名次与热度**，所以 [CatalogPage.ranking] 恒为 null。
     */
    fun category(path: String, page: Int = 1): CatalogPage {
        require(path.isNotBlank() && path.length<=80 && page in 1..MAX_CATALOG_PAGE)
        val data=router("$site/category/$path"+if(page>1) "?page=$page" else "")
        val section=data.optJSONObject("category_$") ?: data.optJSONObject("category_page") ?: throw IOException("分类目录暂不可用")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("分类目录结构已变化")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,MAX_CATALOG_PAGE))
    }
    /**
     * 搜索：先走 App 搜索接口，拿不到数据时退回官网搜索。
     *
     * App 搜索接口 `POST /reading/bookapi/search/tab/v` 是唯一按内容类型分 tab、也唯一能翻页的入口
     * （第 2 页起带 `search_id` / `passback` / `next_offset`），所以它优先；配置 [sessionToken] 时
     * 它会按登录态返回数据，匿名设备则一律返回 HTTP 200 + 空响应体。
     *
     * 接口不可用时退回：第 1 页读官网搜索（`/search/{kw}`：一次只给约 10 条、忽略所有分页参数），
     * 第 2 页起从片库目录里筛片名含关键词的剧。搜索结果过期要如实抛出，
     * 让界面回到第 1 页重新搜，而不是拿官网的前 10 条顶替。
     */
    /**
     * 界面用的搜索：**不区分内容类型**（顶部那两个「漫剧 / 短剧」开关已经去掉）。
     *
     * App 搜索接口按 tab 分（`19` 漫剧、`11` 短剧 / 真人剧），这里两个 tab 各取一次再合并、
     * 按剧集 ID 去重；接口对匿名设备不可用时退回官网搜索（本身就不分类型），
     * 第 2 页起从**两本片库目录**（`comic-drama` 与 `real-drama`）里扫。
     */
    /**
     * 搜索提示词（联想词）：官网 PC 端搜索框用的就是这个接口
     * （前端 chunk 里的 `/incent_resource/suggestion`，见 docs/HONGGUO_API.md 3.5.1）。
     *
     * ```
     * GET https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&web_id={随机 UUID}&query={关键词}&count=10
     * ```
     *
     * **不需要签名、不需要登录态**，只要求 `app_id=8662`（缺了它服务端返回 500）；`web_id` 可省。
     * `count` 有效上限 20（超过按 20 算），不传按 10；空关键词返回 500，因此调用前先判空。
     *
     * 响应里每条的 `video_data` 与卡片是同一套字段（`series_id` / `series_title` /
     * `series_cover` / `series_intro` / `episode_cnt` / `category_list`），因此直接复用 [card] 解析。
     * `word_type` 实测见过 `short_play_name`（剧名，带完整 `video_data`）、`title`、`common_query`、
     * `album`、`short_play_category`、`short_play_kol_user`——后几种的 `video_data` 是空对象，
     * 只能当查询词用。
     */
    fun suggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$site/incent_resource/suggestion?app_id=$SUGGEST_APP_ID&web_id=$webId" +
            "&query=${Signer.encode(clean)}&count=$wanted"
        val rows=JSONObject(text(url)).optJSONArray("suggest_list")?.objects() ?: return emptyList()
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            // video_data 为空对象（title / common_query 这类）时不算剧集，只能当查询词。
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let { card(it) }?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) }
            SearchSuggestion(name,series,row.str("word_type"))
        }.distinctBy { it.name }.take(wanted)
    }
    /**
     * 热搜词：搜索页空输入时铺在下面那一排。
     *
     * 平台**没有**热搜榜接口（`/incent_resource/hot_word`、`/incent_resource/hot_search` 等都是 404），
     * 但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：拿几个题材词当种子各问一次，
     * 把返回里**不是某一部剧**的那些（`title` / `common_query` / `short_play_category` / `category` /
     * `album`——`video_data` 是空对象）收集起来去重，就是一份现成的热词表。
     *
     * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
     * 全部种子词都失败时退回 [FALLBACK_HOT]：搜索页不能因为一个提示接口而空着。
     */
    fun hotSearches(limit: Int = 12): List<String> {
        val wanted=limit.coerceIn(1,30)
        // 种子词**并发**问（串行五个要 1.8s，并发后是单次请求的时间）。
        val pools=SUGGEST_SEEDS.map { seed -> java.util.concurrent.CompletableFuture.supplyAsync({ suggestionsPool(seed) }, SUGGEST_POOL) }
            .map { future -> runCatching { future.join() }.getOrDefault(emptyList()) }
        // 分层 + 层内轮询：
        // - 先按词类型分层（题材 / 分类 > 平台搜索建议 > 书名）；
        // - 同一层里**按种子轮询**交替取——一个种子单独就能填满整张表，
        //   顺着一个种子取的话整排热搜词都是同一个题材的变体（实测全是「…短剧」）。
        val seen=linkedSetOf<String>()
        HOT_WORD_ORDER.forEach { type ->
            if(seen.size>=wanted) return@forEach
            val lists=pools.map { pool -> pool.filter { it.second==type }.map { it.first } }
            var round=0
            while(seen.size<wanted && lists.any { it.size>round }) {
                lists.forEach { list -> list.getOrNull(round)?.let { if(seen.size<wanted) seen += it } }
                round++
            }
        }
        return if(seen.isEmpty()) FALLBACK_HOT.take(wanted) else seen.toList()
    }
    /** 一个种子词的候选：`(词, word_type)`，过滤掉剧名、过长过短的与重复的。 */
    private fun suggestionsPool(seed: String): List<Pair<String,String>> =
        runCatching { suggestions(seed,20) }.getOrDefault(emptyList())
            .filter { it.series==null && it.name.length in 2..12 }
            .map { it.name to it.wordType }
            .distinctBy { it.first }
    fun search(keyword: String, page: Int = 1): CatalogPage = search(keyword,page,ContentType.entries.toList())
    /** 只搜一种内容类型（App 搜索接口的一个 tab）：合并搜索与联调都走这一份实现。 */
    fun search(keyword: String, page: Int, type: ContentType): CatalogPage = search(keyword,page,listOf(type))
    private fun search(keyword: String, page: Int, types: List<ContentType>): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100 && types.isNotEmpty())
        // 第 1 页总会试一次（平台恢复时能自动接回来）；后面几页在确认接口已关掉之后就不再空跑。
        if(page==1 || appSearchAlive!=false) {
            val items=mutableListOf<Series>()
            var hasMore=false; var answered=false; var expired=false
            // AI 剧在 App 搜索接口里没有对应的 tab（见 [ContentType.searchable]），不请求它。
            types.filter { it in ContentType.searchable }.distinct().forEach { type ->
                try {
                    val result=appSearch(keyword,page,type)
                    answered=true; items+=result.items; if(result.hasMore) hasMore=true
                } catch(e: SearchSessionExpiredException) { expired=true } catch(_: Exception) { }
            }
            // 有一个 tab 给了答案就用它：另一个 tab 空响应 / 少 tab 都不算失败。
            if(answered) return CatalogPage(valid(items),hasMore && page<100)
            // 所有 tab 都答不上、而且是游标过期：如实抛出去，让界面回到第 1 页重搜。
            if(expired && page>1) throw SearchSessionExpiredException()
        }
        return if(page==1) websiteSearch(keyword,types) else catalogSearch(keyword,page,types)
    }
    /**
     * 官网搜索：一次只给前 10 条，而且**没有任何分页参数**
     * （`page` / `page_num` / `offset` / `page_size` / `count` 全部被服务端忽略，实测同一批结果）。
     * 所以它只负责第 1 页：相关度最高的那几条，后面继续加载的内容由 [catalogSearch] 从片库目录里补。
     */
    private fun websiteSearch(keyword: String,types: List<ContentType>): CatalogPage {
        val data=router("$site/search/${Signer.encode(keyword)}?page=1")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        // 第 1 页开一次新的「已发过」名单：重新搜同一个词就是重新开始。
        synchronized(catalogSeen) {
            if(catalogSeen.size>=20) catalogSeen.keys.firstOrNull()?.let(catalogSeen::remove)
            catalogSeen[catalogKey(types,keyword)]=items.map { it.id }.toMutableSet()
        }
        // 空结果不是错误：搜索页自己会显示「没有找到相关…，换个关键词试试」。
        // hasMore 交给片库目录决定（下面每次都会真的去查，所以这里先给 true）。
        return CatalogPage(items,true)
    }
    /**
     * 搜索的第 2 页起：从片库目录（官网分类页）里筛出片名带关键词的剧。
     *
     * 官网搜索没有分页、App 搜索接口对匿名设备返回空响应，能往下翻的只有分类页（24 部一页、34 页）。
     * 每次往后扫 [SEARCH_SCAN_PAGES] 页，把命中的片名接在搜索结果后面；
     * 目录翻完就 hasMore=false，界面上的「已显示全部 N 部」就是真的到头了。
     */
    private fun catalogSearch(keyword: String,page: Int,types: List<ContentType>): CatalogPage {
        val start=1+(page-2)*SEARCH_SCAN_PAGES
        val items=mutableListOf<Series>()
        var morePagesLeft=false
        types.forEach { type ->
            var total=0
            for(offset in 0 until SEARCH_SCAN_PAGES) {
                val index=start+offset
                if(index>MAX_CATALOG_PAGE || (total>0 && index>total)) break
                val (matched,pages)=catalogPage(keyword,index,type)
                total=pages
                items+=matched
            }
            if(total>0 && start+SEARCH_SCAN_PAGES<=total) morePagesLeft=true
        }
        // 只回界面还没见过的条目；一个新条目都没有时只再给一次机会（第 2 页），
        // 之后就把 hasMore 收掉——否则关键词在片库里只有两三条时，用户会一直往下滚却什么都没有。
        val seen=synchronized(catalogSeen) { catalogSeen.getOrPut(catalogKey(types,keyword)) { mutableSetOf() } }
        val fresh=synchronized(catalogSeen) { items.distinctBy { it.id }.filter { seen.add(it.id) } }
        return CatalogPage(fresh,morePagesLeft && (fresh.isNotEmpty() || page<=2))
    }
    /** 片库目录第 [index] 页里片名含 [keyword] 的剧，外加这本目录一共多少页。 */
    private fun catalogPage(keyword: String,index: Int,type: ContentType): Pair<List<Series>,Int> {
        val data=router("$site/category/${type.catalog}?page=$index")
        val section=data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
        val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
        val total=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
        val needle=keyword.filterNot(Char::isWhitespace)
        val matched=valid(rows.objects().map(::card)).filter { it.title.filterNot(Char::isWhitespace).contains(needle,ignoreCase=true) }
        return matched to minOf(total,MAX_CATALOG_PAGE)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=appValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        // 漫剧翻页必须带上一页的游标。接口已经被平台关掉（空响应体）时不再抛「搜索结果已过期」：
        // 那样会把漫剧搜索钉死在第 1 页，走不到下面的官网搜索 + 片库目录补页。
        if(type==ContentType.COMIC && page>1 && cursor==null && appSearchAlive!=false) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        val raw=text(signed.url,signed.withSession())
        // 平台对匿名设备返回 HTTP 200 + 空响应体，这里必须先判空：
        // 直接丢给 JSONObject 会抛 JSONException（A JSONObject text must begin with '{'），
        // 那既不是可读的错误，也会把「App 搜索不可用、改用官网搜索」这条回退路径打断。
        if(raw.isBlank()) { appSearchAlive=false; throw IOException("App 搜索接口暂无响应") }
        appSearchAlive=true
        val data=JSONObject(raw)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=text("$site/detail?series_id=$id")
        val o=extractRouter(html).getJSONObject("loaderData").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        // 简介页下方那一栏：先是同一部剧的其它季（平台没有分季接口，用基名搜索近似），
        // 再接官网详情页第二段数据里的相关短剧（`mergeLoaderData` 注入的 recommendations）。
        // 两段都取不到就当作没有相关内容：详情本身必须能打开，不能因为这一栏而报错。
        // 这里**不**去找同系列：那要额外发搜索请求，详情页每次进都要开一次，
        // 挂在这里会让「进简介页」变慢。相关剧集在界面点开时才取（见 [related]）。
        return Detail(card(o).copy(id=id),episodes)
    }
    /**
     * 相关短剧：官网详情页里「相关短剧」那一栏的内容。
     *
     * 数据不在 `_ROUTER_DATA.detail_page` 里，而是同一份 HTML 后面那段
     * `<script data-fn-name="mergeLoaderData" data-fn-args="…">`（HTML 实体转义过的 JSON 数组，
     * 内层 `routerDataFnArgs[0]` 又是一层 JSON 字符串）。取 `recommendationGroups.related_list`，
     * 缺失时退回同一层的 `videoList`（实测两者内容一致）。
     */
    fun related(id: String): RelatedWorks {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=text("$site/detail?series_id=$id")
        val series=extractRouter(html).getJSONObject("loaderData").optJSONObject("detail_page")
            ?.optJSONObject("seriesDetail")?.let { card(it).copy(id=id) } ?: return RelatedWorks(emptyList(),emptyList())
        val seasons=seasonSiblings(series)
        return RelatedWorks(relatedFor(series,seasons,html),seasons)
    }
    /** 从已经取到的详情页 HTML 里解析相关短剧；解析失败返回空列表。 */
    private fun relatedOf(html: String,id: String): List<Series> = runCatching {
        val body=extractRecommendations(html) ?: return emptyList()
        val groups=body.optJSONObject("recommendationGroups")
        val rows=groups?.optJSONArray("related_list") ?: body.optJSONArray("videoList") ?: return emptyList()
        valid(rows.objects().map(::card)).filterNot { it.id==id }
    }.getOrDefault(emptyList())

    /**
     * 简介页下方那一栏的内容：**同一部剧的其它季**在前，官网的相关短剧在后，重复的去掉。
     *
     * 平台没有「分季」接口——详情页里没有任何季 / 系列字段，页面上那栏「相关短剧」
     * （`recommendationGroups.related_list`）是「看了这部还看什么」的推荐，不是同系列。
     * 能拿到同系列各季的只有官网搜索：用剥掉季号的**基名**去搜（实测搜基名时同一系列的各季
     * 都排在前 10 条里），再用 [SeriesSeasons.normalize] 过滤出真正同名的系列、按季号升序排列。
     *
     * 搜索失败或没有同系列时这一段为空，不影响后面的相关短剧。
     */
    private fun relatedFor(series: Series,seasons: List<Series>,html: String): List<Series> {
        val seen=seasons.map { it.id }.toMutableSet()
        // 相关短剧接在同系列之后；已经在前面出现过的（同剧不同季）不再重复。
        // 正在看的那一部本身也排除：界面上它就是当前页，不需要再出现在列表里。
        val others=relatedOf(html,series.id).filter { it.id!=series.id && seen.add(it.id) }
        return seasons+others
    }
    /**
     * 同一部剧的各季（按季号升序，含正在看的那一季）。取不到时返回空列表。
     *
     * 一次搜索只给约 10 条，而同系列的季数可能有十几季（实测「聚宝仙盆之杂灵根才是真BOSS」
     * 有 96 条同名结果），因此往后多取几页再合并：第 2 页起走的是片库目录扫描，
     * 结果可能与前页重复，这里按 id 去重。取到 [SEASON_PAGE_LIMIT] 页或没有下一页就停。
     */
    fun seasonSiblings(series: Series): List<Series> {
        val base=SeriesSeasons.base(series.title)
        if(base.length<2) return emptyList()
        synchronized(seasonCache) {
            seasonCache[base]?.let { return it }
        }
        val found=SeriesSeasons.siblings(series.title,seasonCandidates(series.title,base))
        synchronized(seasonCache) {
            if(seasonCache.size>=SEASON_CACHE_LIMIT) seasonCache.keys.firstOrNull()?.let(seasonCache::remove)
            seasonCache[base]=found
        }
        return found
    }
    /**
     * 找同系列各季时用到的候选：三条路合并，按 id 去重。
     *
     * 官网搜索**只有 10 条**、而且忽略分页参数（实测 `?page=2/3`、`?page_num`、`?offset`、
     * `?pageNo`、`?p`、`?limit` 全部返回同一批），因此单靠一次搜索召回很有限。
     * 但**换关键词就是换一批**，所以：
     *
     * 1. **沿季号逐季搜**：当前第 N 季 → 搜「第 N+1 季」，命中就继续「第 N+2 季」……
     *    每个季号关键词带回的是不同的那 10 条，中文（`第十四季`）与阿拉伯（`第14季`）各试一次；
     *    连续 [SEASON_MISS_LIMIT] 个季号都没命中就停，上限 [SEASON_SEARCH_LIMIT] 季；
     * 2. **基名与去尾字**：基名本身搜一次（第 1 季的剧名常常就是基名），
     *    再去掉 1~3 个尾字各搜一次（模糊匹配能把被挤出前 10 的同系列捞回来）；
     * 3. **片库目录扫描**：`/category/real-drama?page=N` 每页 24 部、共 34 页（约 800 部），
     *    里面还有被搜索挤掉的那部分同系列——这是唯一能突破「只有 10 条」的入口，
     *    扫到 [SEASON_CATALOG_PAGES] 页或连续两页无新增为止。
     */
    private fun seasonCandidates(title: String,base: String): List<Series> {
        // 串行发十几个请求要一分多钟，因此全部丢进一个小线程池并发跑（4 路），
        // 结果用同步 Map 收集；线程是 daemon，不会拦住进程退出。
        val found=java.util.Collections.synchronizedMap(linkedMapOf<String,Series>())
        fun keep(rows: List<Series>) { rows.forEach { found.putIfAbsent(it.id,it) } }
        val current=SeriesSeasons.season(title)

        // 阶段一（并发）：基名 + 去掉 1~3 个尾字各搜一次，再扫几页片库目录。
        val first= mutableListOf<java.util.concurrent.Callable<Unit>>()
        val keywords=linkedSetOf(base)
        for(cut in 1..SEASON_SEARCH_TRIMS) base.dropLast(cut).takeIf { it.length>=2 }?.let(keywords::add)
        keywords.forEach { keyword -> first += java.util.concurrent.Callable {
            keep(runCatching { search(keyword,1) }.getOrNull()?.items.orEmpty())
        } }
        for(page in 1..SEASON_CATALOG_PAGES) first += java.util.concurrent.Callable {
            keep(runCatching { catalogPage(base,page,ContentType.SHORT).first }.getOrDefault(emptyList()))
        }
        relatedPool.invokeAll(first)

        // 阶段二（并发）：从当前季的**下一季**开始往上逐季搜，命中就继续，
        // 连续 [SEASON_MISS_LIMIT] 个季号都没命中就停；上限 [SEASON_LOOKAHEAD] 个季号。
        // 每个季号中文与阿拉伯两种写法各发一次，两条一起等。
        // 逐个季号搜过去：1 到「当前季 + [SEASON_LOOKAHEAD]」，**并发**发出去。
        // 只往上搜是不够的——当前就是最新一季时（比如第十三季），第 2/9 季这些**更早**的季
        // 只能靠搜它们自己的季号命中；而每个季号关键词带回的 10 条里往往包含好几个季，
        // 逐个搜一遍就能覆盖到。中文写法优先（平台上剧名基本用中文数字），
        // 整轮一个都没命中时才补一轮阿拉伯写法（少见情况，代价可控）。
        val seasons=(1..minOf(current+SEASON_LOOKAHEAD,SEASON_SEARCH_LIMIT)).toList()
        fun searchSeason(label: String): Boolean {
            val rows=runCatching { search(base+label,1) }.getOrNull()?.items.orEmpty()
            keep(rows)
            return rows.any { SeriesSeasons.normalize(it.title)==SeriesSeasons.normalize(title) }
        }
        val chineseHits=relatedPool.invokeAll(seasons.map { season ->
            java.util.concurrent.Callable { searchSeason(SeriesSeasons.labels(season).first()) }
        }).map { runCatching { it.get() }.getOrDefault(false) }
        if(chineseHits.none { it }) {
            relatedPool.invokeAll(seasons.map { season ->
                java.util.concurrent.Callable { searchSeason(SeriesSeasons.labels(season).last()) }
            })
        }
        return found.values.toList()
    }
    /**
     * 取一集的播放模型（`video_model`）。
     *
     * 平台 2026-10-14 又改了一次播放接口：原先带场景段的
     * `/novel/player/multi_video_model/:dr_scene/v1/` 现在一律返回 **HTTP 200 + 空响应体**，
     * 旧路径 `/novel/player/multi_video_model/v1/` 返回 `Code 110001 未知异常`，
     * 表现出来还是「所有影片都放不了」。现在能用的那条是 `/novel/player/video_model/v1/`，
     * 而且**请求体形态也变了**：要 `video_id` 单值，不再是 `mixed_video_id_map`
     *（给 `mixed_video_id_map` / `vid` / `video_ids` 都返回 `100001 video_id invalid`）。
     *
     * 因此按「新路由 → 带场景段 → 旧路径」的顺序试，第一个能取到 `video_model` 的用；
     * 全失败时抛出最后一个错误，界面照常显示「播放接口暂不可用」。
     *
     * **空响应体必须当失败**：网关在业务处理前挡掉请求时就是 `200 + 0 字节`，
     * 这时业务码取不到、默认成 0，不判空就会被当成成功往下走（0.15.0 那次改版栽的就是这个）。
     */
    private fun videoModel(id: String): JSONObject {
        val host="https://api5-normal-sinfonlineb.fqnovel.com"
        val biz="""{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        // 新路由要 `video_id` 单值；带场景段与旧路径要 `mixed_video_id_map`（1004 = 短剧）。
        val videoMap="""{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
        val candidates=listOf(
            "/novel/player/video_model/v1/" to """{"video_id":"$id","biz_param":$biz}""",
            "/novel/player/multi_video_model/preload/v1/" to videoMap,
            "/novel/player/multi_video_model/default/v1/" to videoMap,
            "/novel/player/multi_video_model/detail/v1/" to videoMap,
            "/novel/player/multi_video_model/v1/" to videoMap
        )
        var failure: IOException?=null
        candidates.forEach { (path,body) ->
            val attempt=runCatching {
                val payload=body.toByteArray()
                val signed=signer.sign(host+path,VendorConstants.device,payload)
                val response=text(signed.url,signed)
                // 网关挡掉请求时是 200 + 0 字节：业务码取不到会默认成 0，必须先判空。
                if(response.isBlank()) throw IOException("播放接口暂不可用（空响应）")
                val data=JSONObject(response)
                val code=data.optInt("Code",data.optInt("code",0))
                // 业务码非 0 就是这一条路不通（110001 是旧路径的「未知异常」，100001 是参数错误）。
                if(code!=0) throw IOException("播放接口暂不可用（${code}）")
                videoModelOf(data) ?: throw IOException("播放接口暂不可用（没有播放模型）")
            }
            attempt.getOrNull()?.let { return it }
            failure=attempt.exceptionOrNull() as? IOException ?: IOException(attempt.exceptionOrNull()?.message ?: "播放接口暂不可用")
        }
        throw failure ?: IOException("播放接口暂不可用")
    }
    /**
     * 从播放接口响应里取出 `video_model`：新路由在 `data.video_model`，
     * 带场景段 / 旧路径在 `data[<vid>].video_model`。
     * `video_model` 可能是对象，也可能是**字符串化的 JSON**，两种都要处理。
     */
    private fun videoModelOf(data: JSONObject): JSONObject? {
        val root=data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val entry=root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }
    private fun model(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val model=videoModel(id)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    /**
     * 取一集的弹幕，也就是「原 App」播放时看到的同一份数据。
     *
     * 接口是播放器自己的评论接口：`POST /novel/commentapi/comment/list/{vid}/v1/`，
     * 请求体带 `comment_source=601`（短剧）、`server_channel=1000`、`group_type=30`、`comment_type=20`，
     * `business_param` 里再给剧集 ID 与本集时长；响应按 30 秒一个窗口返回，
     * `extra.next_query_danmaku_list_time` 是下一个窗口的起点，`common_list_info.has_more` 表示还没到片尾。
     *
     * [durationSeconds] 是这一集的时长（秒），用来判断何时停：为 0 时按窗口数量上限兜底。
     * [onWindow] 每拉到一个窗口就被回调一次（传当前已收集的全部弹幕，按时间排好），
     * 让播放界面边拉边画：整集拉完要十几秒，等全量再上屏等于前十几秒一条弹幕都没有。
     * 回调跑在调用线程上，调用方自己负责切回主线程。
     * 网络失败或平台不再返回弹幕时返回空列表，播放界面不显示任何弹幕，也不弹错误。
     */
    fun danmaku(vid: String, seriesId: String, durationSeconds: Long = 0, shouldStop: () -> Boolean = { false }, onWindow: (List<Danmaku>) -> Unit = {}): List<Danmaku> {
        require(vid.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        val durationMs=(durationSeconds.coerceIn(0,4*60*60))*1000
        val collected=linkedMapOf<String,Danmaku>()
        var startMs=0L
        var cursor=""
        // 知道时长就按时长算窗口；不知道时先按上限走——平台的 has_more 会在片尾自己翻成 false，
        // 所以「先多要几个窗口」不会把整部剧拉下来，只是让长剧也能取全。
        val planned=if(durationMs>0) (durationMs+COMMENT_WINDOW_MS-1)/COMMENT_WINDOW_MS+4L else MAX_DANMAKU_WINDOWS.toLong()
        val windows=planned.coerceIn(1L,MAX_DANMAKU_WINDOWS.toLong()).toInt()
        for(index in 0 until windows) {
            if(shouldStop()) break
            val page=runCatching { commentWindow(vid,seriesId,durationMs,startMs,cursor) }.getOrNull() ?: break
            var added=false
            page.items.forEach { if(collected.putIfAbsent("${it.offsetMs}:${it.text}",it)==null) added=true }
            if(added) onWindow(collected.values.sortedBy { it.offsetMs })
            if(collected.size>=MAX_DANMAKU) break
            if(page.nextStart<=startMs) break
            if(durationMs>0 && page.nextStart>=durationMs) break
            if(durationMs<=0 && !page.hasMore) break
            startMs=page.nextStart; cursor=page.cursor
            // 弹幕接口对匿名设备有频控：连发会返回风控错误，窗口之间留一点间隔。
            if(index<windows-1) runCatching { Thread.sleep(COMMENT_THROTTLE_MS) }
        }
        return collected.values.sortedBy { it.offsetMs }
    }
    /**
     * 调试用：一个窗口的原始 `data_list`。
     *
     * 只看接口到底发了什么字段（表情是不是只有文本一种形式），联调表情表时用，界面不调用。
     */
    fun danmakuRawWindow(vid: String,seriesId: String,startMs: Long=0): List<org.json.JSONObject> {
        val page=commentWindow(vid,seriesId,0,startMs,"")
        return page.raw
    }

    /** 一个 30 秒窗口的弹幕；所有线路都失败时抛 IOException，由 [danmaku] 决定是继续还是停下。 */
    private fun commentWindow(vid: String,seriesId: String,durationMs: Long,startMs: Long,cursor: String): DanmakuPage {
        val body=JSONObject()
            .put("comment_source",COMMENT_SOURCE).put("server_channel",SERVER_CHANNEL)
            .put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1)
            .put("business_param",JSONObject().put("book_id",seriesId).put("start_offset_time",startMs)
                .put("playlet_item_duration",durationMs)
                .put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2))))
            .put("count",COMMENT_COUNT).put("cursor",cursor).put("aid",8662).put("compliance_status",0)
            .toString().toByteArray(Charsets.UTF_8)
        var failure: IOException?=null
        for(host in commentHosts) {
            try {
                val signed=signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/",appValues(),body)
                val headers=signed.headers.toMutableMap()
                // 这三个头是弹幕接口专用的，签名与普通接口共用同一套 App 参数。
                headers["comment-source"]=COMMENT_SOURCE.toString()
                headers["server-channel"]=SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,body))
                if(raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
                val rows=payload.optJSONArray("data_list")
                val items=parseDanmakuRows(rows)
                val info=payload.optJSONObject("common_list_info")
                val next=payload.optJSONObject("extra")?.optLong("next_query_danmaku_list_time",-1L)?.takeIf { it>startMs }
                    ?: (startMs+COMMENT_WINDOW_MS)
                return DanmakuPage(items,next,info?.str("cursor").orEmpty(),info?.optBoolean("has_more",false) ?: false,rawDanmakuRows(rows))
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }
    companion object {
        /** 弹幕窗口固定 30 秒一个，与平台的 `next_query_danmaku_list_time` 一致。 */
        private const val COMMENT_WINDOW_MS=30_000L
        private const val COMMENT_SOURCE=601
        private const val SERVER_CHANNEL=1000
        private const val COMMENT_COUNT=90
        /** 一集最多拉多少个窗口（按 30 秒一窗，40 窗 = 20 分钟）。 */
        private const val MAX_DANMAKU_WINDOWS=40
        /** 一集最多保留多少条弹幕：低端电视的绘制预算，超出直接截断。 */
        private const val MAX_DANMAKU=3000
        /** 搜索第 2 页起每次往后扫几页片库目录（一页 24 部）。 */
        private const val SEARCH_SCAN_PAGES=3
        /** 片库目录最多翻到第几页。 */
        private const val MAX_CATALOG_PAGE=100
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        private const val COMMENT_THROTTLE_MS=180L
        /**
         * 相关短剧那段数据：`<script … data-fn-name="mergeLoaderData" data-fn-args="…">`。
         *
         * 参数是 HTML 实体转义过的 JSON 数组 `["detail_page",[{key:"recommendations",…}]]`，
         * 其中 `routerDataFnArgs[0]` 又是一层 JSON 字符串（内容是 `{videoList, recommendationGroups}`）。
         * 任何一步对不上都返回 null——相关短剧只是详情页的附加内容，不能影响详情本身。
         */
        fun extractRecommendations(html: String): JSONObject? = runCatching {
            val marker=html.indexOf("data-fn-name=\"mergeLoaderData\"")
            if(marker<0) return null
            val attr=html.indexOf("data-fn-args=\"",marker)
            if(attr<0) return null
            val start=attr+"data-fn-args=\"".length
            val end=html.indexOf('"',start)
            if(end<=start) return null
            // 结构：["detail_page", [ { key:"recommendations", routerDataFnArgs:["<JSON>"] } ]]
            val outer=JSONArray(unescapeHtml(html.substring(start,end)))
            val entries=outer.optJSONArray(1) ?: return null
            val args=entries.optJSONObject(0)?.optJSONArray("routerDataFnArgs") ?: return null
            val inner=args.optString(0,"")
            if(inner.isBlank()) return null
            JSONObject(inner)
        }.getOrNull()

        /** HTML 属性里的实体反转义：`&quot;` / `&#39;` / `&amp;` 三种在实测页面里出现过。 */
        private fun unescapeHtml(value: String)=value
            .replace("&quot;","\"")
            .replace("&#39;","'")
            .replace("&lt;","<")
            .replace("&gt;",">")
            .replace("&amp;","&")

        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
