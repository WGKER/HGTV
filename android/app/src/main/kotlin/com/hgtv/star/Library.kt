// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// The backup and native library use the same immutable progress shape.
typealias WatchProgress = BackupProgress

/** Memory-only UI access; disk reads, JSON serialization and commits run on workers. */
class Library(context: Context) {
    private companion object {
        // A replacement Activity must not load before its predecessor's final checkpoint commits.
        val retiredWrites = mutableListOf<LatestWriteQueue<String, () -> Any?>>()
        /**
         * 静态列表只保留最近几次关闭的队列。
         * 它存在的意义只是「下一个 Activity 启动时等上一批提交落盘」，
         * 不设上限就会随 Activity 重建次数一直增长（每条队列还带着自己的线程与待写快照）。
         */
        const val RETIRED_LIMIT = 4
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "hgtv-library-load") }
    private val prefs by lazy { app.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE) }
    private val homePrefs by lazy { app.getSharedPreferences("native-home-cache-v1", Context.MODE_PRIVATE) }
    /** 搜索登录态单独存一个文件：它不是播放偏好，也不进备份文件、不随记录一起迁移。 */
    private val sessionPrefs by lazy { app.getSharedPreferences("native-session-v1", Context.MODE_PRIVATE) }
    private val writes = LatestWriteQueue<String, () -> Any?>("hgtv-library-write", write = { batch -> commit(prefs, batch) },
        failed = { Log.w("HGTV", "Local library commit failed; pending changes retained for retry") })
    private val homeWrites = LatestWriteQueue<String, () -> Any?>("hgtv-home-write", write = { batch -> commit(homePrefs, batch) },
        failed = { Log.w("HGTV", "Home cache commit failed") })
    private var index: LibraryIndex? = null
    /**
     * 首页四个「热播…」剧架的缓存，按榜单分别存一份（[RankBoard]）。
     * 首页首屏先铺缓存、再后台更新，用户不必等四个榜单都回来才看到东西。
     */
    private val homes = mutableMapOf<RankBoard, CatalogPage>()
    private val homeSavedAt = mutableMapOf<RankBoard, Long>()
    private var loading = false
    private var closed = false
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val errorCallbacks = mutableListOf<(Throwable) -> Unit>()
    val isLoaded get() = index != null

    /** Call before showing the first page. Successful/error callbacks run on the main thread. */
    fun loadAsync(onFailure: (Throwable) -> Unit = { Log.e("HGTV", "Local library failed to load", it) }, onReady: () -> Unit) {
        check(!closed)
        if (isLoaded) { onReady(); return }
        readyCallbacks += onReady
        errorCallbacks += onFailure
        if (loading) return
        loading = true
        loader.execute {
            val result = runCatching {
                val retired = synchronized(retiredWrites) { retiredWrites.toList() }
                retired.forEach { check(it.awaitClosed(30, TimeUnit.SECONDS)) { "上次观看记录仍在保存，请稍后重试" } }
                synchronized(retiredWrites) { retiredWrites.removeAll(retired.toSet()) }
                val values = prefs.all
                val loaded = LibraryDisk.decode(values)
                val now = System.currentTimeMillis()
                val cached = mutableMapOf<RankBoard, CatalogPage>()
                val stamps = mutableMapOf<RankBoard, Long>()
                val migration = homePrefs.edit()
                val oldKeys = mutableListOf<String>()
                RankBoard.entries.forEach { board ->
                    val key = "home-${board.storedValue}"
                    val legacy = values[key] as? String
                    val raw = homePrefs.getString(key, null) ?: legacy.orEmpty()
                    CatalogSnapshot.decode(raw, now)?.let { page ->
                        cached[board] = page
                        stamps[board] = org.json.JSONObject(raw).getLong("savedAt")
                        if (!homePrefs.contains(key)) migration.putString(key, raw)
                    }
                    if (legacy != null) oldKeys += key
                }
                // 首页缓存按榜单分开之后，旧版留下的键（`home-short` 这种）永远不会再被读到：
                // 一起清掉，别让一份不再更新的快照一直躺在磁盘上。
                val liveKeys = RankBoard.entries.map { "home-${it.storedValue}" }.toSet()
                homePrefs.all.keys.filter { it.startsWith("home-") && it !in liveKeys }.forEach(migration::remove)
                // Only remove the legacy copies after the separate cache has been committed.
                val committed = migration.commit()
                if (committed && oldKeys.isNotEmpty()) {
                    prefs.edit().also { edit -> oldKeys.forEach(edit::remove) }.commit()
                }
                Triple(loaded, cached, stamps)
            }
            main.post {
                loading = false
                if (closed) { readyCallbacks.clear(); errorCallbacks.clear(); return@post }
                val ready = readyCallbacks.toList(); readyCallbacks.clear()
                val errors = errorCallbacks.toList(); errorCallbacks.clear()
                result.onSuccess { (loaded, cached, stamps) ->
                    index = loaded; homes.putAll(cached); homeSavedAt.putAll(stamps)
                    ready.forEach { it() }
                }.onFailure { failure -> errors.forEach { it(failure) } }
            }
        }
    }
    private fun state(): LibraryIndex = checkNotNull(index) { "Load the local library before accessing it" }
    private fun put(key: String, value: Any?) { writes.submit(mapOf(key to { value })) }
    private fun persist(vararg keys: String) {
        val state = state()
        val batch = linkedMapOf<String, () -> Any?>()
        keys.forEach { key ->
            // Each lambda captures an immutable snapshot; JSON is built by the serial writer.
            batch[key] = when (key) {
                "favorites" -> state.favorites().let { rows -> { LibraryDisk.series(rows) } }
                "later" -> state.later().let { rows -> { LibraryDisk.series(rows) } }
                "progress" -> state.history().let { rows -> { LibraryDisk.history(rows) } }
                "favoriteUpdates" -> state.updates().let { rows -> { LibraryDisk.updates(rows) } }
                "searches" -> state.searches().let { rows -> { SearchHistory.encode(rows) } }
                "watched" -> state.watched().let { ids -> { ids } }
                "hidden" -> state.hidden().let { ids -> { ids } }
                else -> error("Unknown library partition")
            }
        }
        writes.submit(batch)
    }
    fun favorites() = state().favorites()
    fun favorite(id: String) = state().favorite(id)
    fun later() = state().later()
    fun queued(id: String) = state().queued(id)
    fun history(): List<WatchProgress> = state().history()
    fun progress(id: String): WatchProgress? = state().progress(id)
    fun searches() = state().searches()
    fun watched(id: String) = state().watched(id)
    fun hidden() = state().hidden()
    fun favoriteUpdate(id: String) = state().favoriteUpdate(id)
    fun updatedFavorites() = state().updatedFavorites()
    fun favoriteLabel(id: String): String = favoriteUpdate(id)?.let {
        if (it.added > 0) "新增 ${it.added} 集 · 更新至 ${it.total} 集" else "更新至 ${it.total} 集"
    } ?: "尚未检查更新"
    fun toggle(series: Series) = state().toggle(series).also { persist("favorites", "favoriteUpdates") }
    fun toggleLater(series: Series) = state().toggleLater(series).also { persist("later") }
    fun save(progress: WatchProgress) { if (state().save(progress)) persist("progress") }
    fun setWatched(id: String, value: Boolean) { if (state().setWatched(id, value)) persist("watched") }
    fun hide(id: String) { state().hide(id); persist("hidden") }
    fun unhideAll() { state().unhideAll(); persist("hidden") }
    fun rememberSearch(query: String) { state().rememberSearch(query); persist("searches") }
    fun clearSearches() { state().clearSearches(); persist("searches") }
    fun removeHistory(id: String) { state().removeHistory(id); persist("progress", "watched") }
    fun clearHistory() { state().clearHistory(); persist("progress", "watched") }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean = false) {
        if (state().observeFavorite(id, count, now, acknowledge)) persist("favoriteUpdates")
    }
    fun cachedHome(board: RankBoard): CatalogPage? {
        state()
        val age = System.currentTimeMillis() - (homeSavedAt[board] ?: return null)
        return homes[board].takeIf { age in 0..7 * 24 * 60 * 60 * 1000L }
    }
    fun cacheHome(board: RankBoard, items: List<Series>, hasMore: Boolean) {
        state()
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val retained = items.take(30).map { it.copy(description = it.description.take(1500)) }
        homes[board] = CatalogPage(retained, hasMore)
        homeSavedAt[board] = now
        homeWrites.submit(mapOf("home-${board.storedValue}" to { CatalogSnapshot.encode(retained, hasMore, now) }))
    }
    var maxQuality: Int
        get() = state().settings.quality
        set(value) { val next = PlaybackQuality.normalize(value); if (next != maxQuality) { state().settings = state().settings.copy(quality = next); put("quality", next) } }
    /**
     * 搜索登录态（可选，App 抓到的 `x-tt-token`，整段 Cookie 也可以）。
     *
     * 不填也能搜索（退回官网搜索 + 片库目录补页）；填上后搜索直接使用 App 搜索接口。
     * 它只用于搜索请求，不进备份文件，也不参与「手机备份与恢复」。
     */
    var searchToken: String
        get() = sessionPrefs.getString("x-tt-token", "").orEmpty()
        set(value) { sessionPrefs.edit().putString("x-tt-token", value.trim()).apply() }
    /**
     * 分类页当前选中的小类（`CategoryCatalog` 里的路径）。只影响分类页的默认选择，
     * 不参与备份与恢复（和播放偏好不同，它是一处浏览位置）。
     */
    var categoryPath: String
        get() = prefs.getString("category-path", "").orEmpty()
        set(value) { prefs.edit().putString("category-path", value.trim()).apply() }
    var autoNext: Boolean
        get() = state().settings.autoNext
        set(value) { if (value != autoNext) { state().settings = state().settings.copy(autoNext = value); put("autoNext", value) } }
    var playbackSpeed: Float
        get() = state().settings.speed
        set(value) { val next = PlaybackSpeed.normalize(value); if (next != playbackSpeed) { state().settings = state().settings.copy(speed = next); put("playbackSpeed", next) } }
    var frameMode: VideoFrameMode
        get() = state().settings.frame
        set(value) { if (value != frameMode) { state().settings = state().settings.copy(frame = value); put("frameMode", value.name) } }
    var danmakuMode: DanmakuMode
        get() = state().settings.danmaku
        set(value) { if (value != danmakuMode) { state().settings = state().settings.copy(danmaku = value); put("danmakuMode", value.storedValue) } }
    /** 弹幕字号缩放：1f = 原始大小，滑动条按 [DanmakuStyle.SCALE_STEP] 增减。 */
    var danmakuScale: Float
        get() = state().settings.danmakuScale
        set(value) { val next = DanmakuStyle.scale(value); if (next != danmakuScale) { state().settings = state().settings.copy(danmakuScale = next); put("danmakuScale", next) } }
    /** 弹幕文字不透明度：1f = 完全不透明，最低 [DanmakuStyle.ALPHA_MIN]。 */
    var danmakuAlpha: Float
        get() = state().settings.danmakuAlpha
        set(value) { val next = DanmakuStyle.alpha(value); if (next != danmakuAlpha) { state().settings = state().settings.copy(danmakuAlpha = next); put("danmakuAlpha", next) } }
    fun snapshot() = state().snapshot()
    fun restore(incoming: BackupData, restoreSettings: Boolean) {
        val merged = LibraryBackup.merge(snapshot(), incoming, restoreSettings)
        index = LibraryIndex(merged, state().updates())
        persist("favorites", "later", "progress", "watched", "hidden", "searches", "favoriteUpdates")
        writes.submit(mapOf("quality" to { merged.settings.quality }, "playbackSpeed" to { merged.settings.speed },
            "autoNext" to { merged.settings.autoNext },
            "frameMode" to { merged.settings.frame.name }, "danmakuMode" to { merged.settings.danmaku.storedValue },
            "danmakuScale" to { merged.settings.danmakuScale }, "danmakuAlpha" to { merged.settings.danmakuAlpha }))
        flush()
    }
    /** Requests prompt background commit, without lifecycle-thread disk waits. */
    fun flush() { writes.flush(); homeWrites.flush() }
    /** Drain pending commits after Activity destruction; abrupt process death is still a boundary. */
    fun close() {
        if (closed) return
        closed = true
        readyCallbacks.clear(); errorCallbacks.clear()
        loader.shutdown()
        synchronized(retiredWrites) {
            writes.close(); homeWrites.close()
            retiredWrites += writes; retiredWrites += homeWrites
            while (retiredWrites.size > RETIRED_LIMIT) retiredWrites.removeAt(0)
        }
    }
    private fun commit(target: SharedPreferences, values: Map<String, () -> Any?>): Boolean {
        val edit = target.edit()
        values.forEach { (key, encode) ->
            when (val value = encode()) {
                null -> edit.remove(key)
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Float -> edit.putFloat(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported library value")
            }
        }
        return edit.commit()
    }
}
