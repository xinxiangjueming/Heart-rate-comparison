package com.example.heartratecomparison.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.heartratecomparison.R
import com.example.heartratecomparison.model.UiDeviceState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeviceItem(
    deviceState: UiDeviceState,
    isRecording: Boolean,
    nameColor: Color?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val textColor = if (isRecording && nameColor != null) nameColor else Color.Unspecified
    val shape = MaterialTheme.shapes.small

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)                // 👈 关键：裁剪水波纹为圆角
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = deviceState.name,
                color = textColor,
                modifier = Modifier.weight(4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = stringResource(R.string.desc_bluetooth),
                tint = if (deviceState.isConnected) Color(0xFF64B5F6) else Color(0xFFBDBDBD),
                modifier = Modifier.weight(1f)
            )
        }
    }
}