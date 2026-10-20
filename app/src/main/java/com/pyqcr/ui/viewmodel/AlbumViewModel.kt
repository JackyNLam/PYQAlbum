package com.pyqcr.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyqcr.PyqCrApp
import com.pyqcr.data.db.FolderInfo
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

class AlbumViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AlbumRepository(
        application,
        (application as PyqCrApp).database
    )

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _folderInfos = MutableStateFlow<List<FolderInfo>>(emptyList())
    val folderInfos: StateFlow<List<FolderInfo>> = _folderInfos.asStateFlow()

    // Videos shown in the FOLDER browse alongside images (see getFolderVideos).
    private val _folderVideos = MutableStateFlow<List<ImageItem>>(emptyList())
    val folderVideos: StateFlow<List<ImageItem>> = _folderVideos.asStateFlow()

    // Folder cards for the VIDEO collection — merged with the Room image
    // folders so folders that hold only videos still appear in the list.
    private val _videoFolderInfos = MutableStateFlow<List<FolderInfo>>(emptyList())
    val videoFolderInfos: StateFlow<List<FolderInfo>> = _videoFolderInfos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    var currentSortMode by mutableStateOf(FolderSortMode.MODIFIED_DATE_DESC)
        private set

    var groupBy by mutableStateOf(GroupByMode.NONE)
        private set

    // Folder-level sort state
    var folderSortMode by mutableStateOf(FolderListSortMode.LAST_MODIFIED_DESC)
        private set

    private var foldersJob: Job? = null

    private var videosJob: Job? = null

    // Last folder videos were loaded for — so a MediaStore refresh reloads the
    // same scope (null = all folders / "All Folders").
    private var lastVideoFolder: String? = null

    init {
        // Load folders with info (image count + last modified), default sort: newest first
        repository.getAllFoldersWithInfo().onEach { folderList ->
            _folderInfos.value = folderList
            _folders.value = folderList.map { it.folderName }
        }.launchIn(viewModelScope)

        // Start collecting all images (default view)
        collectAllImages()
    }

    private fun collectAllImages() {
        imagesJob?.cancel()
        imagesJob = repository.getAllImages().onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun refreshImages() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.refreshImagesFromMediaStore()
            } finally {
                _isLoading.value = false
            }
            // Keep the FOLDER browse's video list and folder cards live too.
            refreshVideos()
        }
    }

    /** Videos for the FOLDER browse grid — one folder, or every folder when
     *  [folderName] is null (the "All Folders" pseudo-entry). */
    fun loadFolderVideos(folderName: String?) {
        lastVideoFolder = folderName
        viewModelScope.launch {
            _folderVideos.value = repository.getFolderVideos(folderName)
        }
    }

    /** Video folder cards (merged with the image folders in the UI). */
    fun loadVideoFolderInfos() {
        viewModelScope.launch {
            _videoFolderInfos.value = repository.getVideoFolderInfos()
        }
    }

    /** Reload the folder-scoped videos (keeps the last-loaded folder scope) and
     *  their folder cards — called alongside [refreshImages] so folder browse
     *  stays live after MediaStore changes (restores, deletions…). */
    fun refreshVideos() {
        viewModelScope.launch {
            _videoFolderInfos.value = repository.getVideoFolderInfos()
            _folderVideos.value = repository.getFolderVideos(lastVideoFolder)
        }
    }

    fun loadImagesByFolder(folderName: String) {
        imagesJob?.cancel()
        imagesJob = repository.getImagesByFolder(folderName).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    /** All images across every folder, sorted with the same [FolderSortMode] options as a single folder. */
    fun loadAllImagesSorted(mode: FolderSortMode) {
        imagesJob?.cancel()
        val flow = when (mode) {
            FolderSortMode.MODIFIED_DATE_DESC -> repository.getAllImages()
            FolderSortMode.MODIFIED_DATE_ASC -> repository.getAllImagesDateAsc()
            FolderSortMode.NAME_ASC -> repository.getAllImagesByNameAsc()
            FolderSortMode.NAME_DESC -> repository.getAllImagesByNameDesc()
            FolderSortMode.USER_RATING_DESC -> repository.getImagesSortedByUserRatingDesc()
            FolderSortMode.USER_RATING_ASC -> repository.getImagesSortedByUserRatingAsc()
            FolderSortMode.AI_SCORE_DESC -> repository.getImagesSortedByAiScoreDesc()
            FolderSortMode.AI_SCORE_ASC -> repository.getImagesSortedByAiScoreAsc()
        }
        imagesJob = flow.onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun loadImagesByFolderSorted(folderName: String, mode: FolderSortMode) {
        imagesJob?.cancel()
        val flow = when (mode) {
            FolderSortMode.MODIFIED_DATE_DESC -> repository.getImagesByFolder(folderName)
            FolderSortMode.MODIFIED_DATE_ASC -> repository.getImagesByFolderDateAsc(folderName)
            FolderSortMode.NAME_ASC -> repository.getAllImagesByNameAsc()
            FolderSortMode.NAME_DESC -> repository.getAllImagesByNameDesc()
            FolderSortMode.USER_RATING_DESC -> repository.getImagesSortedByUserRatingDesc()
            FolderSortMode.USER_RATING_ASC -> repository.getImagesSortedByUserRatingAsc()
            FolderSortMode.AI_SCORE_DESC -> repository.getImagesSortedByAiScoreDesc()
            FolderSortMode.AI_SCORE_ASC -> repository.getImagesSortedByAiScoreAsc()
        }
        val needsFolderFilter = mode == FolderSortMode.NAME_ASC ||
            mode == FolderSortMode.NAME_DESC ||
            mode == FolderSortMode.USER_RATING_DESC ||
            mode == FolderSortMode.USER_RATING_ASC ||
            mode == FolderSortMode.AI_SCORE_DESC ||
            mode == FolderSortMode.AI_SCORE_ASC
        imagesJob = flow.onEach { allImages ->
            _images.value = if (needsFolderFilter)
                allImages.filter { it.folderName == folderName }
            else
                allImages
        }.launchIn(viewModelScope)
    }

    fun loadImagesByTag(tagName: String) {
        imagesJob?.cancel()
        imagesJob = repository.getImagesByTag(tagName).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun loadImagesByRating(min: Float, max: Float) {
        imagesJob?.cancel()
        imagesJob = repository.getImagesByRating(min, max).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun updateGroupBy(mode: GroupByMode) {
        groupBy = mode
    }

    fun updateSortMode(mode: FolderSortMode) {
        currentSortMode = mode
    }

    fun updateFolderSortMode(mode: FolderListSortMode) {
        folderSortMode = mode
        foldersJob?.cancel()
        val flow = when (mode) {
            FolderListSortMode.LAST_MODIFIED_DESC -> repository.getAllFoldersWithInfo()
            FolderListSortMode.LAST_MODIFIED_ASC -> repository.getAllFoldersWithInfoByOldest()
            FolderListSortMode.COUNT_DESC -> repository.getAllFoldersWithInfoByCount()
            FolderListSortMode.COUNT_ASC -> repository.getAllFoldersWithInfoByCountAsc()
            FolderListSortMode.NAME_ASC -> repository.getAllFoldersWithInfoByName()
            FolderListSortMode.NAME_DESC -> repository.getAllFoldersWithInfoByNameDesc()
        }
        foldersJob = flow.onEach { folderList ->
            _folderInfos.value = folderList
        }.launchIn(viewModelScope)
    }

    fun sortByAiScoreDesc() {
        imagesJob?.cancel()
        imagesJob = repository.getImagesSortedByAiScoreDesc().onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun updateRating(uri: String, rating: Float) {
        viewModelScope.launch {
            repository.updateRating(uri, rating)
        }
    }

    fun addTagToImage(uri: String, tagName: String) {
        viewModelScope.launch {
            val app = getApplication<PyqCrApp>()
            val tagDao = app.database.tagDao()
            // Try to get existing tag; if unique-index conflict prevents insert,
            // fall back to re-fetching from the database
            var tag = tagDao.getTagByName(tagName)
            val tagId = if (tag != null) {
                tag.id
            } else {
                val insertedId = tagDao.insertTag(com.pyqcr.data.db.TagEntity(name = tagName))
                if (insertedId == -1L) {
                    // Race: another coroutine inserted the same tag name first.
                    // Re-query to get the existing row's ID.
                    tag = tagDao.getTagByName(tagName)
                    tag?.id ?: return@launch
                } else {
                    insertedId
                }
            }
            if (tagDao.hasTag(uri, tagId) == 0) {
                tagDao.addTagToImage(com.pyqcr.data.db.ImageTagCrossRef(imageUri = uri, tagId = tagId))
            }
        }
    }

    fun batchRemoveTagsFromImages(imageUris: List<String>, tagName: String) {
        viewModelScope.launch {
            val app = getApplication<PyqCrApp>()
            val tagDao = app.database.tagDao()
            val tag = tagDao.getTagByName(tagName) ?: return@launch
            tagDao.removeTagFromImages(imageUris, tag.id)
            // Auto-delete tag if it has no images left
            val remaining = tagDao.getImageUrisForTag(tag.id)
            if (remaining.isEmpty()) {
                tagDao.deleteTagById(tag.id)
            }
        }
    }

    /** Delete images: remove from the DB immediately, then re-sync from MediaStore. */
    fun deleteImages(imageUris: List<String>) {
        if (imageUris.isEmpty()) return
        viewModelScope.launch {
            _isLoading.value = true
            repository.removeImagesFromDb(imageUris)
            repository.refreshImagesFromMediaStore()
            _isLoading.value = false
        }
    }

    fun batchRemoveAiInfo(imageUris: List<String>) {
        viewModelScope.launch {
            val app = getApplication<PyqCrApp>()
            val imageDao = app.database.imageDao()
            for (uri in imageUris) {
                imageDao.updateAiScore(uri, 0f)
                imageDao.updateAiReason(uri, "")
            }
        }
    }

    /** Remove tags that have no images associated with them anymore. */
    fun deleteOrphanedTags() {
        viewModelScope.launch {
            val app = getApplication<PyqCrApp>()
            app.database.tagDao().deleteOrphanedTagsGuarded()
        }
    }

    // --- Persisted sort mode ---

    fun persistFolderSortMode(mode: FolderSortMode) {
        val prefs = getApplication<PyqCrApp>().getSharedPreferences("pyqcr_sort", 0)
        prefs.edit().putString("folder_sort_mode", mode.name).apply()
    }

    fun getPersistedFolderSortMode(): FolderSortMode {
        val prefs = getApplication<PyqCrApp>().getSharedPreferences("pyqcr_sort", 0)
        return try {
            FolderSortMode.valueOf(prefs.getString("folder_sort_mode", FolderSortMode.MODIFIED_DATE_DESC.name)!!)
        } catch (e: Exception) {
            FolderSortMode.MODIFIED_DATE_DESC
        }
    }

    fun persistGroupByMode(mode: GroupByMode) {
        val prefs = getApplication<PyqCrApp>().getSharedPreferences("pyqcr_sort", 0)
        prefs.edit().putString("group_by_mode", mode.name).apply()
    }

    fun getPersistedGroupByMode(): GroupByMode {
        val prefs = getApplication<PyqCrApp>().getSharedPreferences("pyqcr_sort", 0)
        return try {
            GroupByMode.valueOf(prefs.getString("group_by_mode", GroupByMode.NONE.name)!!)
        } catch (e: Exception) {
            GroupByMode.NONE
        }
    }
}

enum class FolderSortMode {
    MODIFIED_DATE_DESC,
    MODIFIED_DATE_ASC,
    NAME_ASC,
    NAME_DESC,
    USER_RATING_DESC,
    USER_RATING_ASC,
    AI_SCORE_DESC,
    AI_SCORE_ASC
}

enum class FolderListSortMode {
    LAST_MODIFIED_DESC,
    LAST_MODIFIED_ASC,
    COUNT_DESC,
    COUNT_ASC,
    NAME_ASC,
    NAME_DESC
}

enum class GroupByMode {
    NONE,
    DAY,
    MONTH,
    YEAR,
    RATING_SCORE,
    AI_SCORE
}