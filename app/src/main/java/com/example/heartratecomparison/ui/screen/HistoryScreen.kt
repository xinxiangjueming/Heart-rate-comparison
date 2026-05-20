package com.example.heartratecomparison.ui.screen

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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

    val statusBarHeight = with(density) {
        WindowInsets.systemBars.getTop(this).toDp()
    }
    val navigationBarHeight = with(density) {
        WindowInsets.systemBars.getBottom(this).toDp()
    }

    var fileList by remember {
        mutableStateOf(
            run {
                val dir = File(context.getExternalFilesDir(null), "Comparison")
                if (dir.exists()) {
                    dir.listFiles { f -> f.extension == "csv" }
                        ?.sortedByDescending { it.lastModified() }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
        )
    }

    var fileToDelete by remember { mutableStateOf<File?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }
    val displayFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // 删除确认弹窗
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_message)) },
            confirmButton = {
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
            },
            dismissButton = {
                Button(
                    onClick = { fileToDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.outline)
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
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
        Column(modifier = Modifier.fillMaxSize()) {
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
                                    onClick = {},
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
                                    onLongClick = { fileToDelete = file }
                                ),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
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
}
