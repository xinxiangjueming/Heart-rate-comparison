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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.heartratecomparison.bluetooth.HeartRateService
import com.example.heartratecomparison.data.CsvExporter
import com.example.heartratecomparison.data.CsvImporter
import com.example.heartratecomparison.ui.screen.CsvChartScreen
import com.example.heartratecomparison.ui.screen.MainScreen
import com.example.heartratecomparison.ui.theme.HeartRateComparisonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 通过"用其他应用打开"传入的 CSV URI；onNewIntent 也可能更新它 */
    private val csvImportUri = mutableStateOf<Uri?>(null)

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

        // 处理"打开 CSV"入口（应用未运行时从此 intent 进入）
        handleIntent(intent)

        // 启动时兜底清理分享缓存：cacheDir/share/ 中超过 7 天的临时 CSV（异步 IO，不阻塞启动）
        lifecycleScope.launch(Dispatchers.IO) {
            CsvExporter.cleanExpiredShareFiles(this@MainActivity)
        }

        setContent {
            HeartRateComparisonTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(csvImportUri.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 应用已在运行时，从"打开 CSV"进来走这里
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            csvImportUri.value = intent.data
        }
    }

    /**
     * 根路由：区分"正常启动"与"打开 CSV"。
     * phase: main | loading | chart | error
     */
    @Composable
    private fun AppRoot(importUri: Uri?) {
        val context = LocalContext.current
        var phase by rememberSaveable { mutableStateOf("main") }
        var chartSessionId by rememberSaveable { mutableStateOf(0L) }
        var errorMsg by rememberSaveable { mutableStateOf("") }
        // 防止同一 URI 被重复导入（配置变更或重入）
        var consumedUri by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(importUri) {
            if (importUri == null || phase != "main") return@LaunchedEffect
            if (consumedUri == importUri.toString()) return@LaunchedEffect
            consumedUri = importUri.toString()
            phase = "loading"
            try {
                val id = withContext(Dispatchers.IO) { CsvImporter.importUri(context, importUri) }
                if (id != null) {
                    chartSessionId = id
                    phase = "chart"
                } else {
                    errorMsg = context.getString(R.string.csv_import_unsupported)
                    phase = "error"
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: context.getString(R.string.csv_import_unsupported)
                phase = "error"
            }
        }

        when (phase) {
            "loading" -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.csv_import_loading),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            "error" -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Button(onClick = { phase = "main" }) {
                            Text(text = stringResource(R.string.btn_confirm))
                        }
                    }
                }
            }
            "chart" -> CsvChartScreen(
                sessionId = chartSessionId,
                onBack = { phase = "main" }
            )
            else -> MainScreen()
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
