package com.pyqcr.ui.component

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One real destination directory, resolved from a MediaStore bucket. */
private data class DestinationFolder(
    val name: String,
    val dir: File,
    val count: Int,
    val lastModified: Long
)

/**
 * Copy/Move destination picker that opens on the SAME view as the album's
 * folder browse: a two-column grid of the user's media folders (folder icon,
 * name, item count), newest first. Tapping a folder copies/moves straight
 * into it. "All folders" switches to the filesystem browser (parent/child
 * navigation plus inline folder creation), and "System" still opens the SAF
 * tree picker for locations the File API cannot reach.
 */
@Composable
fun CopyMoveDestinationDialog(
    isCopy: Boolean,
    onDismiss: () -> Unit,
    onPickFolder: (File) -> Unit,
    onPickSystem: () -> Unit
) {
    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }

    // false = the album folder view (first screen), true = filesystem browser.
    var browsing by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf(root) }
    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val libraryFolders by produceState<List<DestinationFolder>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadDestinationFolders(context) }
    }

    val isRoot = currentDir == root
    val subDirs = remember(currentDir) {
        currentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(0.94f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isCopy) "Copy to folder" else "Move to folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))

                if (!browsing) {
                    Text(
                        text = "Your album folders — pick one to continue",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    val folders = libraryFolders
                    if (folders == null) {
                        Text(
                            text = "Loading folders…",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(max = 400.dp)
                        ) {
                            items(folders, key = { it.dir.absolutePath }) { folder ->
                                DestinationFolderCard(
                                    name = folder.name,
                                    detail = "${folder.count} items",
                                    icon = Icons.Default.Folder,
                                    onClick = { onPickFolder(folder.dir) }
                                )
                            }
                            item {
                                DestinationFolderCard(
                                    name = "All folders",
                                    detail = "Browse the file system",
                                    icon = Icons.Default.FolderOpen,
                                    onClick = { browsing = true }
                                )
                            }
                            item {
                                DestinationFolderCard(
                                    name = "System picker",
                                    detail = "Choose any location",
                                    icon = Icons.Default.FolderOpen,
                                    onClick = onPickSystem
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                currentDir.parentFile?.let { currentDir = it }
                            },
                            enabled = !isRoot
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "Parent folder",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Up", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { creatingFolder = !creatingFolder }) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("New folder", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (creatingFolder) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it },
                                singleLine = true,
                                label = { Text("Folder name") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                val name = newFolderName.trim()
                                    .replace("/", "_")
                                    .replace("\\", "_")
                                if (name.isNotEmpty()) {
                                    val dir = File(currentDir, name)
                                    if (dir.exists() || dir.mkdirs()) {
                                        currentDir = dir
                                        newFolderName = ""
                                        creatingFolder = false
                                    }
                                }
                            }) { Text("Create") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    if (subDirs.isEmpty()) {
                        Text(
                            text = "No sub-folders here — you can still use this folder " +
                                "or create a new one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(max = 360.dp)
                        ) {
                            items(subDirs, key = { it.absolutePath }) { dir ->
                                DestinationFolderCard(
                                    name = dir.name,
                                    detail = null,
                                    icon = Icons.Default.Folder,
                                    onClick = { currentDir = dir }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (browsing) {
                        OutlinedButton(
                            onClick = {
                                browsing = false
                                currentDir = root
                            },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Text("Album folders", style = MaterialTheme.typography.labelMedium)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPickSystem,
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("System", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (browsing) {
                        TextButton(onClick = { onPickFolder(currentDir) }) {
                            Text("Select folder")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationFolderCard(
    name: String,
    detail: String?,
    icon: ImageVector,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Media folders (bucket display name + real directory) from the Images and
 * Video collections, newest first — the same set the album folder view shows.
 * Empty / unresolvable rows are skipped so every card is a writable target.
 */
private fun loadDestinationFolders(context: Context): List<DestinationFolder> {
    val root = Environment.getExternalStorageDirectory()
    val found = LinkedHashMap<String, DestinationFolder>()
    val collections: List<Uri> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )
    val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    for (collection in collections) {
        try {
            val projection = if (hasRelativePath) {
                arrayOf(
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
            } else {
                arrayOf(
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
            }
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val relCol = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)

                while (c.moveToNext()) {
                    val dir = if (hasRelativePath && relCol >= 0) {
                        val rel = c.getString(relCol).orEmpty().trimEnd('/')
                        if (rel.isEmpty()) root else File(root, rel)
                    } else {
                        val data = if (dataCol >= 0) c.getString(dataCol) else null
                        if (data.isNullOrBlank()) continue
                        File(data).parentFile ?: continue
                    }
                    if (!dir.isDirectory) continue
                    val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: dir.name
                    val modified = c.getLong(dateCol) * 1000L
                    val prev = found[dir.absolutePath]
                    found[dir.absolutePath] = DestinationFolder(
                        name = name,
                        dir = dir,
                        count = (prev?.count ?: 0) + 1,
                        lastModified = maxOf(prev?.lastModified ?: 0L, modified)
                    )
                }
            }
        } catch (e: Exception) {
            // One unreadable collection must not hide the other's folders.
        }
    }
    return found.values.sortedByDescending { it.lastModified }
}