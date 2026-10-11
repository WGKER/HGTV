// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.hongguotv.core.Danmaku
import com.hongguotv.core.DanmakuMode

/**
 * 压在播放画面上的弹幕层。
 *
 * 数据来自 [com.hongguotv.core.ContentRepository.danmaku]，就是「原 App」播放时看到的那一份。
 * 这里只负责画：布局（哪一条落在哪一轨）在 [setDanmaku] 里一次性算好，
 * 每帧只按当前播放位置求 x，不再排序、不再测量，低端电视上也能跟上。
 *
 * 三种显示方式由 [DanmakuMode] 决定：
 * - [DanmakuMode.FULL] 全屏弹幕：整幅画面从上到下都是弹幕轨道；
 * - [DanmakuMode.TOP] 上方弹幕：只在屏幕最上面四分之一内滚动，贴着顶边往下铺；
 * - [DanmakuMode.HIDDEN] 隐藏弹幕：整层不可见，也不参与绘制。
 *
 * 位置与播放状态都由宿主（MainActivity）通过 [positionMillis] / [playing] 提供，
 * 弹幕层自己不做时钟，暂停、跳转、倍速都不会和播放器脱节。
 */
internal class DanmakuView(context: Context) : View(context) {
    private companion object {
        /** 一条弹幕从右边完全进画到左边完全出画的时长（秒）。 */
        const val SCROLL_SECONDS=9f
        /** 全屏弹幕：顶部标题条（96dp）之下才开始铺轨道，弹幕不压剧名。 */
        const val TOP_INSET_DP=104
        /** 上方弹幕：整层只占屏幕高度的这一份，其余留白。 */
        const val TOP_MODE_SHARE=0.25f
        /** 上方弹幕离顶边留的安全边距，躲开电视过扫描。 */
        const val TOP_MODE_INSET_DP=20
        /** 底部控制层之上收住，弹幕不压进度条。 */
        const val BOTTOM_INSET_DP=120
        /** 单帧最多画多少条，给低端 GPU 一个上限。 */
        const val MAX_LINES_PER_FRAME=160
        /** 轨道之间再加一点空隙，两行字不会贴在一起。 */
        const val LANE_GAP=1.25f
    }

    /** 一条已经排好轨道的弹幕：起点（秒）、文本宽度、所在轨道。 */
    private class Line(val start: Float,val width: Float,val lane: Int,val text: String)

