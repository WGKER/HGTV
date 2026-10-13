// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 应用内唯一的模态框：标题 + 内容 + 一排 [TvButton]。
 *
 * 之前所有弹窗都交给系统 AlertDialog，按钮是 Material 的文字按钮——高度、圆角、焦点反馈
 * 和页面上的胶囊按钮都不是一套，看起来像另一个应用。这里把弹窗内容做成普通 View，
 * 按钮直接复用全站按钮组件，因此模态框内外完全一致。
 *
 * 用法：
 * ```
 * TvDialog(this, "删除这条记录")
 *     .message("删除《…》的本机观看进度？收藏会保留。")
 *     .button("取消") {}
 *     .button("确认") { library.removeHistory(id) }
 *     .show(anchor)
 * ```
 * 列表型弹窗用 [choices]：每项都是整行左对齐的常规按钮，超过半屏由 [ScrollView] 接管。
 * 三个区块（内容 / 列表 / 按钮行）只有真正有东西时才占位，空着不留间距。
 */
internal class TvDialog(private val activity: Activity, title: String) {

    private val dp = { value: Number -> activity.tvDp(value) }
    /** 弹窗面板的柔光带：面板比窗口边界内缩这么多，投影 / 高光画在这条带里。 */
    private val band = activity.softBandLarge()
    // 容器比内容早 FOCUS_BLEED 开始：列表项 / 按钮行放大后的焦点底色才有地方画；
    // 再加上面板的柔光带，弹窗内容仍然落在同一条 24dp 内容线上。
    // 标题、内容、按钮行各自补回 FOCUS_BLEED，因此弹窗里所有元素对齐。
    private val body = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
        val inset = dp(TvStyle.GAP_GROUP)
        val bleed = dp(TvStyle.FOCUS_BLEED)
        setPadding(inset - bleed + band, inset + band, inset - bleed + band, inset + band)
    }

    /** 调用方可以直接往里塞二维码、复选框这类自定义内容。 */
    val content: LinearLayout = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
    }

    private val items = activity.tvColumn().apply {
        clipChildren = false
        clipToPadding = false
    }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        visibility = View.GONE
        focusBleed()
    }
    /** 列表滚动的外壳：裁剪由它做，列表项放大后的底色才能画进容器自己的焦点余量里。 */
    private val listHost = activity.tvScrollHost(scroll)
    private val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val buttons = mutableListOf<TvButton>()
    private var listHeight = 0
    private var dialog: AlertDialog? = null
    private var dismissed: (() -> Unit)? = null
    private var anchor: View? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    init {
        body.addView(
            activity.tvText(title, 22f, TvStyle.textStrong, bold = true)
                .apply { setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0) }
        )
        content.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        body.addView(content, LinearLayout.LayoutParams(-1, -2))
        scroll.addView(items)
        body.addView(listHost, LinearLayout.LayoutParams(-1, -2))
        actions.setPadding(dp(TvStyle.FOCUS_BLEED), 0, dp(TvStyle.FOCUS_BLEED), 0)
        body.addView(actions, LinearLayout.LayoutParams(-1, -2))
        // 内容区可能在建好之后才被填（二维码、复选框），因此间距跟着子控件数量走。
        content.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val column = view as? LinearLayout ?: return@addOnLayoutChangeListener
            val params = column.layoutParams as? LinearLayout.LayoutParams ?: return@addOnLayoutChangeListener
            val wanted = if (column.childCount == 0) 0 else dp(TvStyle.GAP_BLOCK)
            if (params.topMargin != wanted) {
                params.topMargin = wanted
                column.layoutParams = params
            }
        }
    }

    /** 一段说明文字。多段就多调一次，行距与行高由同一套令牌决定。 */
    fun message(text: String, size: Float = 16f, color: Int = TvStyle.textDim): TvDialog {
        content.addView(
            activity.tvText(text, size, color).apply { setLineSpacing(dp(TvStyle.GAP_TIGHT).toFloat(), 1f) },
            LinearLayout.LayoutParams(-1, -2).apply { if (content.childCount > 0) topMargin = dp(TvStyle.GAP_INLINE) }
        )
        return this
    }

    /** 列表型选项：整行左对齐的常规按钮，[selected] 的那一项用强调色标出。 */
    fun choices(labels: List<String>, selected: Int = -1, onChoice: (Int) -> Unit): TvDialog {
        labels.forEachIndexed { index, label ->
            val item = TvButton(activity, label, ButtonSize.NORMAL, ButtonTone.SECONDARY, alignStart = true, action = {
                dismiss()
                onChoice(index)
            })
            item.setSelectedState(index == selected)
            item.contentDescription = label + if (index == selected) "，当前选择" else ""
            items.addView(
                item,
                LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = dp(TvStyle.GAP_INLINE) }
            )
        }
        if (labels.isNotEmpty()) {
            scroll.visibility = View.VISIBLE
            (listHost.layoutParams as LinearLayout.LayoutParams).topMargin = dp(TvStyle.GAP_BLOCK)
            // 列表滚动的可视高度要把滚动容器自身的焦点余量（上下各 FOCUS_BLEED）一起算进去，
            // 否则第一项 / 最后一项选中时的底色会被列表裁掉一条边。
            // 每一项都是常规档按钮，高度含柔光带；间距按 GAP_INLINE + 两条柔光带算，
            // 否则列表高度会低估，第一项 / 最后一项的柔光与焦点环会被列表裁掉。
            val itemHeight = dp(TvStyle.BUTTON_NORMAL + 2 * TvStyle.SOFT_BAND)
            val itemGap = dp(TvStyle.GAP_INLINE + 2 * TvStyle.SOFT_BAND)
            listHeight = labels.size * itemHeight + (labels.size - 1) * itemGap +
                2 * dp(TvStyle.FOCUS_BLEED_VERTICAL)
        }
        return this
    }

    /**
     * 底部动作按钮。默认点完就关（[keepOpen] 用于「检查更新」这种点完还要留在框里刷新状态的）。
     * 返回按钮本身，调用方可以后续改文案或禁用。
     */
    fun button(label: String, tone: ButtonTone = ButtonTone.SECONDARY, keepOpen: Boolean = false, run: () -> Unit = {}): TvButton {
        val view = TvButton(activity, label, ButtonSize.NORMAL, tone, action = {
            if (!keepOpen) dismiss()
            run()
        })
        actions.addView(
            view,
            LinearLayout.LayoutParams(-2, -2).apply { if (actions.childCount > 0) leftMargin = dp(TvStyle.GAP_INLINE) }
        )
        if (actions.childCount == 1) (actions.layoutParams as LinearLayout.LayoutParams).topMargin = dp(TvStyle.GAP_GROUP)
        buttons += view
        return view
    }

    fun show(anchor: View? = null, onDismiss: () -> Unit = {}) {
        if (isShowing) return
        this.anchor = anchor
        dismissed = onDismiss
        val metrics = activity.resources.displayMetrics
        val next = AlertDialog.Builder(activity).setView(body).create()
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                val done = dismissed
                dismissed = null
                done?.invoke()
            }
            if (anchor?.isAttachedToWindow == true) anchor.requestFocus()
        }
        dialog = next
        next.show()
        next.window?.apply {
            // 弹窗就是一块大号凸起面板：圆角 24dp、柔光带 9dp、投影更深一档。
            setBackgroundDrawable(
                TvStyle.soft(
                    activity, TvStyle.surfaceHigh, TvStyle.RADIUS_DIALOG, TvStyle.SOFT_BAND_LARGE,
                    SoftStyle.RAISED, depth = 1.3f
                )
            )
            // 背景压暗是柔和的：新拟态靠同色明度差表达层次，压得太黑面板就看不出是浮层了。
            setDimAmount(.66f)
            setLayout(
                (metrics.widthPixels * .58f).toInt().coerceIn(dp(420), dp(860)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 列表超过半屏就滚动，避免弹窗被长列表顶出画面。
        if (listHeight > 0) {
            val limit = (metrics.heightPixels * .56f).toInt()
            listHost.layoutParams = listHost.layoutParams.apply { height = listHeight.coerceAtMost(limit) }
        }
        // 焦点落在第一项（列表优先），因此「确认」永远不会是默认落点，误触不会造成破坏。
        body.post {
            if (!isShowing) return@post
            (if (items.childCount > 0) items.getChildAt(0) else buttons.firstOrNull())?.requestFocus()
        }
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
