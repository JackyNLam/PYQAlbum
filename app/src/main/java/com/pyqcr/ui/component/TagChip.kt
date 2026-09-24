package com.pyqcr.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagChip(
    text: String,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    if (onRemove != null) {
        InputChip(
            selected = selected,
            onClick = { onClick?.invoke() },
            label = { Text(text) },
            trailingIcon = {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(18.dp)
                ) {
                    Text("✕", style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = modifier.padding(2.dp),
            shape = RoundedCornerShape(16.dp)
        )
    } else {
        AssistChip(
            onClick = { onClick?.invoke() },
            label = { Text(text) },
            modifier = modifier.padding(2.dp),
            shape = RoundedCornerShape(16.dp)
        )
    }
}