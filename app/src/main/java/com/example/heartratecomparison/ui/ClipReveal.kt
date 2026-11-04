package com.example.heartratecomparison.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 「从入口图标圆扩散到下一界面」的一镜到底转场调度（对齐 sportlink 现行口径，
 * 2026-11-04 由 2026-09-25 的旧「一条线上下撑开」版升级）。
 *
 * 交互：点击历史图标 → 在本窗口 `android.R.id.content` 上插入全屏覆盖层
 * （[TouchBlockingHost] > [ClipRevealLayout] > 历史页内容）。覆盖层首帧被裁剪成
 * **历史图标本体大小的圆**，圆内铺点击前从主界面截下的那一小块像素（与真图标逐像素
 * 重合）；随后圆从锚点圆心向外扩散铺满全屏，图标像素快速淡出、历史页内容在中后段
 * 淡入。返回键 / 页内返回沿同一套几何反向收拢成圆后 removeView 露出主界面。
 *
 * 与 sportlink 的差异（本项目同窗口覆盖层，无需跨 Activity）：
 * 1. 不引入 `AppTransitions` 的 Handoff / 目标页包装 —— 历史页本来就是本窗口内的
 *    ComposeView 覆盖层，少一层窗口切换，也避开框架共享元素在 HyperOS 上的崩溃；
 * 2. 源端截图用 `decorView.draw(Canvas)` 局部截取，而不是 Compose `GraphicsLayer`
 *    （本项目 Compose BOM 2024.02.00 无 `rememberGraphicsLayer`，不升级依赖）；
 * 3. 透明度曲线、时长、几何公式与 sportlink ui/ClipReveal 保持一致。
 *
 * 硬约束（sportlink 实机踩过的坑，别改）：
 * - **布局时机**：几何必须在覆盖层首帧 PreDraw（layout 已完成）里算，并返回 false
 *    取消首帧绘制；否则裁剪生效前历史页整页先闪现一帧；
 * - **窗口坐标**：锚点矩形用 `positionInWindow()`，与 clip 容器 `getLocationInWindow`
 *    相减，层级差/状态栏差/fitsSystemWindows 差全部抵消；
 * - **内容不位移**：内容只做 alpha 交叉淡变，钉死屏幕坐标（sportlink 2026-09-27
 *    用户否决过「内容跟着上移」的旧版观感）；
 * - **纯函数驱动**：所有 alpha 都是进度 f 的函数，进场/收拢/中途打断状态连续不跳变；
 * - **触摸**：动画期间覆盖层吞掉全部触摸，展开完成后未消费的触摸也不穿透到主界面。
 *
 * 动画公式（f: 0=锚点圆 → 1=覆盖全屏；收拢反向）：
 * - radius = startR + (endR - startR) * f
 * - startR = max(锚点宽, 锚点高) / 2；endR = 圆心到最远屏幕角距离 + 1px
 */
object ClipReveal {

    private const val TAG = "ClipReveal"

    /** 展开时长（ms），配 FastOutSlowInInterpolator（与 sportlink 一致） */
    const val OPEN_MS = 580L

    /** 收拢时长（ms），配 FastOutLinearInInterpolator */
    const val CLOSE_MS = 420L

    /** 锚点像素淡出完成点（f）：0→0.22 快速淡出 */
    private const val ANCHOR_FADE_END = 0.22f

    /** 详情内容淡入窗口（f）：0.48→0.88 */
    private const val CONTENT_IN_START = 0.48f
    private const val CONTENT_IN_END = 0.88f

    /** 收拢侧：内容在前 55% 淡出，锚点像素 45% 后才淡回 */
    private const val CONTENT_OUT_END = 0.55f
    private const val ANCHOR_IN_START = 0.45f

    /** 同一时刻只允许一个展开会话（窗口里只能有一层覆盖层） */
    private var current: Holder? = null

    private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