    private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=Color.WHITE
        typeface=Typeface.DEFAULT_BOLD
    }
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=0xB3000000.toInt()
        style=Paint.Style.STROKE
        typeface=Typeface.DEFAULT_BOLD
        strokeJoin=Paint.Join.ROUND
    }
    private val lines=mutableListOf<Line>()
    /** 这一集拿到的全部弹幕（按时间排好）；换显示方式、换分辨率都从它重排轨道。 */
    private var raw: List<Danmaku> = emptyList()
    /** 当前帧还要画的第一条：按时间排好序，播放推进时只往前走，不回头。 */
    private var cursor=0
    private var lastPosition=-1L
    private var laneCount=0
    private var laneHeight=0f
    private var laneTop=0f
    /** 按屏高算出来的「原始」字号（1 倍缩放）；缩放与透明度都在它上面叠加。 */
    private var baseTextSize=0f
    /** 描边笔的原始不透明度（0xB3 = 70% 黑）。 */
    private val edgeAlpha=0xB3

    var mode: DanmakuMode=DanmakuMode.FULL
        set(value) {
            if(field==value) return
            field=value
            // 轨道数量跟着档位变（上方弹幕只有最上面四分之一），必须整批重排，
            // 否则原来落在下半屏的弹幕会跑到「上方」区域外面去。
            relayout()
        }

    /**
     * 字号缩放（1f = 原始大小）。
     *
     * 字号变了轨道高度也变，必须整批重排：不然行距还是旧字号算出来的，
     * 放大之后两行字会叠在一起、缩小之后轨道之间会空一大截。
     */
    var textScale: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            relayout()
        }

    /**
     * 文字不透明度（1f = 完全不透明）。
     *
     * 只改笔的 alpha，不动字号，因此不用重排轨道，重画一帧就够。
     */
    var textAlpha: Float=1f
        set(value) {
            if(field==value) return
            field=value
            applyTextMetrics()
            invalidate()
        }

    /** 播放位置（毫秒），由宿主每帧提供。 */
    var positionMillis: () -> Long = { 0L }
    /** 是否正在播放：暂停时停在当前帧，不继续推进。 */
    var playing: () -> Boolean = { false }
    init {
        isFocusable=false
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        // 空态用 INVISIBLE 而不是 GONE：GONE 的视图不参与测量，width/height 永远是 0，
        // relayout() 里「宽高齐了才排轨道」的条件就永远不成立，弹幕层会把自己锁死在空态。
        // INVISIBLE 照样被测量、被布局，只是不绘制，取到弹幕时能立刻按真实尺寸排轨道。
        visibility=INVISIBLE
    }

    /** 换集 / 拉到新的一批弹幕：重排轨道并回到第一条。 */
    fun setDanmaku(items: List<Danmaku>) {
        raw=items.sortedBy { it.offsetMs }
        relayout()
    }

    /** 清空这一集的弹幕（换集、切到隐藏档）。 */
    fun clear() {
        raw=emptyList()
        relayout()
    }

    /** 按当前档位与当前尺寸把 [raw] 重新排到轨道上；每次重排都从头开始画。 */
    private fun relayout() {
        lines.clear()
        cursor=0
        lastPosition=-1L
        layoutLanes()
        val lanes=if(mode==DanmakuMode.HIDDEN) 0 else laneCount
        if(lanes>0 && width>0 && height>0) {
            val freeAt=FloatArray(lanes)
            raw.forEach { item ->
                val text=item.text.trim()
                if(text.isNotEmpty()) {
                    var lane=0
                    var free=Float.MAX_VALUE
                    for(index in 0 until lanes) if(freeAt[index]<free) { free=freeAt[index]; lane=index }
                    val start=item.offsetMs/1000f
                    // 一条弹幕占住这条轨道 SCROLL_SECONDS 秒；同轨道上后来的排在它后面。
                    freeAt[lane]=maxOf(freeAt[lane],start)+SCROLL_SECONDS
                    lines+=Line(start,fill.measureText(text),lane,text)
                }
            }
        }
        // 可见性只看「有没有数据」，不看「排没排成轨道」：轨道要等 onSizeChanged 之后才算得出来，
        // 若在这里用 lines.isEmpty() 判空，视图会因为「没尺寸 → 排不出轨道 → 保持不可见 → 不测量」
        // 形成死循环，弹幕接口就算返回几千条也一条都不显示。
        visibility=if(mode==DanmakuMode.HIDDEN || raw.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // 轨道数量按屏高现算：电视上换分辨率、放大字号都会重建视图。
        // 字号按屏高给（1080p 电视约 18sp），再夹住上下限：720p 与 4K 都不会过大或过小。
        val density=context.resources.displayMetrics.density
        baseTextSize=(h*0.034f).coerceIn(density*18f,density*34f)
        applyTextMetrics()
        relayout()
    }

    /**
     * 把「屏高定出的基准字号 + 用户缩放 / 透明度」落到两支笔上。
     *
     * 缩放后的字号仍然夹一次上下限（10dp–72dp）：把 2 倍字号铺到 4K 屏上会盖住大半画面，
     * 0.6 倍铺到 720p 上会小到读不出来，两头都收住。
     * 描边的不透明度跟着文字一起缩：字淡了、描边还全黑，看上去就是一圈黑边浮在画面上。
     */
    private fun applyTextMetrics() {
        val density=context.resources.displayMetrics.density
        val size=(baseTextSize*textScale).coerceIn(density*10f,density*72f)
        fill.textSize=size
        edge.textSize=size
        edge.strokeWidth=size*0.14f
        val alpha=textAlpha.coerceIn(0f,1f)
        fill.alpha=(255*alpha).toInt().coerceIn(0,255)
        edge.alpha=(edgeAlpha*alpha).toInt().coerceIn(0,255)
    }

    /**
     * 重新量一次可用轨道。
     *
     * 全屏弹幕铺满整幅画面，只让开顶部标题条与底部控制层；
     * 上方弹幕则只占屏幕最上面四分之一，并且紧贴顶边开始铺——
     * 留白在下面，弹幕整体往上走，不压画面正中。
     */
    private fun layoutLanes() {
        val density=context.resources.displayMetrics.density
        laneHeight=fill.textSize*LANE_GAP
        val usable=if(mode==DanmakuMode.TOP) {
            laneTop=TOP_MODE_INSET_DP*density
            (height*TOP_MODE_SHARE-laneTop).coerceAtLeast(0f)
        } else {
            laneTop=TOP_INSET_DP*density
            (height-BOTTOM_INSET_DP*density).coerceAtLeast(laneTop+1f)-laneTop
        }
        laneCount=if(laneHeight<=0f) 0 else (usable/laneHeight).toInt().coerceIn(0,24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(mode==DanmakuMode.HIDDEN || lines.isEmpty() || laneCount<=0) return
        val viewWidth=width.toFloat()
        val now=positionMillis()
        val seconds=now/1000f
        // 跳转（前进或后退）之后指针作废，从头找一次；正常播放只往前挪。
        if(now<lastPosition) cursor=0
        lastPosition=now
        while(cursor<lines.size && seconds-lines[cursor].start>SCROLL_SECONDS) cursor++
        var drawn=0
        var index=cursor
        while(index<lines.size && drawn<MAX_LINES_PER_FRAME) {
            val line=lines[index]
            val elapsed=seconds-line.start
            if(elapsed<0f) break
            if(elapsed<=SCROLL_SECONDS) {
                val travel=viewWidth+line.width
                val x=viewWidth-elapsed/SCROLL_SECONDS*travel
                val baseline=laneTop+(line.lane+1)*laneHeight-fill.textSize*0.28f
                canvas.drawText(line.text,x,baseline,edge)
                canvas.drawText(line.text,x,baseline,fill)
                drawn++
            }
            index++
        }
        // 播放中才继续要下一帧：暂停时停在原地，退出播放时视图被移除，循环自然停下。
        if(playing() && visibility==VISIBLE && isAttachedToWindow) postInvalidateOnAnimation()
    }

    /** 播放位置可能被快进，指针要跟着重算；宿主在 seek 之后调一次。 */
    fun resetCursor() { cursor=0; lastPosition=-1L }
}
