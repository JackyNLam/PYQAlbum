package com.pyqcr.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyqcr.PyqCrApp
import com.pyqcr.data.model.ImageItem
import com.pyqcr.data.repository.AlbumRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AlbumViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AlbumRepository(
        application,
        (application as PyqCrApp).database
    )

    private val _images = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _folders = MutableStateFlow<List<String>>(emptyList())
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        repository.getAllFolders().onEach { folderList ->
            _folders.value = folderList
        }.launchIn(viewModelScope)

        repository.getAllImages().onEach { imageList ->
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
        repository.getImagesByFolder(folderName).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun loadImagesByTag(tagName: String) {
        repository.getImagesByTag(tagName).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun loadImagesByRating(min: Float, max: Float) {
        repository.getImagesByRating(min, max).onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun sortByAiScoreDesc() {
        repository.getImagesSortedByAiScoreDesc().onEach { imageList ->
            _images.value = imageList
        }.launchIn(viewModelScope)
    }

    fun updateRating(uri: String, rating: Float) {
        viewModelScope.launch {
            repository.updateRating(uri, rating)
        }
    }
}