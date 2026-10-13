// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * 一条弹幕。
 *
 * 数据来自红果 / 番茄播放器自己的评论接口
 * `POST /novel/commentapi/comment/list/{vid}/v1/`（`comment_source=601`、`server_channel=1000`），
 * 与「原 App」播放时看到的是同一份数据：
 * 文本在 `comment.common.content.text`，时间点在 `comment.expand.offset_time`（毫秒），
 * 点赞数在 `comment.stat.digg_count`。
 */
data class Danmaku(val offsetMs: Long, val text: String, val diggCount: Int)

/**
 * 解析弹幕接口一个窗口里的 `data.data_list`。
 *
 * 每一条的结构是 `comment.common.content.text`（文本）、`comment.expand.offset_time`（毫秒）、
 * `comment.stat.digg_count`（点赞）。缺字段、空文本或时间点为负的条目直接跳过——
 * 平台上确实存在这类残缺记录，宁可少一条也不要把 0 毫秒的弹幕糊在片头。
 */
internal fun parseDanmakuRows(rows: org.json.JSONArray?): List<Danmaku> =
    rawDanmakuRows(rows).mapNotNull { row ->
        val comment=row.optJSONObject("comment") ?: return@mapNotNull null
        val text=comment.optJSONObject("common")?.optJSONObject("content")?.let { if(it.isNull("text")) "" else it.optString("text","") }.orEmpty().trim()
        val offset=comment.optJSONObject("expand")?.optLong("offset_time",-1L) ?: -1L
        if(text.isEmpty() || offset<0) null
        else Danmaku(offset,text,comment.optJSONObject("stat")?.optInt("digg_count",0) ?: 0)
    }

/** 一个窗口里的一行原始记录（含 `comment` 那一层）。调试表情表时用它看接口发了什么。 */
internal fun rawDanmakuRows(rows: org.json.JSONArray?): List<org.json.JSONObject> =
    (0 until (rows?.length() ?: 0)).mapNotNull { index -> rows?.optJSONObject(index) }

/** 弹幕在播放画面上的显示方式。存进本机设置，换集与重开应用都保持。 */
enum class DanmakuMode(val storedValue: String, val label: String) {
    FULL("full","全屏弹幕"),
    TOP("top","上方弹幕"),
    HIDDEN("hidden","隐藏弹幕");

    /**
     * 这一档下弹幕文字的外观（字号 / 透明度）还有没有意义。
     *
     * 隐藏档整层不画、也不发请求，字号与透明度改完看不到任何变化，因此设置页与播放设置面板
     * 都要把这两项收起来——留着一排调不出效果的滑动条，只会让人以为功能坏了。
     * 判定放在这里而不是各页面各写一次：两处必须同时收同时放，规则只能有一份。
     */
    val showsStyle get() = this != HIDDEN

    companion object { fun fromStored(value: String?) = entries.firstOrNull { it.storedValue==value } ?: FULL }
}

/**
 * 弹幕文字的外观偏好：字号缩放与不透明度。
 *
 * 这两项是**连续量**（播放器设置面板里用滑动条调），不是离散档位，因此取值一律经过
 * [scale] / [alpha] 归一：先吸附到 0.1 的整数倍，再夹进上下限，最后抹掉浮点误差。
 * 归一放在 core 里，是为了让「面板上滑到 130%」和「从备份里读回 130%」得到的是同一个数，
 * 不会出现 1.3000001 这种值在面板上显示 130%、写盘却和 130% 不相等的情况。
 */
object DanmakuStyle {
    const val SCALE_MIN=0.6f
    const val SCALE_MAX=2f
    const val SCALE_STEP=0.1f
    const val ALPHA_MIN=0.2f
    const val ALPHA_MAX=1f
    const val ALPHA_STEP=0.1f

    fun scale(value: Float)=snap(value,SCALE_MIN,SCALE_MAX,SCALE_STEP)
    fun alpha(value: Float)=snap(value,ALPHA_MIN,ALPHA_MAX,ALPHA_STEP)

    /** 面板上的读数：`0.9f → "90%"`。字号与透明度共用同一套百分比显示。 */
    fun percent(value: Float)="${Math.round(value*100)}%"

    private fun snap(value: Float,min: Float,max: Float,step: Float): Float {
        // 非法值（NaN / 无穷）落回上限：读坏数据时宁可回到「100%」这一档。
        if(!value.isFinite()) return max
        val snapped=Math.round((value-min)/step)*step+min
        return (Math.round(snapped*100f)/100f).coerceIn(min,max)
    }
}
