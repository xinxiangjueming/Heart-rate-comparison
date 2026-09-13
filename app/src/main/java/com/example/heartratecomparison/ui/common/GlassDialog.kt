package com.example.heartratecomparison.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.DeviceInfo

private val DialogCorner = RoundedCornerShape(28.dp)

private const val NOT_AVAILABLE = "—"

/**
 * 简洁确认弹窗。
 *
 * 仅保留卡片包裹与按键布局，去除玻璃模糊（haze）与 miuix 高光描边。
 * 卡片用纯色 surface 背景，不再需要调用方提供任何模糊状态。
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: String? = null,
    buttons: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 宽度：横屏 50%、竖屏 80%（与 DeviceInfoDialog 一致）
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 弹窗窗口也按 edge-to-edge 处理，避免系统在状态栏/小白条区域补一层 scrim（表现为"阴影"）
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val cardModifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.5f else 0.8f)
                .background(scheme.surface, DialogCorner)
                .clip(DialogCorner)
                .padding(24.dp)

            Column(modifier = cardModifier) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text != null) {
                    Text(
                        text = text,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    buttons()
                }
            }
        }
    }
}

/**
 * 设备信息弹窗：展示设备 ID 与 Device Information Service 读取到的
 * 厂商/型号/序列号/固件版本/软件版本。info 为 null 或字段缺失时显示 "—"。
 */
@Composable
fun DeviceInfoDialog(
    deviceName: String,
    address: String,
    info: DeviceInfo?,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 宽度：横屏 50%、竖屏 80%（外层 Box 两侧各有 24dp 安全边距）
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(if (isLandscape) 0.5f else 0.8f)
                    .background(scheme.surface, DialogCorner)
                    .clip(DialogCorner)
            ) {
                // 内容区：内容过高时压缩到可用高度并滚动，关闭按钮固定在卡片底部始终可见
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp)
                ) {
                    Text(
                        text = deviceName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val rows = listOf(
                        stringResource(R.string.label_device_id) to address,
                        stringResource(R.string.label_manufacturer) to info?.manufacturer,
                        stringResource(R.string.label_model) to info?.model,
                        stringResource(R.string.label_serial_number) to info?.serialNumber,
                        stringResource(R.string.label_firmware_version) to info?.firmwareVersion,
                        stringResource(R.string.label_software_version) to info?.softwareVersion,
                    )
                    rows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = label,
                                fontSize = 15.sp,
                                color = scheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = value ?: NOT_AVAILABLE,
                                fontSize = 15.sp,
                                color = scheme.onSurface,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.btn_close),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
