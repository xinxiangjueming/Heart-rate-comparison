package com.example.heartratecomparison.ui.screen

import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.heartratecomparison.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current

    val statusBarHeight = with(density) {
        WindowInsets.systemBars.getTop(this).toDp()
    }
    val navigationBarHeight = with(density) {
        WindowInsets.systemBars.getBottom(this).toDp()
    }

    val comparisonDir = remember { File(context.getExternalFilesDir(null), "Comparison") }

    var fileList by remember {
        mutableStateOf(
            run {
                if (comparisonDir.exists()) {
                    comparisonDir.listFiles { f -> f.extension == "csv" }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
        )
    }

    fun refreshFileList() {
        fileList = if (comparisonDir.exists()) {
            comparisonDir.listFiles { f -> f.extension == "csv" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var selectedFile by remember { mutableStateOf<File?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    val displayFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // 返回手势拦截
    if (selectedFile == null) {
        BackHandler { onBack() }
    }

    // 删除确认弹窗
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = {
                Text(
                    text = stringResource(R.string.history_delete_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = { Text(stringResource(R.string.history_delete_message)) },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { fileToDelete = null }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                    Button(
                        onClick = {
                            fileToDelete?.delete()
                            fileList = fileList.filter { it != fileToDelete }
                            fileToDelete = null
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
    if (selectedFile != null && !useSplitLayout) {
        CsvChartScreen(file = selectedFile!!, onBack = { selectedFile = null })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = statusBarHeight + 7.dp,
                bottom = navigationBarHeight + 7.dp,
                start = 7.dp,
                end = 7.dp
            )
    ) {
        if (useSplitLayout && selectedFile != null) {
            // 大屏横屏分栏：左侧文件列表，右侧图表
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧文件列表
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    FileListContent(
                        fileList = fileList,
                        onFileClick = { selectedFile = it },
                        onFileLongClick = { fileToDelete = it },
                        dateFormat = dateFormat,
                        displayFormat = displayFormat,
                        view = view,
                        context = context
                    )
                }
                Spacer(modifier = Modifier.width(7.dp))
                // 右侧图表
                Box(
                    modifier = Modifier
                        .weight(3f)
                        .fillMaxHeight()
                ) {
                    CsvChartScreen(file = selectedFile!!, onBack = { selectedFile = null })
                }
            }
        } else {
            // 普通模式：文件列表或空状态
            Column(modifier = Modifier.fillMaxSize()) {
                FileListContent(
                    fileList = fileList,
                    onFileClick = { selectedFile = it },
                    onFileLongClick = { fileToDelete = it },
                    dateFormat = dateFormat,
                    displayFormat = displayFormat,
                    view = view,
                    context = context
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListContent(
    fileList: List<File>,
    onFileClick: (File) -> Unit,
    onFileLongClick: (File) -> Unit,
    dateFormat: SimpleDateFormat,
    displayFormat: SimpleDateFormat,
    view: View,
    context: android.content.Context
) {
    if (fileList.isEmpty()) {
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
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(fileList, key = { it.name }) { file ->
                val timestamp = try {
                    val name = file.nameWithoutExtension.removePrefix("heart_")
                    val date = dateFormat.parse(name)
                    if (date != null) displayFormat.format(date) else file.name
                } catch (e: Exception) {
                    file.name
                }
                val sizeKb = file.length() / 1024

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { onFileClick(file) },
                            onDoubleClick = {
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
                            },
                            onLongClick = { onFileLongClick(file) }
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DragIndicator,
                            contentDescription = "拖拽分享",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier
                                .size(24.dp)
                                .pointerInput(file) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file
                                            )
                                            val clipData = ClipData(
                                                "CSV file",
                                                arrayOf("text/csv"),
                                                ClipData.Item(uri)
                                            )
                                            view.startDragAndDrop(
                                                clipData,
                                                View.DragShadowBuilder(view),
                                                null,
                                                View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                                            )
                                        },
                                        onDrag = { _, _ -> },
                                        onDragEnd = {},
                                        onDragCancel = {}
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = timestamp,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.history_file_size, sizeKb),
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
