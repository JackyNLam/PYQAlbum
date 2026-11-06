package com.pyqcr

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.activity.compose.rememberLauncherForActivityResult
import coil3.compose.AsyncImage
import com.pyqcr.ui.theme.PyqCrTheme
import com.pyqcr.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image offered by the picker (content URI + display name). */
private data class PickItem(val uri: String, val name: String)

/**
 * Exported image picker.
 *
 * Before this existed PYQAlbum only registered MAIN/LAUNCHER, so when a browser
 * (or any other app) asked for a photo via ACTION_GET_CONTENT / ACTION_PICK the
 * app never appeared in the chooser. This activity answers those requests with
 * a small grid of the device's images and returns the chosen content URI(s)
 * with a read grant — the contract every picker client expects.
 */
class PickImageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val allowMultiple = intent.action == Intent.ACTION_GET_CONTENT &&
            intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)

        setContent {
            PyqCrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PickImageScreen(
                        allowMultiple = allowMultiple,
                        onCancel = {
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        },
                        onPick = { uris -> deliver(uris) }
                    )
                }
            }
        }
    }

    /** Return the selection in every form picker clients look at. */
    private fun deliver(uris: List<Uri>) {
        if (uris.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        val result = Intent().apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            data = uris.first()
            // ClipData carries the read grant for EVERY item; single-item
            // clients still read `data`, multi-item ones read the clip.
            clipData = ClipData.newUri(contentResolver, "PYQAlbum", uris.first()).apply {
                for (uri in uris.drop(1)) addItem(ClipData.Item(uri))
            }
            if (uris.size > 1) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

@Composable
private fun PickImageScreen(
    allowMultiple: Boolean,
    onCancel: () -> Unit,
    onPick: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var granted by remember { mutableStateOf(PermissionHelper.hasMediaPermission(context)) }
    var askAgain by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        if (!result) askAgain = true
    }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(permission)
    }

    var allImages by remember { mutableStateOf<List<PickItem>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        allImages = withContext(Dispatchers.IO) { loadImages(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Choose from PYQAlbum",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        when {
            !granted -> PermissionBody(askAgain) { permissionLauncher.launch(permission) }
            allImages == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            allImages!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No photos found on this device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                val list = allImages!!
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(list, key = { it.uri }) { item ->
                        val uri = Uri.parse(item.uri)
                        val isSelected = item.uri in selected
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    if (allowMultiple) {
                                        selected = if (isSelected) selected - item.uri
                                        else selected + item.uri
                                    } else {
                                        onPick(listOf(uri))
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (allowMultiple && isSelected) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                )
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }

                if (allowMultiple) {
                    Button(
                        onClick = { onPick(selected.map { Uri.parse(it) }) },
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Text(
                            if (selected.isEmpty()) "Select photos"
                            else "Add ${selected.size} photo(s)"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionBody(askAgain: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (askAgain) {
                "Photo access is needed before another app can pick an image from here."
            } else {
                "Allow photo access so another app can pick an image."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.padding(8.dp))
        Button(onClick = onRequest) { Text("Allow access") }
    }
}

/** Device images, newest first, excluding trashed rows on Android 11+. */
private fun loadImages(context: android.content.Context): List<PickItem> {
    val out = mutableListOf<PickItem>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME
    )
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "${MediaStore.Images.Media.IS_TRASHED} = 0"
    } else null
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                out += PickItem(uri.toString(), name ?: "Image")
            }
        }
    } catch (e: Exception) {
        // An unreadable provider must not crash the picker — return what we got.
    }
    return out
}