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

    // Jobs to cancel previous collectors when switching modes
    private var imagesJob: Job? = null

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
            repository.refreshImagesFromMediaStore()
            _isLoading.value = false
        }
    }

    fun loadImagesByFolder(folderName: String) {
        imagesJob?.cancel()
        imagesJob = repository.getImagesByFolder(folderName).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun loadImagesByFolderSorted(folderName: String, sortByUserRating: Boolean, sortByAiScore: Boolean, sortByName: Boolean = false) {
        imagesJob?.cancel()
        val flow = when {
            sortByName -> repository.getAllImagesByNameAsc()
            sortByUserRating -> repository.getImagesSortedByUserRatingDesc()
            sortByAiScore -> repository.getImagesSortedByAiScoreDesc()
            else -> repository.getImagesByFolder(folderName)
        }
        imagesJob = flow.onEach { allImages ->
            _images.value = if (sortByUserRating || sortByAiScore || sortByName)
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
            FolderListSortMode.COUNT_DESC -> repository.getAllFoldersWithInfoByCount()
            FolderListSortMode.NAME_ASC -> repository.getAllFoldersWithInfoByName()
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
}

enum class FolderSortMode {
    MODIFIED_DATE_DESC,
    NAME_ASC,
    USER_RATING_DESC,
    AI_SCORE_DESC
}

enum class FolderListSortMode {
    LAST_MODIFIED_DESC,
    COUNT_DESC,
    NAME_ASC
}

enum class GroupByMode {
    NONE,
    DAY,
    MONTH,
    YEAR
}