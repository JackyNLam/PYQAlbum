package com.pyqcr.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.AppSnackbarHost
import com.pyqcr.util.WechatGridEngine
import com.pyqcr.util.WechatGridSelectionStore
import kotlinx.coroutines.launch

/**
 * WeChat Grid — native port of https://jackynlam.github.io/WechatGrid/.
 *
 * One center photo plus 18–90 hidden photos become nine long JPEGs. All nine
 * carry the same center tile, so WeChat shows the center photo in its grid
 * preview, while opening any single one reveals the photo hidden below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WechatGridScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images = it }
    }

    // The photo pool. The folder view's batch menu (⋮ → WeChat Grid) and the
    // viewer's swipe-up bar ADD to it, so the next batch grows the same pool
    // instead of replacing it. Both pickers below list this pool and nothing
    // else — the rest of the gallery is never offered inside this screen.
    var pool by remember { mutableStateOf(WechatGridSelectionStore.loadPool(context)) }

    // Drop entries whose photo is gone (deleted elsewhere) so the picker never
    // shows an unopenable tile, and write the pruned pool back. Only image URIs
    // are pruned: the folder grid's batch selection can include videos, which
    // are not part of `images` but must stay in the pool.
    LaunchedEffect(images, pool) {
        if (images.isEmpty() || pool.isEmpty()) return@LaunchedEffect
        val alive = images.map { it.uri }.toSet()
        val kept = pool.filter { it in alive || !it.contains("/images/") }
        if (kept.size != pool.size) {
            WechatGridSelectionStore.savePool(context, kept)
            pool = kept
        }
    }

    val poolImages = remember(pool, images) {
        val byUri = images.associateBy { it.uri }
        pool.mapNotNull { byUri[it] }
    }

    var mode by remember { mutableIntStateOf(54) }
    var centerUri by remember { mutableStateOf<String?>(null) }
    var others by remember { mutableStateOf<List<String>>(emptyList()) }
    var tileWidthText by remember { mutableStateOf("${WechatGridEngine.DEFAULT_TILE}") }
    var tileHeightText by remember { mutableStateOf("${WechatGridEngine.DEFAULT_TILE}") }
    var rotatePortrait by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModeMenu by remember { mutableStateOf(false) }
    var showReorder by remember { mutableStateOf(false) }
    var previewUri by remember { mutableStateOf<String?>(null) }

    // Seed the grid from the pool once the library has loaded: the first photo
    // becomes the center photo, the rest fill the hidden slots in pool order.
    // One shot — later edits inside this screen must not be overwritten.
    var seededFromPool by remember { mutableStateOf(false) }
    LaunchedEffect(poolImages) {
        if (seededFromPool || poolImages.isEmpty()) return@LaunchedEffect
        seededFromPool = true
        centerUri = poolImages.first().uri
        val hidden = poolImages.drop(1).map { it.uri }.take(WechatGridEngine.MODES.last())
        if (hidden.isNotEmpty()) {
            others = hidden
            mode = WechatGridEngine.MODES.firstOrNull { it >= hidden.size }
                ?: WechatGridEngine.MODES.last()
        }
    }

    // Which list the picker is editing: the single center photo or the hidden
    // photos. Null = the main screen is showing.
    var pickerForCenter by remember { mutableStateOf<Boolean?>(null) }
    var pickerSelection by remember { mutableStateOf<List<String>>(emptyList()) }

    fun shareResults() {
        if (results.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList(results.map { Uri.parse(it) })
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share moments"))
    }

    fun generate() {
        val center = centerUri ?: return
        if (others.size != mode) return
        val tileW = tileWidthText.toIntOrNull() ?: WechatGridEngine.DEFAULT_TILE
        val tileH = tileHeightText.toIntOrNull() ?: WechatGridEngine.DEFAULT_TILE
        scope.launch {
            busy = true
            status = "Preparing…"
            val result = WechatGridEngine.generate(
                context = context,
                centerUri = center,
                others = others,
                tileWidth = tileW,
                tileHeight = tileH,
                rotatePortrait = rotatePortrait
            ) { _, message -> status = message }
            busy = false
            if (result == null) {
                status = ""
                snackbarHostState.showSnackbar("Could not generate the images")
            } else {
                results = result.uris
                status = buildString {
                    append("Saved 9 images to Pictures/PYQAlbum/WechatGrid")
                    if (result.capped) {
                        append(" — tile size reduced to ${result.tileWidth}×${result.tileHeight} to fit memory")
                    }
                    if (result.unreadable > 0) {
                        append(" — ${result.unreadable} photo(s) could not be read")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WeChat Grid") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) }
    ) { padding ->

        val editingCenter = pickerForCenter
        if (editingCenter != null) {
            WechatGridPicker(
                title = if (editingCenter) "Choose the center photo" else "Choose hidden photos",
                images = poolImages,
                selected = pickerSelection,
                limit = if (editingCenter) 1 else mode,
                padding = padding,
                onToggle = { uri ->
                    pickerSelection = if (editingCenter) {
                        if (pickerSelection.firstOrNull() == uri) emptyList() else listOf(uri)
                    } else if (uri in pickerSelection) {
                        pickerSelection - uri
                    } else if (pickerSelection.size < mode) {
                        pickerSelection + uri
                    } else {
                        pickerSelection
                    }
                },
                onCancel = { pickerForCenter = null },
                onDone = {
                    if (editingCenter) {
                        centerUri = pickerSelection.firstOrNull()
                    } else {
                        others = pickerSelection
                    }
                    pickerForCenter = null
                }
            )
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Every one of the nine images shows the center photo as its " +
                        "middle tile, so WeChat's 3×3 preview looks like a single " +
                        "picture — opening any of them reveals the photo hidden " +
                        "underneath. Nothing leaves the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- center photo ----
            item {
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text("Center photo", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                val center = centerUri
                                if (center != null) {
                                    AsyncImage(
                                        model = center,
                                        contentDescription = "Center photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.Image, contentDescription = null)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (centerUri == null) "Not selected" else "Selected",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "Sits in the middle of all nine images",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                pickerSelection = listOfNotNull(centerUri)
                                pickerForCenter = true
                            }) {
                                Text(if (centerUri == null) "Choose" else "Change")
                            }
                        }
                    }
                }
            }

            // ---- pool ----
            // Where the photos come from: the folder view's batch menu and a
            // photo's WeChat Grid button add to this pool, and both "Choose"
            // pickers above/list below it. Clear pool empties it for good.
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    Text(
                        "Pool: ${pool.size} photo(s) — the only photos " +
                            "the pickers offer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (pool.isNotEmpty()) {
                        TextButton(onClick = {
                            WechatGridSelectionStore.clear(context)
                            pool = emptyList()
                            centerUri = null
                            others = emptyList()
                            results = emptyList()
                            status = ""
                        }) { Text("Clear pool") }
                    }
                }
            }

            // ---- hidden photos ----
            item {
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Hidden photos", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${others.size} / $mode selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                pickerSelection = others
                                pickerForCenter = false
                            }) {
                                Text(if (others.isEmpty()) "Choose" else "Edit")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (mode == 0) 0f else (others.size.toFloat() / mode).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (others.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                others.forEachIndexed { index, uri ->
                                    Box {
                                        AsyncImage(
                                            model = uri,
                                            contentDescription = "Hidden photo ${index + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                        Text(
                                            "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White,
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .padding(horizontal = 3.dp)
                                        )
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove",
                                            tint = Color.White,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(18.dp)
                                                .background(Color.Black.copy(alpha = 0.55f))
                                                .clickable {
                                                    others = others.filterIndexed { i, _ -> i != index }
                                                }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            TextButton(
                                onClick = { showReorder = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.SwapVert, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Reorder")
                            }
                        }
                        Text(
                            "Top-to-bottom in each image follows this order.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- settings ----
            item {
                ElevatedCard {
                    Column(Modifier.padding(12.dp)) {
                        Text("Output", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tileWidthText,
                                onValueChange = { tileWidthText = it.filter(Char::isDigit) },
                                label = { Text("Tile width") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = tileHeightText,
                                onValueChange = { tileHeightText = it.filter(Char::isDigit) },
                                label = { Text("Tile height") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box {
                            OutlinedButton(
                                onClick = { showModeMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    WechatGridEngine.modeLabel(mode),
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showModeMenu,
                                onDismissRequest = { showModeMenu = false }
                            ) {
                                WechatGridEngine.MODES.forEach { total ->
                                    DropdownMenuItem(
                                        text = { Text(WechatGridEngine.modeLabel(total)) },
                                        onClick = {
                                            mode = total
                                            if (others.size > total) others = others.take(total)
                                            showModeMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Portrait photos", Modifier.weight(1f))
                            FilterChip(
                                selected = !rotatePortrait,
                                onClick = { rotatePortrait = false },
                                label = { Text("Keep") }
                            )
                            Spacer(Modifier.width(6.dp))
                            FilterChip(
                                selected = rotatePortrait,
                                onClick = { rotatePortrait = true },
                                label = { Text("Rotate") }
                            )
                        }
                    }
                }
            }

            // ---- generate ----
            item {
                Column {
                    Button(
                        onClick = { generate() },
                        enabled = !busy && centerUri != null && others.size == mode,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Generating…" else "Generate 9 images")
                    }
                    if (busy) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (status.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (centerUri != null && others.size != mode) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Select ${mode - others.size} more hidden photo(s).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ---- results ----
            if (results.isNotEmpty()) {
                item {
                    ElevatedCard {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Generated images",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { shareResults() }) {
                                    Icon(Icons.Default.Share, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share all")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(results) { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Generated image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .aspectRatio(0.35f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { previewUri = uri }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        val preview = previewUri
        if (preview != null) {
            Dialog(onDismissRequest = { previewUri = null }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable { previewUri = null },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = preview,
                        contentDescription = "Generated image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (showReorder) {
            ReorderDialog(
                uris = others,
                onMove = { from, to ->
                    val list = others.toMutableList()
                    if (from in list.indices && to in list.indices) {
                        val item = list.removeAt(from)
                        list.add(to, item)
                        others = list
                    }
                },
                onRemove = { index ->
                    others = others.filterIndexed { i, _ -> i != index }
                },
                onDismiss = { showReorder = false }
            )
        }
    }
}

@Composable
private fun WechatGridPicker(
    title: String,
    images: List<ImageItem>,
    selected: List<String>,
    limit: Int,
    padding: PaddingValues,
    onToggle: (String) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${selected.size} / $limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
        if (images.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "The pool is empty. Add photos from the folder view's ⋮ menu " +
                        "(WeChat Grid) or from a photo's WeChat Grid button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(images, key = { _, image -> image.uri }) { _, image ->
                val index = selected.indexOf(image.uri)
                val isSelected = index >= 0
                Box {
                    AsyncImage(
                        model = image.uri,
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(6.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onToggle(image.uri) }
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ReorderDialog(
    uris: List<String>,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Reorder hidden photos", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(uris.size) { index ->
                        val uri = uris[index]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${index + 1}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(
                                onClick = { onMove(index, index - 1) },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { onMove(index, index + 1) },
                                enabled = index < uris.lastIndex
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Move down")
                            }
                            IconButton(onClick = { onRemove(index) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
}