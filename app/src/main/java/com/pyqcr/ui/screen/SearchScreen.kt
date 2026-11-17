package com.pyqcr.ui.screen

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import com.pyqcr.ui.component.AppSnackbarHost
import com.pyqcr.ui.component.BatchMultiSelectBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Result order — the criteria the search form itself offers. */
private enum class SearchSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name A–Z"),
    SIZE_BIG("Largest first"),
    SIZE_SMALL("Smallest first"),
    RATING("Rating high–low"),
    AI_SCORE("AI score high–low")
}

/**
 * The criteria one Search press actually ran with. The form's own fields stay
 * free to edit without touching the list — the library is only re-filtered
 * when Search is pressed, which keeps a big library from being scanned on
 * every keystroke.
 */
private data class AppliedSearch(
    val name: String,
    val folder: String,
    val dateFrom: String,
    val dateTo: String,
    val minSizeMb: String,
    val maxSizeMb: String,
    val minRating: Float,
    val minAiScore: Float
)

/**
 * Library search from the left drawer.
 *
 * Every criterion the gallery stores is filterable here: filename, folder name,
 * date (a range, typed or with the quick chips), file size, star rating and AI
 * score. Filters combine with AND, results only appear after the Search button
 * is pressed, and every result is a row the album shows, so it always opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onImageClick: (uri: String, uris: List<String>) -> Unit,
    onNavigateToImageEdit: () -> Unit = {},
    onNavigateToWechatGrid: () -> Unit = {},
    onNavigateToAiEdit: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images = it }
    }

    // Videos are not in the Room images table, so they are read live for the
    // whole library (getFolderVideos(null)). They carry no rating/AI score, so
    // those two criteria naturally hide them.
    var videos by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        videos = withContext(Dispatchers.IO) { repository.getFolderVideos(null) }
    }

    val library = remember(images, videos) { images + videos }

    var nameQuery by remember { mutableStateOf("") }
    var folderQuery by remember { mutableStateOf("") }
    var dateFrom by remember { mutableStateOf("") }
    var dateTo by remember { mutableStateOf("") }
    var minSizeMb by remember { mutableStateOf("") }
    var maxSizeMb by remember { mutableStateOf("") }
    var minRating by remember { mutableFloatStateOf(0f) }
    var minAiScore by remember { mutableFloatStateOf(0f) }
    var sort by remember { mutableStateOf(SearchSort.NEWEST) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showFolderMenu by remember { mutableStateOf(false) }
    // The criteria the last Search press ran with. Null until then, so the
    // list is built from this snapshot and never from the live form fields.
    var applied by remember { mutableStateOf<AppliedSearch?>(null) }

    // ---- multi-select (the folder view's batch actions) -------------------
    var multiSelect by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchAction by remember { mutableStateOf<SearchBatchAction?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A new search starts a fresh selection: acting on rows that are no longer
    // on screen would be a surprise.
    LaunchedEffect(applied) {
        multiSelect = false
        selection = emptySet()
    }

    // Rows that left the result set (deleted, moved, or no longer matching)
    // drop out of the selection, so the count never counts ghosts.
    LaunchedEffect(results) {
        if (selection.isNotEmpty()) {
            val visible = results.map { it.uri }.toSet()
            if (!visible.containsAll(selection)) selection = selection.intersect(visible)
        }
    }

    /** Filter the library with exactly what the form holds right now. */
    fun runSearch() {
        applied = AppliedSearch(
            name = nameQuery.trim(),
            folder = folderQuery.trim(),
            dateFrom = dateFrom.trim(),
            dateTo = dateTo.trim(),
            minSizeMb = minSizeMb.trim(),
            maxSizeMb = maxSizeMb.trim(),
            minRating = minRating,
            minAiScore = minAiScore
        )
    }

    fun clearFilters() {
        nameQuery = ""
        folderQuery = ""
        dateFrom = ""
        dateTo = ""
        minSizeMb = ""
        maxSizeMb = ""
        minRating = 0f
        minAiScore = 0f
        applied = null
    }

    val hasFilters = nameQuery.isNotBlank() || folderQuery.isNotBlank() ||
        dateFrom.isNotBlank() || dateTo.isNotBlank() || minSizeMb.isNotBlank() ||
        maxSizeMb.isNotBlank() || minRating > 0f || minAiScore > 0f

    val folders = remember(library) {
        library.map { it.folderName }.distinct().sortedBy { it.lowercase() }
    }

    val results: List<ImageItem> = remember(library, applied, sort) {
        val query = applied
        if (query == null) {
            emptyList()
        } else {
            val from = parseDateBound(query.dateFrom, endOfDay = false)
            val to = parseDateBound(query.dateTo, endOfDay = true)
            val minBytes = query.minSizeMb.toFloatOrNull()?.let { it * 1048576f }
            val maxBytes = query.maxSizeMb.toFloatOrNull()?.let { it * 1048576f }
            val name = query.name.lowercase()
            val folder = query.folder.lowercase()
            val matched = library.filter { item ->
                (name.isEmpty() || item.displayName.lowercase().contains(name)) &&
                    (folder.isEmpty() || item.folderName.lowercase().contains(folder)) &&
                    (from == null || item.dateAdded >= from) &&
                    (to == null || item.dateAdded <= to) &&
                    (minBytes == null || item.sizeBytes >= minBytes.toLong()) &&
                    (maxBytes == null || item.sizeBytes <= maxBytes.toLong()) &&
                    (query.minRating <= 0f || item.rating >= query.minRating) &&
                    (query.minAiScore <= 0f || (item.aiScore ?: -1f) >= query.minAiScore)
            }
            when (sort) {
                SearchSort.NEWEST -> matched.sortedByDescending { it.dateAdded }
                SearchSort.OLDEST -> matched.sortedBy { it.dateAdded }
                SearchSort.NAME -> matched.sortedBy { it.displayName.lowercase() }
                SearchSort.SIZE_BIG -> matched.sortedByDescending { it.sizeBytes }
                SearchSort.SIZE_SMALL -> matched.sortedBy { it.sizeBytes }
                SearchSort.RATING -> matched.sortedByDescending { it.rating }
                SearchSort.AI_SCORE -> matched.sortedByDescending { it.aiScore ?: -1f }
            }
        }
    }

    val resultUris = remember(results) { results.map { it.uri } }

    /** Quick date chips write the two fields, so the range stays editable. */
    fun setLastDays(days: Int) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        if (days <= 1) {
            dateFrom = today
            dateTo = today
        } else {
            cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
            dateFrom = fmt.format(cal.time)
            dateTo = today
        }
    }

    fun setThisYear() {
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date())
        dateFrom = "$year-01-01"
        dateTo = "$year-12-31"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (applied == null) "Search" else "Search results") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (applied != null) {
                        // Back to the form with everything still filled in — the
                        // criteria and the result list never share the screen.
                        TextButton(onClick = { applied = null }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Filters")
                        }
                    }
                }
            )
        },
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        bottomBar = {
            if (multiSelect) {
                BatchMultiSelectBar(
                    selectedCount = selection.size,
                    allContextSelected = selection.size == results.size && results.isNotEmpty(),
                    onCancel = {
                        multiSelect = false
                        selection = emptySet()
                    },
                    onSelectAll = {
                        selection = if (selection.size == results.size) emptySet()
                        else results.map { it.uri }.toSet()
                    },
                    onRestore = {},
                    onCollage = { batchAction = SearchBatchAction.COLLAGE },
                    onTag = { batchAction = SearchBatchAction.TAG },
                    onRate = { batchAction = SearchBatchAction.RATE },
                    onFavourite = { batchAction = SearchBatchAction.FAVOURITE },
                    onDelete = { batchAction = SearchBatchAction.DELETE },
                    onSelectForAi = { batchAction = SearchBatchAction.AI_RATING },
                    onAiEdit = { batchAction = SearchBatchAction.AI_EDIT },
                    onEditImages = { batchAction = SearchBatchAction.EDIT_IMAGES },
                    onWechatGrid = { batchAction = SearchBatchAction.WECHAT_GRID },
                    onResize = { batchAction = SearchBatchAction.RESIZE },
                    onRename = { batchAction = SearchBatchAction.RENAME },
                    onCopyTo = { batchAction = SearchBatchAction.COPY },
                    onMoveTo = { batchAction = SearchBatchAction.MOVE },
                    onMoveToVault = { batchAction = SearchBatchAction.VAULT }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (applied == null) {
                // ---- criteria only ------------------------------------------
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nameQuery,
                        onValueChange = { nameQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Filename contains") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (nameQuery.isNotEmpty()) {
                                IconButton(onClick = { nameQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                        // The keyboard's Search key runs exactly what the button
                        // runs — the list never changes while typing.
                        keyboardActions = KeyboardActions(onSearch = { runSearch() })
                    )

                    Box {
                        OutlinedTextField(
                            value = folderQuery,
                            onValueChange = { folderQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Folder name contains") },
                            trailingIcon = {
                                IconButton(onClick = { showFolderMenu = true }) {
                                    Icon(Icons.Default.ExpandMore, contentDescription = "Folders")
                                }
                            }
                        )
                        DropdownMenu(
                            expanded = showFolderMenu,
                            onDismissRequest = { showFolderMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("All folders") },
                                onClick = {
                                    folderQuery = ""
                                    showFolderMenu = false
                                }
                            )
                            folders.forEach { folder ->
                                DropdownMenuItem(
                                    text = { Text(folder) },
                                    onClick = {
                                        folderQuery = folder
                                        showFolderMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = dateFrom,
                            onValueChange = { dateFrom = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("From") },
                            placeholder = { Text("2025-01-31") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = dateTo,
                            onValueChange = { dateTo = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("To") },
                            placeholder = { Text("2025-12-31") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    val badFrom = dateFrom.isNotBlank() && parseDateBound(dateFrom, false) == null
                    val badTo = dateTo.isNotBlank() && parseDateBound(dateTo, true) == null
                    if (badFrom || badTo) {
                        Text(
                            "Date needs yyyy-MM-dd, yyyy-MM or yyyy",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        Chip("Today") { setLastDays(1) }
                        Chip("7 days") { setLastDays(7) }
                        Chip("30 days") { setLastDays(30) }
                        Chip("This year") { setThisYear() }
                        Chip("Any date") {
                            dateFrom = ""
                            dateTo = ""
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = minSizeMb,
                            onValueChange = { minSizeMb = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Min MB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = maxSizeMb,
                            onValueChange = { maxSizeMb = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Max MB") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        Chip("< 1 MB") {
                            minSizeMb = ""
                            maxSizeMb = "1"
                        }
                        Chip("1–10 MB") {
                            minSizeMb = "1"
                            maxSizeMb = "10"
                        }
                        Chip("10–50 MB") {
                            minSizeMb = "10"
                            maxSizeMb = "50"
                        }
                        Chip("> 50 MB") {
                            minSizeMb = "50"
                            maxSizeMb = ""
                        }
                        Chip("Any size") {
                            minSizeMb = ""
                            maxSizeMb = ""
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (minRating <= 0f) "Any rating" else "Rating ≥ %.1f ★".format(minRating),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(120.dp)
                        )
                        (1..5).forEach { stars ->
                            FilterChip(
                                selected = minRating == stars.toFloat(),
                                onClick = {
                                    minRating = if (minRating == stars.toFloat()) 0f else stars.toFloat()
                                },
                                label = { Text("$stars★") }
                            )
                        }
                    }

                    Text(
                        text = if (minAiScore <= 0f) "Any AI score"
                        else "AI score ≥ ${minAiScore.toInt()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = minAiScore,
                        onValueChange = { minAiScore = it },
                        valueRange = 0f..100f,
                        steps = 19
                    )
                    Spacer(Modifier.height(4.dp))
                    // The only thing that runs a search: pressing it snapshots the
                    // form and switches this screen to the result list. Typing in a
                    // field never re-filters the library.
                    Button(
                        onClick = { runSearch() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Search")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                // ---- results only -------------------------------------------
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (multiSelect) "${selection.size} selected"
                            else "${results.size} of ${library.size}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (!multiSelect && results.isNotEmpty()) {
                            TextButton(onClick = {
                                multiSelect = true
                                selection = emptySet()
                            }) { Text("Select") }
                        }
                        Box {
                            TextButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(sort.label, style = MaterialTheme.typography.bodyMedium)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SearchSort.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            sort = option
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        if (hasFilters) {
                            OutlinedButton(onClick = { clearFilters() }) {
                                Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Clear")
                            }
                        }
                    }

                    if (results.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (library.isEmpty()) "No items in the library yet."
                                else "Nothing matches these filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(results, key = { it.uri }) { item ->
                                val checked = item.uri in selection
                                SearchResultRow(
                                    item = item,
                                    multiSelect = multiSelect,
                                    checked = checked,
                                    onClick = {
                                        if (multiSelect) {
                                            selection = if (checked) selection - item.uri
                                            else selection + item.uri
                                        } else {
                                            onImageClick(item.uri, resultUris)
                                        }
                                    },
                                    onLongClick = {
                                        multiSelect = true
                                        selection = selection + item.uri
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- batch action host ---------------------------------------------
    SearchBatchActionHost(
        action = batchAction,
        uris = selection.toList(),
        images = library,
        onConsumed = { batchAction = null },
        onMessage = { text -> scope.launch { snackbarHostState.showSnackbar(text) } },
        onNavigateToImageEdit = onNavigateToImageEdit,
        onNavigateToWechatGrid = onNavigateToWechatGrid,
        onNavigateToAiEdit = onNavigateToAiEdit
    )
}

/** An action chip: applies a preset to the field above it (never a toggle). */
@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.bodySmall) }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(
    item: ImageItem,
    multiSelect: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box {
            AsyncImage(
                model = Uri.parse(item.uri),
                contentDescription = item.displayName,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            if (multiSelect) {
                // Same visual language as the album grid's selection.
                Surface(
                    shape = CircleShape,
                    color = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (checked) Icons.Default.Check else Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val caption = StringBuilder(item.folderName)
                .append(" · ")
                .append(formatSearchDate(item.dateAdded))
            if (item.sizeBytes > 0L) caption.append(" · ").append(formatSearchSize(item.sizeBytes))
            if (item.uri.contains("/video/")) caption.append(" · video")
            if (item.width > 0 && item.height > 0) {
                caption.append(" · ").append(item.width).append("×").append(item.height)
            }
            Text(
                text = caption.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (item.rating > 0f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("%.1f".format(item.rating), style = MaterialTheme.typography.bodySmall)
                }
            }
            val score = item.aiScore
            if (score != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "AI ${score.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Parses a typed date into epoch seconds — `dateAdded` is stored in seconds.
 * Accepts yyyy-MM-dd, yyyy-MM and yyyy with either - or /; [endOfDay] widens
 * the bound to the last moment of that day / month / year so "To: 2025" keeps
 * the whole year. Returns null when the text is not a date yet, which leaves
 * the criterion unused instead of emptying the results mid-typing.
 */
private fun parseDateBound(text: String, endOfDay: Boolean): Long? {
    val value = text.trim()
    if (value.isEmpty()) return null
    val patterns = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy-MM", "yyyy/MM", "yyyy")
    for (pattern in patterns) {
        val format = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val parsed = try {
            format.parse(value)
        } catch (e: Exception) {
            null
        } ?: continue
        if (format.format(parsed) != value) continue
        val precision = when (pattern.length) {
            10 -> Calendar.DAY_OF_MONTH
            7 -> Calendar.MONTH
            else -> Calendar.YEAR
        }
        val calendar = Calendar.getInstance().apply { time = parsed }
        if (endOfDay) {
            if (precision == Calendar.YEAR) calendar.set(Calendar.MONTH, Calendar.DECEMBER)
            calendar.set(
                Calendar.DAY_OF_MONTH,
                calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            )
            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
        }
        return calendar.timeInMillis / 1000L
    }
    return null
}

private fun formatSearchDate(epochSeconds: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochSeconds * 1000L))

/** Byte count as "1.2 MB" / "856 KB" / "123 B". */
private fun formatSearchSize(bytes: Long): String {
    if (bytes <= 0L) return "Unknown"
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}
