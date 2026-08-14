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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
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

        // 沉浸式适配（HyperOS 全屏沉浸模式 + 自由窗口兼容 + Flip 外屏兼容，统一走 NavigationBarHelper）
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
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

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/深浅色切换后重新应用 edge-to-edge（Manifest 声明 configChanges 后旋转不重建 Activity，
        // 系统可能按主题重放导航栏颜色，必须在此重设；180° 翻转由 NavigationBarHelper 的 insets 监听兜底）
        NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        // 系统可能在配置变更后重放窗口属性，延迟一帧再设一次，确保重放之后仍是透明导航栏
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            NavigationBarHelper.setupEdgeToEdge(this, lightStatusBar = !isNightMode())
        }
    }
}
