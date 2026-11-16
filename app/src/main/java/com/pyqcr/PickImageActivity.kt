package com.pyqcr

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pyqcr.data.db.AppDatabase
import com.pyqcr.ui.theme.PyqCrTheme
import com.pyqcr.util.FavouriteStore
import com.pyqcr.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image offered by the picker. */
private data class PickPhoto(val uri: String, val name: String, val folder: String)

/** A device folder (MediaStore bucket) with its images, newest first. */
private data class PickFolder(val name: String, val photos: List<PickPhoto>) {
    val cover: PickPhoto? get() = photos.firstOrNull()
}

/** A user tag with the images it is attached to. */
private data class PickTag(val id: Long, val name: String, val photos: List<PickPhoto>) {
    val cover: PickPhoto? get() = photos.firstOrNull()
}

/** Everything the picker can browse: all photos, folders and tags. */
private data class PickerData(
    val photos: List<PickPhoto>,
    val folders: List<PickFolder>,
    val tags: List<PickTag>
)

private enum class PickTab(val label: String) {
    FOLDERS("Folders"),
    TAGS("Tags"),
    ALL("All photos")
}

/**
 * Exported image picker.
 *
 * PYQAlbum registers exported ACTION_GET_CONTENT / ACTION_PICK filters in the
 * manifest, so browsers and other apps can offer it as a photo source. This
 * activity answers those requests with a browsable view of the library — by
 * folder, by tag or all photos — and returns the chosen content URI(s) with a
 * read grant, the contract every picker client expects.
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

    var data by remember { mutableStateOf<PickerData?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tab by remember { mutableStateOf(PickTab.FOLDERS) }
    var openFolder by remember { mutableStateOf<String?>(null) }
    var openTagId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        data = withContext(Dispatchers.IO) { loadPickerData(context) }
    }

    val openFolderEntry = data?.folders?.firstOrNull { it.name == openFolder }
    val openTagEntry = data?.tags?.firstOrNull { it.id == openTagId }
    val openCollectionName = openFolderEntry?.name ?: openTagEntry?.name
    val collectionPhotos = openFolderEntry?.photos ?: openTagEntry?.photos

    BackHandler(enabled = openCollectionName != null) {
        openFolder = null
        openTagId = null
    }

    val toggle: (PickPhoto) -> Unit = { photo ->
        selected = if (photo.uri in selected) selected - photo.uri else selected + photo.uri
    }
    val tap: (PickPhoto) -> Unit = { photo ->
        if (allowMultiple) toggle(photo) else onPick(listOf(Uri.parse(photo.uri)))
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
            if (openCollectionName != null) {
                IconButton(onClick = {
                    openFolder = null
                    openTagId = null
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                text = openCollectionName ?: "Choose from PYQAlbum",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        val current = data
        when {
            !granted -> PermissionBody(askAgain) { permissionLauncher.launch(permission) }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            collectionPhotos != null -> {
                if (collectionPhotos.isEmpty()) {
                    EmptyBody("No photos in this ${if (openTagEntry != null) "tag" else "folder"}")
                } else {
                    PhotoGrid(
                        photos = collectionPhotos,
                        allowMultiple = allowMultiple,
                        selected = selected,
                        onTap = tap,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            else -> {
                BrowseTabs(tab) { tab = it }
                when (tab) {
                    PickTab.FOLDERS -> {
                        if (current.folders.isEmpty()) {
                            EmptyBody("No photo folders found")
                        } else {
                            FolderGrid(
                                folders = current.folders,
                                allowMultiple = allowMultiple,
                                selected = selected,
                                onOpen = { openFolder = it.name },
                                onTapPhoto = tap,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    PickTab.TAGS -> {
                        if (current.tags.isEmpty()) {
                            EmptyBody("No tags yet — add tags in PYQAlbum first")
                        } else {
                            TagGrid(current.tags, Modifier.weight(1f)) { openTagId = it.id }
                        }
                    }
                    PickTab.ALL -> {
                        if (current.photos.isEmpty()) {
                            EmptyBody("No photos found on this device")
                        } else {
                            PhotoGrid(
                                photos = current.photos,
                                allowMultiple = allowMultiple,
                                selected = selected,
                                onTap = tap,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        if (allowMultiple && data != null) {
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

@Composable
private fun BrowseTabs(current: PickTab, onSelect: (PickTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PickTab.entries.forEach { entry ->
            val active = entry == current
            TextButton(
                onClick = { onSelect(entry) },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent
                    )
            ) {
                Text(
                    entry.label,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FolderGrid(
    folders: List<PickFolder>,
    allowMultiple: Boolean,
    selected: Set<String>,
    onOpen: (PickFolder) -> Unit,
    onTapPhoto: (PickPhoto) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        items(folders, key = { it.name }) { folder ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(folder) }
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val cover = folder.cover
                    if (cover != null) {
                        AsyncImage(
                            model = Uri.parse(cover.uri),
                            contentDescription = folder.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    // A folder is a container, not a photo: tapping it opens the
                    // folder. The overlay chip jumps straight to its first photo.
                    if (!allowMultiple && folder.photos.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                .clickable { onTapPhoto(folder.photos.first()) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Pick first",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (allowMultiple) {
                        val count = folder.photos.count { it.uri in selected }
                        if (count > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "$count",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                )
                Text(
                    text = "${folder.photos.size} photo(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TagGrid(
    tags: List<PickTag>,
    modifier: Modifier = Modifier,
    onOpen: (PickTag) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
    ) {
        items(tags, key = { it.id }) { tag ->
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable { onOpen(tag) }
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (tag.id == -1L) Icons.Filled.Favorite else Icons.Filled.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.padding(4.dp))
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.padding(2.dp))
                Text(
                    text = "${tag.photos.size} photo(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                val cover = tag.cover
                if (cover != null) {
                    AsyncImage(
                        model = Uri.parse(cover.uri),
                        contentDescription = tag.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .aspectRatio(1.6f)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<PickPhoto>,
    allowMultiple: Boolean,
    selected: Set<String>,
    onTap: (PickPhoto) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
    ) {
        items(photos, key = { it.uri }) { item ->
            val isSelected = item.uri in selected
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onTap(item) }
            ) {
                AsyncImage(
                    model = Uri.parse(item.uri),
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
}

@Composable
private fun EmptyBody(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun loadImages(context: Context): List<PickPhoto> {
    val out = mutableListOf<PickPhoto>()
    val bucketColumn = MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        bucketColumn
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
            val bucketCol = cursor.getColumnIndex(bucketColumn)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                val bucket = if (bucketCol >= 0) cursor.getString(bucketCol) else null
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                out += PickPhoto(
                    uri = uri.toString(),
                    name = name ?: "Image",
                    folder = bucket?.takeIf { it.isNotBlank() } ?: "Other"
                )
            }
        }
    } catch (e: Exception) {
        // An unreadable provider must not crash the picker — return what we got.
    }
    return out
}

/** All photos plus the folder and tag groupings, loaded off the main thread. */
private suspend fun loadPickerData(context: Context): PickerData {
    val photos = loadImages(context)
    val byUri = photos.associateBy { it.uri }

    val folders = photos
        .groupBy { it.folder }
        .map { (name, list) -> PickFolder(name, list) }
        .sortedBy { it.name.lowercase() }

    val tags = try {
        val dao = AppDatabase.getInstance(context).tagDao()
        dao.getAllTagsOnce().mapNotNull { tag ->
            val uris = dao.getImageUrisForTag(tag.id)
            val tagged = uris.map { uri ->
                byUri[uri] ?: PickPhoto(uri, uri.substringAfterLast('/'), "")
            }
            if (tagged.isEmpty()) null else PickTag(tag.id, tag.name, tagged)
        }
    } catch (e: Exception) {
        // A missing/unreadable database must not crash the picker.
        emptyList()
    }

    // Favourites are not a Room tag: they live in the MediaStore IS_FAVORITE
    // flag. Surface them in the same Tags tab so an external app can pick them.
    val favourites = try {
        FavouriteStore.loadFavouriteUris(context).map { uri ->
            byUri[uri] ?: PickPhoto(uri, uri.substringAfterLast('/'), "")
        }
    } catch (e: Exception) {
        emptyList()
    }
    val tagEntries =
        if (favourites.isEmpty()) tags
        else listOf(PickTag(-1L, "Favourites", favourites)) + tags

    return PickerData(photos, folders, tagEntries)
}