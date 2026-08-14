package com.example.heartratecomparison

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat

/**
 * 系统栏沉浸统一工具（Android 10~14 小白条适配）。
 *
 * 解决：configChanges 旋转不重建导致的沉浸失效、Android 12+ 180° 翻转（landscape↔reverseLandscape /
 * portrait↔reversePortrait）不回调 onConfigurationChanged 导致的导航栏变白、以及部分 ROM 在配置变更后
 * 按主题重放导航栏颜色的系统重放问题。
 *
 * 用法：所有 Activity 在 onCreate 调 [setupEdgeToEdge]，并（若声明了 configChanges）在
 * onConfigurationChanged 中重调一次 + post 一帧兜底。180° 翻转由本类内置的 insets 监听兜底，
 * 无需每个页面处理。
 */
object NavigationBarHelper {

    @Volatile
    private var flipResult: Boolean? = null

    /**
     * 检测是否为 Flip（折叠外屏）设备。Flip 外屏不支持透明导航栏，需要不透明底色。
     * 反射检测结果缓存，避免每次重放都走反射。
     */
    private fun isFlipDevice(): Boolean {
        flipResult?.let { return it }
        val result = try {
            val c = Class.forName("miui.util.MiuiMultiDisplayTypeInfo")
            val m = c.getMethod("isFlipDevice")
            m.invoke(c) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
        flipResult = result
        return result
    }

    /**
     * 应用 edge-to-edge 沉浸 + 注册 insets 变化兜底监听。
     *
     * @param lightStatusBar 系统栏图标是否亮色（浅色模式 true）。null 时按当前深浅色自动判断。
     */
    @Suppress("DEPRECATION")
    fun setupEdgeToEdge(activity: ComponentActivity, lightStatusBar: Boolean? = null) {
        val window = activity.window

        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        applyEdgeToEdgeWindowProperties(activity, lightStatusBar)

        // 180° 翻转（landscape↔reverseLandscape / portrait↔reversePortrait）时，
        // Android 12+ 不回调 onConfigurationChanged（Configuration.orientation 值不变），
        // 各 Activity onConfigurationChanged 里的重应用不执行，系统按默认值重放导航栏
        // 状态 → 小白条被白底包裹。旋转必然改变系统栏 insets（手势条换边/状态栏换边），
        // 故监听 decorView insets 变化，重放透明设置兜底。
        // 重复调用 setupEdgeToEdge 会覆盖此 listener，不会累积。
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    applyEdgeToEdgeWindowProperties(activity, lightStatusBar)
                }
            }
            insets // 不消费：Compose / 其他监听器仍需原始 insets
        }
    }

    /**
     * 幂等重放窗口层系统栏属性（只设窗口属性，不注册监听器——insets listener 内不可递归调
     * [setupEdgeToEdge]，否则会覆盖 listener 形成循环）。
     */
    @Suppress("DEPRECATION")
    private fun applyEdgeToEdgeWindowProperties(activity: ComponentActivity, lightStatusBar: Boolean?) {
        val window = activity.window
        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        // 必须在 enableEdgeToEdge() 之后显式设置系统栏透明 + 关闭 contrast：
        // Android 15 (API 35) 上 SystemBarStyle.auto() 的 nightMode==0 会使 enableEdgeToEdge()
        // 内部调用 setNavigationBarContrastEnforced(true)，浅色模式导航栏会显示不透明白色遮罩；
        // 显式设置同时覆盖部分 ROM 在配置变更（旋转）后按系统默认值重放的不透明白色导航栏。
        // Flip 外屏不支持透明导航栏，保持不透明底色（机型级适配）。
        window.navigationBarColor =
            if (isFlipDevice()) {
                if (isDark) 0xFF1C1B1F.toInt() else 0xFFFFFBFE.toInt()
            } else {
                Color.TRANSPARENT
            }
        window.statusBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val light = lightStatusBar ?: !isDark
        setSystemBarsAppearance(activity, light)
    }

    private fun setSystemBarsAppearance(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = activity.window.insetsController ?: return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(
                if (light) mask else 0,
                mask
            )
        } else {
            // API<30：按位保留现有 flags（LAYOUT_STABLE / LAYOUT_HIDE_NAVIGATION / LAYOUT_FULLSCREEN），
            // 只切换 LIGHT_STATUS_BAR / LIGHT_NAVIGATION_BAR 位（LIGHT_NAVIGATION_BAR 需 API 26+，minSdk 26 满足），
            // 避免覆盖式赋值清掉 edge-to-edge 沉浸状态
            @Suppress("DEPRECATION")
            val decor = activity.window.decorView
            @Suppress("DEPRECATION")
            val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                if (light) decor.systemUiVisibility or lightFlags
                else decor.systemUiVisibility and lightFlags.inv()
        }
    }
}
