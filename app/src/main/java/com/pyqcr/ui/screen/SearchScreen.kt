package com.pyqcr.ui.screen

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
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
 * Library search from the left drawer.
 *
 * Every criterion the gallery stores is filterable here: filename, folder name,
 * date (a range, typed or with the quick chips), file size, star rating and AI
 * score. Filters combine with AND and are applied to the same MediaStore-backed
 * list the album shows, so a result always exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onImageClick: (uri: String, uris: List<String>) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PyqCrApp
    val repository = remember { AlbumRepository(context, app.database) }

    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        repository.getAllImages().collect { images = it }
    }

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

    fun clearFilters() {
        nameQuery = ""
        folderQuery = ""
        dateFrom = ""
        dateTo = ""
        minSizeMb = ""
        maxSizeMb = ""
        minRating = 0f
        minAiScore = 0f
    }

    val hasFilters = nameQuery.isNotBlank() || folderQuery.isNotBlank() ||
        dateFrom.isNotBlank() || dateTo.isNotBlank() || minSizeMb.isNotBlank() ||
        maxSizeMb.isNotBlank() || minRating > 0f || minAiScore > 0f

    val folders = remember(images) {
        images.map { it.folderName }.distinct().sortedBy { it.lowercase() }
    }

    val results = remember(
        images, nameQuery, folderQuery, dateFrom, dateTo,
        minSizeMb, maxSizeMb, minRating, minAiScore, sort
    ) {
        val from = parseDateBound(dateFrom, endOfDay = false)
        val to = parseDateBound(dateTo, endOfDay = true)
        val minBytes = minSizeMb.trim().toFloatOrNull()?.let { it * 1048576f }
        val maxBytes = maxSizeMb.trim().toFloatOrNull()?.let { it * 1048576f }
        val name = nameQuery.trim().lowercase()
        val folder = folderQuery.trim().lowercase()
        val matched = images.filter { item ->
            (name.isEmpty() || item.displayName.lowercase().contains(name)) &&
                (folder.isEmpty() || item.folderName.lowercase().contains(folder)) &&
                (from == null || item.dateAdded >= from) &&
                (to == null || item.dateAdded <= to) &&
                (minBytes == null || item.sizeBytes >= minBytes.toLong()) &&
                (maxBytes == null || item.sizeBytes <= maxBytes.toLong()) &&
                (minRating <= 0f || item.rating >= minRating) &&
                (minAiScore <= 0f || (item.aiScore ?: -1f) >= minAiScore)
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
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
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
            }

            HorizontalDivider()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${results.size} of ${images.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
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
                        text = when {
                            images.isEmpty() -> "No photos in the library yet."
                            hasFilters -> "Nothing matches these filters."
                            else -> "Type a filename, pick a folder or set a date, size, rating or AI score."
                        },
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
                        SearchResultRow(
                            item = item,
                            onClick = { onImageClick(item.uri, resultUris) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** An action chip: applies a preset to the field above it (never a toggle). */
@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.bodySmall) }
    )
}

@Composable
private fun SearchResultRow(
    item: ImageItem,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        AsyncImage(
            model = Uri.parse(item.uri),
            contentDescription = item.displayName,
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
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
            if (item.width > 0 && item.height > 0) {
                caption.append(" · ").append(item.width).append("×").append(item.height)
            }
            if (item.type == "video") caption.append(" · video")
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
