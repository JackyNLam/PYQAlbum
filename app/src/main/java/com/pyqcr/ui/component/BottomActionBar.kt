package com.pyqcr.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ActionBarAction(
    val label: String,
    val icon: String,  // Using text as icon for simplicity
    val onClick: () -> Unit
)

/**
 * Bottom action bar shown during batch selection mode.
 * Contains buttons for batch operations: add tag, rate, remove tags.
 */
@Composable
fun BottomActionBar(
    selectedCount: Int,
    actions: List<ActionBarAction>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                actions.forEach { action ->
                    OutlinedButton(
                        onClick = action.onClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(action.label)
                    }
                }
            }
        }
    }
}