package com.pyqcr.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** One decoded QR code: the file it came from plus the payload. */
data class QrScanResult(val label: String, val text: String)

/**
 * Shows the text of the QR code(s) found in the selected images. Every result
 * has a Copy button, and "Copy all" joins them with a blank line so the user
 * can paste the payload anywhere.
 */
@Composable
fun QrCodeResultDialog(
    results: List<QrScanResult>,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val combined = results.joinToString("\n\n") { it.text }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (results.size == 1) "QR code" else "QR codes (${results.size})") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (results.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(combined))
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text("Copy all")
                        }
                    }
                }
                results.forEachIndexed { index, result ->
                    if (index > 0) HorizontalDivider()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = result.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = result.text,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(modifier = Modifier.align(Alignment.End)) {
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(result.text))
                            }) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text("Copy")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Simple text dialog for "no QR code found" feedback. */
@Composable
fun QrCodeEmptyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("No QR code found") },
        text = {
            Text("No QR code could be read from the selected image(s). Try a sharper or less cropped photo.")
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}