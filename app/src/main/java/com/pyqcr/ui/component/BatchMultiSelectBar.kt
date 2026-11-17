package com.pyqcr.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A submenu opener row: label plus a "there is more" arrow, as in the sort menu. */
@Composable
private fun GroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

/** The batch menu's collapsed action groups, kept out of the top level. */
private enum class BatchSubMenu(val label: String) {
    AI_TOOLS("AI & Tools"),
    SIZE("Resize & Collage"),
    ORGANIZE("Organize")
}

@Composable
fun BatchMultiSelectBar(
    selectedCount: Int,
    allContextSelected: Boolean,
    isRecycleBin: Boolean = false,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit = {},
    onCollage: () -> Unit,
    onTag: () -> Unit,
    onRate: () -> Unit,
    onFavourite: () -> Unit,
    onSelectForAi: () -> Unit,
    onResize: () -> Unit,
    onRename: () -> Unit,
    onAiEdit: () -> Unit,
    onEditImages: () -> Unit,
    onWechatGrid: () -> Unit,
    onCopyTo: () -> Unit,
    onMoveTo: () -> Unit,
    onMoveToVault: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var subMenu by remember { mutableStateOf<BatchSubMenu?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .navigationBarsPadding()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Small action button — left side
            Box {
                IconButton(
                    onClick = { subMenu = null; showMenu = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false; subMenu = null }
                ) {
                    if (subMenu == null) {
                        DropdownMenuItem(
                            onClick = { showMenu = false; onSelectAll() },
                            text = { Text(if (allContextSelected) "Deselect All" else "Select All") },
                            leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                    if (isRecycleBin) {
                        // Inside the recycle bin only Restore + permanent Delete apply
                        HorizontalDivider()
                        DropdownMenuItem(
                            onClick = { showMenu = false; onRestore() },
                            text = { Text("Restore") },
                            leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        DropdownMenuItem(
                            onClick = { showMenu = false; onDelete() },
                            text = { Text("Delete permanently", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    } else {
                        // Less-used actions live in three submenus; a group opens
                        // inside the same dropdown (the sort menu's "Group by"
                        // does the same) so nothing floats over the grid.
                        val group = subMenu
                        if (group == null) {
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = { showMenu = false; onTag() },
                                text = { Text("Tag") },
                                leadingIcon = { Icon(Icons.Default.Label, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                onClick = { showMenu = false; onRate() },
                                text = { Text("Rate") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                onClick = { showMenu = false; onFavourite() },
                                text = { Text("Favourite") },
                                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = { subMenu = BatchSubMenu.AI_TOOLS },
                                text = { GroupLabel(BatchSubMenu.AI_TOOLS.label) },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                onClick = { subMenu = BatchSubMenu.SIZE },
                                text = { GroupLabel(BatchSubMenu.SIZE.label) },
                                leadingIcon = { Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenuItem(
                                onClick = { subMenu = BatchSubMenu.ORGANIZE },
                                text = { GroupLabel(BatchSubMenu.ORGANIZE.label) },
                                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                onClick = { showMenu = false; onDelete() },
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                onClick = { subMenu = null },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(group.label, modifier = Modifier.weight(1f))
                                    }
                                }
                            )
                            HorizontalDivider()
                            when (group) {
                                BatchSubMenu.AI_TOOLS -> {
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onSelectForAi() },
                                        text = { Text("AI Rating") },
                                        leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onAiEdit() },
                                        text = { Text("AI Edit") },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onEditImages() },
                                        text = { Text("Edit images") },
                                        leadingIcon = { Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onWechatGrid() },
                                        text = { Text("WeChat Grid") },
                                        leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                                BatchSubMenu.SIZE -> {
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onResize() },
                                        text = { Text("Resize") },
                                        leadingIcon = { Icon(Icons.Default.PhotoSizeSelectLarge, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onCollage() },
                                        text = { Text("Collage") },
                                        leadingIcon = { Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                                BatchSubMenu.ORGANIZE -> {
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onRename() },
                                        enabled = selectedCount == 1,
                                        text = { Text("Rename") },
                                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onCopyTo() },
                                        text = { Text("Copy to…") },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onMoveTo() },
                                        text = { Text("Move to…") },
                                        leadingIcon = { Icon(Icons.Default.Forward, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                    DropdownMenuItem(
                                        onClick = { showMenu = false; onMoveToVault() },
                                        text = { Text("Move to Vault") },
                                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Selected count — centered
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally)
            )

            // Cancel button — right side
            TextButton(onClick = onCancel, modifier = Modifier.height(34.dp)) {
                Text("Cancel")
            }
        }
    }
}
