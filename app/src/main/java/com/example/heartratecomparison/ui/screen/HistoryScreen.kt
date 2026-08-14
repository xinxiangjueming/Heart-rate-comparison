package com.example.heartratecomparison.ui.screen

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.heartratecomparison.R
import com.example.heartratecomparison.data.CsvExporter
import com.example.heartratecomparison.data.HeartRateDatabase
import com.example.heartratecomparison.data.SessionWithCount
import com.example.heartratecomparison.ui.theme.LocalDeviceCardBorder
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 底部系统栏 inset + 7dp 视觉间距：列表滚动到底时最后一项不被小白条遮挡
    val bottomInset = with(density) {
        WindowInsets.safeDrawing.getBottom(this).toDp() + 7.dp
    }

    val dao = remember { HeartRateDatabase.getInstance(context).recordDao() }

    var sessionList by remember { mutableStateOf(emptyList<SessionWithCount>()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        sessionList = withContext(Dispatchers.IO) { dao.getSessionsWithCount() }
    }

    fun refresh() {
        refreshTrigger++
    }

    var sessionToDelete by remember { mutableStateOf<SessionWithCount?>(null) }
    var selectedSession by remember { mutableStateOf<SessionWithCount?>(null) }

    val displayFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // 双击分享：从 Room 动态生成 CSV 临时文件，通过 FileProvider 分享
    fun shareSession(sessionId: Long) {
        scope.launch {
            val file = withContext(Dispatchers.IO) { CsvExporter.exportSession(context, sessionId) }
            if (file != null) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, null))
            }
        }
    }

    // 返回手势拦截
    if (selectedSession == null) {
        BackHandler { onBack() }
    }

    // 删除确认弹窗
    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.history_delete_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.history_delete_message),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { sessionToDelete = null }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                    Button(
                        onClick = {
                            val target = sessionToDelete
                            sessionToDelete = null
                            if (target != null) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { dao.deleteSession(target.session.id) }
                                    refresh()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.btn_confirm))
                    }
                }
            },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        )
    }

    // 判断是否大屏横屏
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
    val useSplitLayout = isLandscape && isTablet

    // 全屏横图表（非分栏模式时）
    if (selectedSession != null && !useSplitLayout) {
        CsvChartScreen(sessionId = selectedSession!!.session.id, onBack = { selectedSession = null })
        return
    }

    // 背景铺满全屏（真沉浸）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(7.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
            if (useSplitLayout && selectedSession != null) {
                // 大屏横屏分栏：左侧列表，右侧图表
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        SessionListContent(
                            sessionList = sessionList,
                            onSessionClick = { selectedSession = it },
                            onSessionShare = { shareSession(it.session.id) },
                            onSessionLongClick = { sessionToDelete = it },
                            displayFormat = displayFormat,
                            contentBottomPadding = bottomInset
                        )
                    }
                    Spacer(modifier = Modifier.width(7.dp))
                    Box(
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight()
                    ) {
                        // 分栏模式：列表可见，不锁定横屏也不隐藏系统栏
                        CsvChartScreen(
                            sessionId = selectedSession!!.session.id,
                            onBack = { selectedSession = null },
                            immersive = false
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SessionListContent(
                        sessionList = sessionList,
                        onSessionClick = { selectedSession = it },
                        onSessionShare = { shareSession(it.session.id) },
                        onSessionLongClick = { sessionToDelete = it },
                        displayFormat = displayFormat,
                        contentBottomPadding = bottomInset
                    )
                }
            }
        }
    }
}

/** 时长格式化：>1 小时显示 h:mm:ss，否则 mm:ss */
private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListContent(
    sessionList: List<SessionWithCount>,
    onSessionClick: (SessionWithCount) -> Unit,
    onSessionShare: (SessionWithCount) -> Unit,
    onSessionLongClick: (SessionWithCount) -> Unit,
    displayFormat: SimpleDateFormat,
    contentBottomPadding: Dp = 0.dp
) {
    if (sessionList.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.history_empty),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(bottom = contentBottomPadding)
        ) {
            items(sessionList, key = { it.session.id }) { item ->
                val session = item.session
                val timestamp = displayFormat.format(Date(session.startTime))
                val duration = formatDuration(session.endTime - session.startTime)

                val itemShape = MaterialTheme.shapes.small
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .combinedClickable(
                            onClick = { onSessionClick(item) },
                            onDoubleClick = { onSessionShare(item) },
                            onLongClick = { onSessionLongClick(item) }
                        )
                        .border(1.dp, LocalDeviceCardBorder.current, itemShape),
                    shape = itemShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 19.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = timestamp,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.history_summary, item.sampleCount, duration),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
