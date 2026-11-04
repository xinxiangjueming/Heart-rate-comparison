package com.example.heartratecomparison.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 圆形容器裁剪 —— 「从历史图标圆扩散到整页」一镜到底的绘制核心
 * （对齐 sportlink ui/ClipRevealLayout 的圆形锚点分支，2026-11-04 移植）。
 *
 * 原理：动画期间把自身整体绘制裁剪到 [setCircleClip] 给出的圆内 —— 圆从历史图标
 * 的本体大小向外扩散，最终覆盖全屏。页面内容始终按全尺寸布局、钉死在屏幕坐标上，
 * 只是可见范围变化，**不做 scale / translation**（scaleY 会把整页文字压扁）。
 *
 * 双层裁剪（sportlink 在 HyperOS 上实测过的硬约束）：
 * 1. [clipToOutline] + [ViewOutlineProvider]：RenderNode 级 GPU 抗锯齿圆裁剪。
 *    `Outline.setCircle` 是 API 30+，本项目 minSdk 26 不满足，因此用「正方形
 *    setRoundRect + 半径 = 半边长」等效圆形，半径可大于屏幕尺寸（扩散末态合法）。
 * 2. [draw] 里 canvas.clipPath 兜底：个别 ROM 对「小于自身 bounds 的 outline 子矩形」
 *    行为异常时仍能保持圆形窗口，两层取交集，不穿帮。
 *
 * **稳态绝不能把 outline 置空**（sportlink 2026-09-25 真机实锤）：clipToOutline=true
 * 时空 outline 会把整页裁没，表现为详情页消失/点击被吞。因此稳态 outline 给全屏
 * 矩形，且 [clearClip] 直接关闭 clipToOutline，稳态与无裁剪完全一致。
 *
 * 绘制层次：容器背景（历史页底色）→ 详情内容（children）→ 内容遮罩 → 锚点位图。
 * 锚点位图是点击前从主界面截下的「图标那一小块像素」，钉在原窗口位置不放大，
 * 只做透明度淡变；f=0 时圆内切于它，用户看到的就是原来的图标，实现像素级连续。
 */
class ClipRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var circleCx = 0f
    private var circleCy = 0f
    private var circleRadius = 0f
    private val circlePath = Path()

    /** 是否处于圆形裁剪状态。动画结束后 [clearClip] 关闭，稳态无 outline 开销 */
    var clipping = false
        private set

    /** 锚点位图（起始层），绘制目标矩形为本容器坐标系 */
    private var anchorBitmap: Bitmap? = null
    private val anchorDst = RectF()
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var anchorAlpha = 1f

    /** 内容遮罩（收拢期盖住详情页内容，颜色与容器底色同源） */
    private val veilPaint = Paint()
    private var veilColor = 0
    private var veilAlpha = 0f

    init {
        clipToOutline = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (!clipping) {
                    // 稳态：全屏矩形（裁剪=自身 bounds，视觉无效果），绝不能 setEmpty()
                    outline.setRect(0, 0, view.width, view.height)
                    return
                }
                if (circleRadius <= 0f) {
                    outline.setRect(0, 0, 0, 0)
                    return
                }
                val r = circleRadius
                outline.setRoundRect(
                    (circleCx - r).roundToInt(), (circleCy - r).roundToInt(),
                    (circleCx + r).roundToInt(), (circleCy + r).roundToInt(), r,
                )
            }
        }
    }

    /** 设置当前显示窗口为圆（本容器自身坐标系）。半径为 0 = 整帧不画 */
    fun setCircleClip(cx: Float, cy: Float, radiusPx: Float) {
        circleCx = cx
        circleCy = cy
        circleRadius = radiusPx
        clipping = true
        clipToOutline = true
        invalidateOutline()
        invalidate()
    }

    /** 解除裁剪，恢复完整绘制（展开完成后的稳态） */
    fun clearClip() {
        clipping = false
        clipToOutline = false
        veilAlpha = 0f
        invalidateOutline()
        invalidate()
    }

    /** 钉住锚点位图；[dst] 为窗口矩形换算到本容器坐标系后的目标矩形 */
    fun setAnchorBitmap(bitmap: Bitmap?, dst: RectF?) {
        anchorBitmap = bitmap
        if (dst != null) anchorDst.set(dst) else anchorDst.setEmpty()
        invalidate()
    }

    fun setAnchorAlpha(alpha: Float) {
        anchorAlpha = alpha.coerceIn(0f, 1f)
        invalidate()
    }

    fun setVeilColor(color: Int) {
        veilColor = color
        invalidate()
    }

    /** 遮罩强度：收拢时按内容淡出互补值盖住详情页，避免半透明锚点下发灰 */
    fun setContentVeil(alpha: Float) {
        veilAlpha = alpha.coerceIn(0f, 1f)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (clipping) {
            // 零尺寸/零半径窗口整帧不画，防止空 outline 被 ROM 忽略时闪出整页
            if (circleRadius <= 0f) return
            val saveCount = canvas.save()
            circlePath.reset()
            circlePath.addCircle(circleCx, circleCy, circleRadius, Path.Direction.CW)
            canvas.clipPath(circlePath)
            super.draw(canvas)
            canvas.restoreToCount(saveCount)
            return
        }
        super.draw(canvas)
    }

    /**
     * 锚点层画在 children **之后**（恒盖在页面内容上面）：收拢的交叉淡变窗口里，
     * 锚点截图必须压住正在淡出的详情页内容，否则会叠印出残影。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (veilAlpha > 0.001f) {
            veilPaint.color = veilColor
            veilPaint.alpha = (veilAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veilPaint)
        }
        val bitmap = anchorBitmap
        if (bitmap != null && clipping && anchorAlpha > 0f && !anchorDst.isEmpty) {
            anchorPaint.alpha = (anchorAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bitmap, null, anchorDst, anchorPaint)
        }
    }
}
