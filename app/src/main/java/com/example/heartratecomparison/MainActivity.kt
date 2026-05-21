package com.example.heartratecomparison

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.heartratecomparison.bluetooth.HeartRateService
import com.example.heartratecomparison.ui.screen.MainScreen
import com.example.heartratecomparison.ui.theme.HeartRateComparisonTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }
        startService(Intent(this, HeartRateService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // HyperOS 公平运行内存适配
        MemoryReceiver.getInstance().initialize(this)

        // 通知权限请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> {
                    startService(Intent(this, HeartRateService::class.java))
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startService(Intent(this, HeartRateService::class.java))
        }

        // 沉浸式适配（HyperOS 全屏沉浸模式 + 自由窗口兼容 + Flip 外屏兼容）
        enableEdgeToEdge()
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // 检测是否为 Flip 设备，Flip 外屏不支持透明导航栏
        val isFlip = try {
            val c = Class.forName("miui.util.MiuiMultiDisplayTypeInfo")
            val m = c.getMethod("isFlipDevice")
            m.invoke(c) as? Boolean ?: false
        } catch (_: Exception) { false }
        if (isFlip) {
            window.navigationBarColor = if (isDark) 0xFF1C1B1F.toInt() else 0xFFFFFBFE.toInt()
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDark
        insetsController.isAppearanceLightNavigationBars = !isDark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContent {
            HeartRateComparisonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