    /**
     * 从 [anchorRectInWindow]（历史图标处 40dp 圆的窗口外接矩形）展开历史页。
     *
     * @param backgroundColor 历史页底色：容器背景/收拢遮罩必须同色，浅色模式下不能用
     *   XML 主题解析的 `colorBackground`（本项目主题是深色 Theme.Material）
     */
    fun openRevealAt(
        activity: ComponentActivity,
        anchorRectInWindow: Rect,
        backgroundColor: Int,
        createContent: (context: Context, close: () -> Unit) -> View,
        onClosed: () -> Unit,
    ): Holder {
        current?.let {
            Log.w(TAG, "已有一个展开会话，忽略本次 openReveal")
            return it
        }
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: error("ClipReveal: activity 未 setContentView，找不到 android.R.id.content")

        // 截图必须在覆盖层挂树之前：截到的是「点击前的主界面」，不是半透明覆盖层
        val anchorBitmap = captureAnchor(activity, anchorRectInWindow)

        val host = TouchBlockingHost(activity)
        val clip = ClipRevealLayout(activity).apply {
            setBackgroundColor(backgroundColor)
            setVeilColor(backgroundColor)
        }
        val holder = Holder(host, clip, onClosed)
        current = holder

        val detail = createContent(activity) { holder.close() }
        holder.content = detail
        clip.addView(detail, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        host.addView(clip, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        contentRoot.addView(host, MATCH_PARENT, MATCH_PARENT)

        // 返回键收拢：androidx 回调在 dispatcher 栈顶，优先于 Activity 既有回调
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = holder.close()
        }
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        holder.backCallback = backCallback

        // 首帧 PreDraw：此刻覆盖层已布局完成（width/height 非 0）才能算圆心与终态半径。
        // 返回 false 取消首帧绘制，防止裁剪生效前历史整页先闪现一帧。
        host.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    host.viewTreeObserver.removeOnPreDrawListener(this)
                    if (holder.phase == Holder.Phase.CLOSED) return true
                    if (host.width <= 0 || host.height <= 0) {
                        holder.showPlain()
                        return true
                    }
                    val loc = IntArray(2)
                    clip.getLocationInWindow(loc)
                    val w = host.width.toFloat()
                    val h = host.height.toFloat()
                    val cx = (anchorRectInWindow.exactCenterX() - loc[0]).coerceIn(0f, w)
                    val cy = (anchorRectInWindow.exactCenterY() - loc[1]).coerceIn(0f, h)
                    val startR = max(
                        anchorRectInWindow.width().toFloat(),
                        anchorRectInWindow.height().toFloat(),
                    ) / 2f
                    val endR = hypot(max(cx, w - cx), max(cy, h - cy)) + 1f
                    clip.setAnchorBitmap(
                        anchorBitmap,
                        RectF(
                            anchorRectInWindow.left - loc[0].toFloat(),
                            anchorRectInWindow.top - loc[1].toFloat(),
                            anchorRectInWindow.right - loc[0].toFloat(),
                            anchorRectInWindow.bottom - loc[1].toFloat(),
                        ),
                    )
                    holder.beginOpen(cx, cy, startR, endR, w, h)
                    return false
                }
            },
        )
        return holder
    }

    /** 收拢当前展开会话（若在播）。返回 true = 已接管收拢 */
    fun closeActive(): Boolean {
        val holder = current ?: return false
        holder.close()
        return true
    }

    /**
     * 从窗口 decorView 局部截取锚点区域的像素。只截 40dp 见方，开销可忽略。
     * 失败（Compose 软件绘制异常/尺寸异常）返回 null → 退化为纯色圆扩散，动画不中断。
     */
    private fun captureAnchor(activity: Activity, rect: Rect): Bitmap? = try {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) null
        else {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // decorView 原点即窗口原点，与 positionInWindow() 同坐标系
            canvas.translate(-rect.left.toFloat(), -rect.top.toFloat())
            activity.window.decorView.draw(canvas)
            bitmap
        }
    } catch (t: Throwable) {
        Log.w(TAG, "锚点截图失败 → 退化为纯色圆扩散", t)
        null
    }

    /** 锚点像素透明度：f 的纯函数（0→0.22 淡出；收拢反向淡回） */
    private fun anchorAlphaAt(f: Float): Float =
        1f - (f / ANCHOR_FADE_END).coerceIn(0f, 1f)

    /** 详情内容透明度：f 的纯函数（0.48→0.88 淡入） */
    private fun contentAlphaAt(f: Float): Float =
        ((f - CONTENT_IN_START) / (CONTENT_IN_END - CONTENT_IN_START)).coerceIn(0f, 1f)

    /** 收拢侧锚点像素淡入：raw 0.45→1 */
    private fun anchorAlphaIn(raw: Float): Float =
        ((raw - ANCHOR_IN_START) / (1f - ANCHOR_IN_START)).coerceIn(0f, 1f)

    /** 收拢侧内容淡出：raw 0→0.55 */
    private fun contentAlphaOut(raw: Float): Float =
        1f - (raw / CONTENT_OUT_END).coerceIn(0f, 1f)

    /**
     * 动画期间吞掉全部触摸的覆盖层根：
     * - blockTouch=true（进场/收拢中）：dispatchTouchEvent 直接消费，事件到不了
     *   详情内容，更到不了主界面（主界面是本层的兄弟节点）；
     * - blockTouch=false（展开完成）：事件正常进入详情内容；详情未消费的区域
     *   落在本层（clickable）也不会穿透到主界面。
     */
    internal class TouchBlockingHost(context: Context) : FrameLayout(context) {
        var blockTouch = true

        init { isClickable = true }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (blockTouch) return true
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 一次展开的会话句柄。持有收拢入口 [close]；展开/收拢动画在此驱动 */
    class Holder internal constructor(
        private val host: TouchBlockingHost,
        private val clip: ClipRevealLayout,
        private val onClosed: () -> Unit,
    ) {
        internal var content: View? = null
        internal enum class Phase { OPENING, OPEN, CLOSING, CLOSED }
        internal var phase = Phase.OPENING
            private set
        internal var backCallback: OnBackPressedCallback? = null

        private var openSet: AnimatorSet? = null
        /** 展开进度 f ∈ [0,1]（0=锚点圆，1=全屏）；收拢从当前值继续 */
        private var progress = 0f
        private var geometryReady = false
        private var cx = 0f
        private var cy = 0f
        private var startR = 0f
        private var endR = 0f
        private var fullWidth = 0f
        private var fullHeight = 0f

        private fun radiusAt(f: Float) = startR + (endR - startR) * f

        /** 进场（首帧 PreDraw 里调用）：先摆 f=0，再播 580ms 圆扩散 + 交叉淡变 */
        internal fun beginOpen(
            cx: Float, cy: Float, startR: Float, endR: Float, width: Float, height: Float,
        ) {
            this.cx = cx
            this.cy = cy
            this.startR = startR
            this.endR = endR
            fullWidth = width
            fullHeight = height
            geometryReady = true
            applyProgress(0f)
            // 内容初始态必须在动画启动前置好：内容淡入窗口从 f=0.48 才开始，不预置
            // 的话前 48% 会以 alpha=1 全量出现在扩散中的圆里，随后才被动画首帧拉回 0
            content?.alpha = 0f
            clip.setContentVeil(0f)

            val reveal = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = OPEN_MS
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { applyProgress(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (phase != Phase.OPENING) return // 已被 close() 接管
                        phase = Phase.OPEN
                        clip.clearClip()
                        host.blockTouch = false
                    }
                })
            }
            openSet = AnimatorSet().apply {
                play(reveal)
                start()
            }
        }

        /** 布局异常兜底（宽高为 0）：跳过动画直接进入稳态，保证历史页可用 */
        internal fun showPlain() {
            phase = Phase.OPEN
            clip.clearClip()
            host.blockTouch = false
        }

        /** 进度 f → 圆形裁剪窗口 + 锚点/内容透明度（进场时间窗） */
        private fun applyProgress(f: Float) {
            progress = f
            clip.setCircleClip(cx, cy, radiusAt(f))
            clip.setAnchorAlpha(anchorAlphaAt(f))
            content?.alpha = contentAlphaAt(f)
        }

        /**
         * 反向收拢到锚点圆，结束后移除覆盖层。可在任意阶段调用：进场未完成时先取消
         * 进场，再从当前进度继续收拢（不跳变）。
         */
        fun close() {
            when (phase) {
                Phase.CLOSED, Phase.CLOSING -> return
                Phase.OPENING -> {
                    // 先改 phase 再 cancel：进场动画的 onEnd 检查 phase，跳过其终态处理
                    phase = Phase.CLOSING
                    openSet?.cancel()
                    openSet = null
                }
                Phase.OPEN -> phase = Phase.CLOSING
            }
            backCallback?.isEnabled = false
            host.blockTouch = true

            if (!geometryReady) {
                // beginOpen 尚未执行（PreDraw 还没跑）：屏幕上什么都没显示过，直接移除
                finishImmediately()
                return
            }
            remapToCurrentHostSize()
            val reveal = ValueAnimator.ofFloat(progress, 0f).apply {
                duration = CLOSE_MS
                interpolator = FastOutLinearInInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    val raw = it.animatedFraction
                    progress = f
                    clip.setCircleClip(cx, cy, radiusAt(f))
                    // 收拢侧独立时间窗：内容先淡出、锚点像素后淡回；与进场窗口取
                    // max/min，中途打断时状态连续不跳变
                    val anchorA = max(anchorAlphaAt(f), anchorAlphaIn(raw))
                    clip.setAnchorAlpha(anchorA)
                    val contentA = min(contentAlphaAt(f), contentAlphaOut(raw))
                    content?.alpha = contentA
                    // 遮罩盖住半透明内容下透出的详情页底色，锚点层画在遮罩之上
                    clip.setContentVeil(max(1f - contentA, anchorA))
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finishImmediately()
                })
            }
            reveal.start()
        }

        /** 跳过动画立即移除覆盖层（宿主组合销毁等场景）。已收拢时为 no-op */
        fun dismissNow() = finishImmediately()

        /**
         * 覆盖层存续期间旋转（Activity 声明了 configChanges，窗口不重建）：按锚点占比
         * 把圆心重映射到当前尺寸并重算终态半径，否则收拢圆可能整体落在屏幕外，
         * 视觉为历史页瞬间消失。尺寸未变时为 no-op。
         */
        private fun remapToCurrentHostSize() {
            val wNow = host.width.toFloat()
            val hNow = host.height.toFloat()
            if (wNow <= 0f || hNow <= 0f || fullWidth <= 0f || fullHeight <= 0f) return
            if (wNow == fullWidth && hNow == fullHeight) return
            cx = cx / fullWidth * wNow
            cy = cy / fullHeight * hNow
            fullWidth = wNow
            fullHeight = hNow
            endR = hypot(max(cx, wNow - cx), max(cy, hNow - cy)) + 1f
        }

        private fun finishImmediately() {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            clip.clearClip()
            (host.parent as? ViewGroup)?.removeView(host)
            backCallback?.remove()
            backCallback = null
            if (current === this) current = null
            onClosed.invoke()
        }
    }
}
