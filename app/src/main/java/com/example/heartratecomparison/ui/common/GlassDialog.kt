package com.example.heartratecomparison.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DialogCorner = RoundedCornerShape(28.dp)

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
                .fillMaxWidth(0.9f)
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
