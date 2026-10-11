// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.Window

/**
 * 播放器底部的选集面板：贴底、整宽、约 300dp 高，只负责把 [EpisodeGrid]
 * 放进一个透明弹窗里。
 *
 * 内容与简介页的选集共用同一份实现，因此两处的标题、集数行、单元格尺寸、
 * 翻页与跳转行为完全一致；这里不再有第二套网格代码。
 */
internal class EpisodePanel(
    private val activity: Activity,
    private val grid: EpisodeGrid,
    private val closed: () -> Unit
) {
    private val dialog = Dialog(activity)
    private val dp = { value: Number -> activity.tvDp(value) }

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { closed() }
        dialog.setContentView(grid)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(
                activity.resources.displayMetrics.widthPixels,
                dp(300)
            )
        }
        // 弹窗布局完成后再落焦点，保证落在当前集而不是第一格。
        grid.post { grid.focusCurrent() }
    }

    fun dismiss() = dialog.dismiss()
}
