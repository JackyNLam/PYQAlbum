package com.pyqcr.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's single message-bar look.
 *
 * Material's default Snackbar paints `inverseSurface`, which is a black bar in
 * this app's light theme — visibly different from the system toast that reports
 * a finished delete. Messages therefore use the normal surface colour with a
 * toast-like radius, so every popup in the app reads the same. Screen-specific
 * snackbar hosts must go through this composable rather than `SnackbarHost`.
 */
@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.primary
        )
    }
}