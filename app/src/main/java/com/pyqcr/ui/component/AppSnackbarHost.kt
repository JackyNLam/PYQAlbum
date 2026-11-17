package com.pyqcr.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app's single message popup, drawn to look like the system toast that
 * reports a finished delete: launcher icon in front, text centred on screen, the
 * card hugging its text instead of a full-width bar.
 *
 * Material's default Snackbar painted `inverseSurface` — a black bar in this
 * app's light theme — and stretched across the screen with blank space at both
 * ends, so it never matched the other notifications. Every screen host goes
 * through this composable rather than `SnackbarHost`.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val icon = rememberAppIconPainter()
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            ToastCard(data, icon)
        }
    }
}

@Composable
private fun ToastCard(data: SnackbarData, icon: Painter?) {
    val message = singleLineText(data.visuals.message)
    val actionLabel = data.visuals.actionLabel?.trim()?.takeIf { it.isNotEmpty() }
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .widthIn(max = 480.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(
                start = 14.dp,
                end = if (actionLabel == null) 14.dp else 4.dp,
                top = 10.dp,
                bottom = 10.dp
            )
        ) {
            if (icon != null) {
                Image(
                    painter = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (actionLabel != null) {
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * Collapses a message onto one trimmed line. Leading / trailing blanks and the
 * blanks around newlines are what made popups look like they carried empty space
 * in front of and behind the text.
 */
private fun singleLineText(text: String): String =
    text.trim()
        .replace(Regex("\\s*\\n\\s*"), " ")
        .replace(Regex("\\s{2,}"), " ")

/**
 * The launcher icon as a painter — the icon the system toast puts in front of
 * its text. Taken from the package manager because `R.mipmap.ic_launcher` is an
 * adaptive icon, which `painterResource` cannot draw directly.
 */
@Composable
fun rememberAppIconPainter(): Painter? {
    val context = LocalContext.current
    return remember(context) {
        try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            BitmapPainter(bitmap.asImageBitmap())
        } catch (e: Exception) {
            null
        }
    }
}
