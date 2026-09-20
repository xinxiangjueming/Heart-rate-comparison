package com.example.heartratecomparison.ui.screen

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.example.heartratecomparison.ui.common.GlassAlertDialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.heartratecomparison.R
import com.example.heartratecomparison.data.CsvExporter
import com.example.heartratecomparison.data.HeartRateDatabase
import com.example.heartratecomparison.data.SessionWithCount
import com.example.heartratecomparison.ui.icon.MiuixShareIcon
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

    // 分享：点击列表项右侧的分享图标，从 Room 动态生成 CSV 临时文件，通过 FileProvider 分享
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
        GlassAlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = stringResource(R.string.history_delete_title),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = { sessionToDelete = null },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.btn_cancel),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.btn_confirm),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 判断是否大屏横屏
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isTablet = (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE

    // 列表滚动位置提升到方向分支之外：横竖屏切换时列表位置不丢
    val listState = rememberLazyGridState()

    // Activity 因 configChanges=orientation|screenSize 不会重建，旋转只在 Compose 层重组。
    // 「淡出 → 切换布局 → 淡入」：先淡出旧布局（130ms），在完全不可见时换成新布局，
    // 再淡入并伴随 0.98→1 的轻微缩放回弹（220ms），避免列表硬跳变。
    var layoutLandscape by remember { mutableStateOf(isLandscape) }
    val layoutProgress = remember { Animatable(1f) }
    LaunchedEffect(isLandscape) {
        if (isLandscape == layoutLandscape) {
            // 旋转中途折返：动画可能停在半途，把当前布局补回完全可见
            layoutProgress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
            return@LaunchedEffect
        }
        layoutProgress.animateTo(0f, tween(130, easing = LinearEasing))
        layoutLandscape = isLandscape
        layoutProgress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
    }

    val useSplitLayout = layoutLandscape && isTablet
    // 整屏横屏时历史列表排两列；竖屏与分栏左栏（窄）保持一列
    val listColumns = if (layoutLandscape) 2 else 1

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
            // 过渡动画只作用于内容层：背景与系统栏避让层保持稳定，不参与淡入淡出/缩放
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = layoutProgress.value
                        val s = 0.98f + 0.02f * layoutProgress.value
                        scaleX = s
                        scaleY = s
                    }
            ) {
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
                                state = listState,
                                columns = 1,
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
                    SessionListContent(
                        sessionList = sessionList,
                        onSessionClick = { selectedSession = it },
                        onSessionShare = { shareSession(it.session.id) },
                        onSessionLongClick = { sessionToDelete = it },
                        displayFormat = displayFormat,
                        state = listState,
                        columns = listColumns,
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
    state: LazyGridState,
    /** 列数：整屏横屏 2 列，竖屏 / 分栏左栏 1 列 */
    columns: Int = 1,
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                        // 分享入口：右侧图标，随行高垂直居中；点击弹出系统分享
                        IconButton(
                            onClick = { onSessionShare(item) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = MiuixShareIcon,
                                contentDescription = stringResource(R.string.desc_share),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
