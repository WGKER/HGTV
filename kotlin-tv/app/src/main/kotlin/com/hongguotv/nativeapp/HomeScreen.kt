// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.hongguotv.core.Series

/**
 * 首页内容区：全部由竖版海报架子组成（历史记录 / 我的收藏 / 热门推荐 / 热门发现，加有内容才出现的稍后看）。
 * 每栏最多十部，本机没有记录的架子不占位，由 [render] 过滤空架子。
 *
 * 排版对齐网易爆米花：整页左右安全边距 78dp，一屏 6 张海报、行内间距 14dp，
 * 分区标题就是一行 20sp 粗体 80% 白文字（没有装饰条、没有副标题），
 * 剧名与状态放在卡片下方，因此整页不需要额外的大图预览区。
 * 一行放不下的分区在行末留一张「查看全部」底板：按右键直接进入集合页，点按同样可以进入。
 * 焦点按数据 key 保存，行内横向位置与整页纵向位置分开记忆，返回后原位恢复。
 */
class HomeScreen(
    context: Context,
    private val top: View,
    private val inset: Int,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    private val actions: (Series, View) -> Unit,
    private val remember: (String) -> Unit
) : LinearLayout(context) {
    data class Entry(
        val series: Series,
        val label: String,
        val resume: Boolean = false,
        val progress: Int? = null,
        val time: String = ""
    )

    data class Shelf(
        val key: String,
        val title: String,
        val entries: List<Entry>,
        val more: (() -> Unit)? = null
    )

    data class Position(
        val focus: String,
        val shelf: String?,
        val column: Int,
        val row: Int,
        val vertical: Int,
        val horizontal: Map<String, Int>
    )

    private class Card(val key: String, val view: View, var entry: Entry?) {
        var image: ImageView? = null
        var poster: PosterCard? = null
        var pending = false
        var repaint: (Boolean) -> Unit = {}
    }

    private class Row(
        val key: String,
        val block: LinearLayout,
        val horizontal: HorizontalScrollView,
        val cards: List<Card>,
        val slotWidth: Int
    )

    private fun dp(value: Number) = context.tvDp(value)

    /**
     * 剧架滚动容器：左右各留 [TvStyle.FOCUS_BLEED]。
     * 卡片就贴在容器左边缘，焦点放大后第一张卡的左描边会落到容器外面，
     * 被容器自己裁掉——这就是「第一张卡左边框看不见」的原因。
     */
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        // 容器自己不裁剪：卡片要能画进 focusBleed() 留出来的焦点余量里，
        // 否则第一行 / 最左边那张卡放大后的描边会被切掉。
        // 把滚动内容关在可视区里的裁剪由外面的 tvScrollHost 提供（裁剪边界取的是直接父级）。
        clipChildren = false
        clipToPadding = false
        focusBleed()
        // 滚动位置报给宿主：宿主据此收起 / 展开顶部工具条（首页与二级页同一套行为）。
        // 恢复上次位置的那一次不算，免得一进页面页头就闪一下。
        setOnScrollChangeListener { _, _, y, _, _ -> if (!restoring) onScroll?.invoke(y) }
    }
    /** 滚动容器的外壳：把滚动内容关在可视区里的裁剪由它做（见 TvComponents.tvScrollHost）。 */
    private val scrollHost = context.tvScrollHost(scroll)
    private val contents = LinearLayout(context).apply {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val rows = mutableListOf<Row>()
    private val cards = linkedMapOf<String, Card>()
    private var shelves = emptyList<Shelf>()
    private var footerMore: (() -> Unit)? = null
    private var moreButton: View? = null
    private var lastFocus = ""
    private var lastRow: String? = null
    private var restoring = false
    private var revision = 0

    /** 底部整行刷新入口；文字由调用方按加载状态改写，样式走全站统一的常规按钮。 */
    internal val refresh: TvButton =
        TvButton(context, "刷新热门", ButtonSize.NORMAL, ButtonTone.SECONDARY, action = {}).apply {
            tag = "home:refresh"
            onFocus = { if (it) lastFocus = "home:refresh" }
        }

    /** 剧架滚动时把纵向位置报给宿主：宿主用它决定顶部工具条收起还是展开。 */
    var onScroll: ((Int) -> Unit)? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        scroll.addView(contents)
        // 滚动容器挂在自己的外壳上：外壳裁剪，容器留余量。
        addView(scrollHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    fun capturePosition(): Position {
        val key = findFocus()?.tag?.toString()?.takeIf { it in cards || it == "home:refresh" || it == "home:more" }
            ?: lastFocus
        val row = rows.indexOfFirst { item -> item.cards.any { it.key == key } }
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == key } ?: 0
        return Position(
            key,
            rows.getOrNull(row)?.key,
            column.coerceAtLeast(0),
            row.coerceAtLeast(0),
            scroll.scrollY,
            rows.associate { it.key to it.horizontal.scrollX }
        )
    }

    /** 只重建这一块有界区域，并尽量保留原卡片或它的邻居。 */
    fun updateShelves(next: List<Shelf>, requestFocus: Boolean = hasFocus()) {
        val position = capturePosition()
        render(next, position.focus, requestFocus, footerMore, position)
    }

    /** 不重建视图、不移动焦点的局部更新；结构性变化走 updateShelves。 */
    fun updateEntry(shelfKey: String, entry: Entry): Boolean {
        val card = cards["$shelfKey:${entry.series.id}"] ?: return false
        card.entry = entry
        card.poster?.let { poster ->
            poster.title.text = entry.series.title
            poster.setStatus(entry.label)
            poster.setProgress(entry.progress)
            poster.setCover(entry.series.cover)
        }
        card.view.contentDescription = listOf(
            shelves.firstOrNull { it.key == shelfKey }?.title.orEmpty(),
            entry.series.title,
            entry.label
        ).filter { it.isNotBlank() }.joinToString("，")
        shelves = shelves.map { shelf ->
            if (shelf.key == shelfKey)
                shelf.copy(entries = shelf.entries.map { if (it.series.id == entry.series.id) entry else it })
            else shelf
        }
        loadNearbySelection()
        return true
    }

    /** 页面或生命周期变化取消封面请求后，重新加载可见卡片。 */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(
        shelves: List<Shelf>,
        focus: String,
        requestFocus: Boolean,
        more: (() -> Unit)?,
        position: Position? = null
    ) {
        val previous = position ?: if (rows.isNotEmpty()) capturePosition() else null
        this.shelves = shelves
        footerMore = more
        revision++
        val ticket = revision
        restoring = true
        contents.removeAllViews()
        rows.clear()
        cards.clear()
        moreButton = null
        lastRow = null

        shelves.filter { it.entries.isNotEmpty() || it.more != null }.forEach { shelf ->
            val block = LinearLayout(context).apply {
                orientation = VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            block.addView(
                SectionHeading(
                    context,
                    shelf.title,
                    hint = if (shelf.more != null) "更多 ›" else ""
                ),
                LinearLayout.LayoutParams(-1, -2)
            )
            val horizontal = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                setPadding(dp(2), 0, dp(2), 0)
            }
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
                clipChildren = false
                clipToPadding = false
            }
            horizontal.addView(line)
            block.addView(horizontal, LinearLayout.LayoutParams(-1, -2))
            val slotWidth = slotWidth()
            val rowCards = mutableListOf<Card>()
            shelf.entries.forEach { addCard(shelf, line, rowCards, it, null, ticket, slotWidth) }
            shelf.more?.let { action -> addCard(shelf, line, rowCards, null, action, ticket, slotWidth) }
            contents.addView(
                block,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            )
            rows += Row(shelf.key, block, horizontal, rowCards, slotWidth)
        }

        if (cards.isEmpty())
            contents.addView(
                context.tvText("还没有本机记录，热门剧加载后可选择观看。", 16f, TvStyle.textDim)
                    .apply { setPadding(0, dp(20), 0, dp(20)) },
                LinearLayout.LayoutParams(-1, -2)
            )
        // 底部入口排成一行靠左的胶囊按钮，不再通栏：通栏亮条会压过上面一整屏海报。
        // 两个入口与全站按钮同档（48dp），行内间距与其他按钮行一样取 GAP_INLINE。
        val footerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        contents.addView(footerRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(TvStyle.GAP_GROUP) })
        // refresh 是本实例长期持有的入口按钮，updateShelves() 会让 render() 再跑一次：
        // 必须先用 rehost() 把它从上一行的父级里摘出来，否则第二次重建直接抛 IllegalStateException。
        footerRow.rehost(refresh, LinearLayout.LayoutParams(-2, -2))
        val footer = mutableListOf<View>(refresh)
        if (more != null) {
            val next = TvButton(context, "更多热门", ButtonSize.NORMAL, ButtonTone.SECONDARY) { more() }.apply {
                tag = "home:more"
                onFocus = { if (it) lastFocus = "home:more" }
            }
            footerRow.addView(next, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(TvStyle.GAP_INLINE) })
            footer += next
            moreButton = next
            refresh.nextFocusRightId = next.id
            next.nextFocusLeftId = refresh.id
        }

        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                card.view.nextFocusLeftId = row.cards[(j - 1).coerceAtLeast(0)].view.id
                card.view.nextFocusRightId = row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) top.id
                    else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) refresh.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        footer.forEachIndexed { i, view ->
            view.nextFocusUpId =
                if (i == 0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id
                else footer[i - 1].id
            view.nextFocusDownId = footer.getOrNull(i + 1)?.id ?: view.id
        }
        top.nextFocusDownId = firstId()

        val preferred = focus.ifBlank { previous?.focus.orEmpty() }
        val target = target(preferred, previous)
        if (requestFocus) target.requestFocus()
        post layout@{
            if (revision != ticket) return@layout
            fitArtwork()
            post restore@{
                if (revision != ticket) return@restore
                if (previous != null) restoreOffsets(previous)
                restoring = false
                if (requestFocus && previous == null)
                    rows.firstOrNull { row -> row.cards.any { it.view === target } }?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: Position, requestFocus: Boolean = true) {
        restoring = true
        if (requestFocus) target(position.focus, position).requestFocus()
        post {
            restoreOffsets(position)
            restoring = false
            loadNearbySelection()
        }
    }

    fun firstId() = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: refresh.id

    /** 行内可用宽度 = 屏宽 − 页面左右边距 − 剧架自身左右内边距。 */
    private fun contentWidth() =
        (resources.displayMetrics.widthPixels - 2 * inset - 2 * dp(2)).coerceAtLeast(dp(320))

    /**
     * 与爆米花一致：整行铺满，一屏 6 张竖版海报（约 175×233dp，间距 14dp），
     * 1280dp 画布下与爆米花宫格基本重合。所有分区同一尺寸，行与行之间节奏一致。
     */
    private fun slotWidth(): Int {
        // 卡片自己四周有 SOFT_BAND 的柔光带，行内间距因此收到 10dp，
        // 视觉上的间隔与改版前的 14dp 一致，一行仍然 6 张。
        val gap = dp(10)
        val columns = 6
        return ((contentWidth() - gap * (columns - 1)) / columns).coerceAtLeast(dp(90))
    }

    /**
     * 卡片内封面高度：按 134:196 换算，要先扣掉卡片自身的 5dp 内边距，
     * 否则封面会露出底色。
     */
    private fun slotHeight(width: Int): Int {
        // 扣掉三样东西才轮到封面：卡片自己的柔光带（两侧 SOFT_BAND）+ 海报井的 4dp 内衬。
        val inner = (width - dp(2 * TvStyle.SOFT_BAND + 8)).coerceAtLeast(dp(60))
        return (inner / TvStyle.POSTER_ASPECT).toInt()
    }

    private fun badgeFor(entry: Entry): String {
        val badge = entry.series.badge.trim()
        if (badge.isBlank()) return ""
        return if (badge.length <= 9) badge else badge.take(8) + "…"
    }

    private fun addCard(
        shelf: Shelf,
        line: LinearLayout,
        rowCards: MutableList<Card>,
        entry: Entry?,
        moreAction: (() -> Unit)?,
        ticket: Int,
        slotWidth: Int
    ) {
        val key = if (entry != null) "${shelf.key}:${entry.series.id}" else "${shelf.key}:more"
        // 行末「查看全部」卡与海报同高，否则会把整行撑高、把后续分区挤出屏幕。
        val height = slotHeight(slotWidth)
        val card =
            if (entry == null) moreCard(key, height)
            else posterCard(key, height, entry)
        val view = card.view
        view.tag = key
        view.setOnFocusChangeListener { _, focused ->
            card.poster?.setFocused(focused)
            card.repaint(focused)
            if (focused) {
                lastFocus = key
                remember(key)
                if (!restoring) {
                    loadNearbySelection()
                    if (lastRow != shelf.key)
                        scroll.post { if (revision == ticket && view.hasFocus()) reveal(blockOf(shelf.key)) }
                }
                lastRow = shelf.key
            }
        }
        view.setOnClickListener {
            lastFocus = key
            remember(key)
            card.entry?.let { open(it.series, it.resume) } ?: moreAction?.invoke()
        }
        if (entry != null) {
            view.setOnLongClickListener {
                lastFocus = key
                remember(key)
                card.entry?.let { actions(it.series, view) }
                true
            }
            view.setOnKeyListener { target, code, event ->
                when {
                    code == KeyEvent.KEYCODE_MENU -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            lastFocus = key
                            remember(key)
                            card.entry?.let { actions(it.series, target) }
                        }
                        true
                    }
                    // 行末再按右键直接进集合页，不必先把焦点移到「查看全部」底板再确认。
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0 && rowCards.lastOrNull { it.entry != null } === card ->
                        shelves.firstOrNull { it.key == shelf.key }?.more?.let { more ->
                            lastFocus = key
                            remember(key)
                            more()
                            true
                        } ?: false
                    else -> false
                }
            }
        }
        line.addView(view, LinearLayout.LayoutParams(slotWidth, -2).apply { rightMargin = dp(10) })
        rowCards += card
        cards[key] = card
    }

    private fun blockOf(shelfKey: String): View =
        rows.firstOrNull { it.key == shelfKey }?.block ?: contents

    /** 行末「查看全部」卡：与海报卡同一套材质（凸起底板 + 焦点抬升），只是里面没有封面。 */
    private fun moreCard(key: String, height: Int): Card {
        val band = context.softBand()
        val plate = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            id = View.generateViewId()
            clipChildren = false
            clipToPadding = false
            setPadding(band, band, band, band)
        }
        val stack = context.tvColumn().apply { gravity = Gravity.CENTER }
        stack.addView(context.tvText("›", 44f, TvStyle.textDim, bold = true))
        stack.addView(
            context.tvText("查看全部", 14f, TvStyle.textDim),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }
        )
        plate.addView(stack, FrameLayout.LayoutParams(-1, height, Gravity.CENTER))
        paintMore(plate, false)
        return Card(key, plate, null).apply {
            repaint = { focused -> paintMore(plate, focused) }
        }
    }

    /**
     * 「查看全部」底板：常态凸起面，焦点换成抬升面 + 更深的柔光 + 光晕（与海报卡同一套）。
     * 2026-10-07 主题改版之后焦点不再画强调色细环，靠材质自己的凹凸表达。
     */
    private fun paintMore(plate: FrameLayout, focused: Boolean) {
        plate.background = TvStyle.soft(
            context,
            if (focused) TvStyle.surfaceHigh else TvStyle.surface,
            TvStyle.RADIUS_CARD,
            TvStyle.SOFT_BAND,
            SoftStyle.RAISED,
            glow = if (focused) TvStyle.litGlow else Color.TRANSPARENT,
            depth = if (focused) 1.35f else 1f
        )
        TvStyle.focus(plate, focused)
    }

    private fun posterCard(key: String, height: Int, entry: Entry): Card {
        val poster = PosterCard(context, height, loadCover)
        poster.bind(entry.series, entry.label, badgeFor(entry), entry.progress, false)
        return Card(key, poster, entry).apply {
            this.poster = poster
            image = poster.image
        }
    }

    private fun target(key: String, position: Position?): View {
        if (key == "home:refresh") return refresh
        if (key == "home:more") moreButton?.let { return it }
        cards[key]?.let { return it.view }
        if (position != null) {
            val row = rows.firstOrNull { it.key == position.shelf }
                ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let { return it.view }
        }
        return cards.values.firstOrNull()?.view ?: refresh
    }

    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0, 0) }
        scroll.scrollTo(0, position.vertical)
        // 被移除的分区可能把最近的幸存卡片留在旧视口之外。
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }

    private fun revealSelection(row: Row) {
        row.cards.firstOrNull { it.view.hasFocus() }?.view?.let { card ->
            val horizontal = row.horizontal
            val left = horizontal.scrollX
            val right = left + horizontal.width - horizontal.paddingRight
            val target = when {
                card.left < left -> card.left
                card.right > right -> card.right - horizontal.width + horizontal.paddingRight
                else -> left
            }
            if (target != left) horizontal.scrollTo(target.coerceAtLeast(0), 0)
        }
        reveal(row.block, false)
    }

    private fun reveal(block: View, smooth: Boolean = true) {
        val target = if (smooth) block.top else when {
            block.top < scroll.scrollY -> block.top
            block.bottom > scroll.scrollY + scroll.height ->
                if (block.height >= scroll.height) block.top else block.bottom - scroll.height
            else -> scroll.scrollY
        }.coerceAtLeast(0)
        if (target != scroll.scrollY) {
            if (smooth) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
        }
    }

    private fun loadNearbySelection() {
        if (restoring) return
        val row = rows.indexOfFirst { it.cards.any { card -> card.key == lastFocus } }.coerceAtLeast(0)
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items ->
            for (index in (column - 1).coerceAtLeast(0)..(column + 6).coerceAtMost(items.lastIndex))
                requestCover(items[index])
        }
        rows.getOrNull(row + 1)?.cards?.take(6)?.forEach(::requestCover)
    }

    private fun requestCover(card: Card) {
        val image = card.image ?: return
        if (card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket = revision
        fun start() {
            card.pending = false
            if (ticket != revision || !image.isAttachedToWindow || image.width <= 0 || image.height <= 0) return
            loadCover(image, card.entry!!.series.cover)
        }
        if (image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending = true
            image.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    image.removeOnLayoutChangeListener(this)
                    start()
                }
            })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { reloadArtwork() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 只有宽度变化才重新排版封面：高度变化来自顶部工具条收展，跟着重算会让整屏海报抖一下。
        if (w != oldw) post {
            fitArtwork()
            loadNearbySelection()
        }
    }

    /** 屏高不足时按可用高度收窄卡片，保证「整行 + 分区标题」始终完整可见。 */
    private fun fitArtwork() {
        if (height <= 0) return
        rows.forEach { row ->
            val heading = row.block.getChildAt(0).measuredHeight
            val available = (height - heading - dp(58 + 2 * TvStyle.SOFT_BAND + 8) - dp(24)).coerceAtLeast(dp(110))
            val target = minOf(slotHeight(row.slotWidth), available)
            row.cards.forEach { card ->
                card.poster?.resizePoster(target)
            }
        }
    }
}
