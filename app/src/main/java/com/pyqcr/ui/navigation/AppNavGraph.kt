package com.pyqcr.ui.navigation

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pyqcr.data.model.ImageItem
import com.pyqcr.ui.screen.*

object Routes {
    const val ALBUM = "album"
    const val TAG = "tag"
    const val RATING = "rating"
    const val IMAGE_DETAIL = "image_detail/{imageUri}"
    const val AI_RATING = "ai_rating"
    const val AI_SELECT = "ai_select"
    const val BATCH_EDIT = "batch_edit/{mode}"

    /**
     * URL-encode the imageUri so that Navigation Compose doesn't break
     * on URI path separators (e.g., "content://media/...").
     */
    fun imageDetail(imageUri: String) = "image_detail/${Uri.encode(imageUri)}"

    fun batchEdit(mode: String) = "batch_edit/$mode"
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    // Track current image list so ImageDetailScreen can navigate prev/next
    var currentImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var currentImageIndex by remember { mutableIntStateOf(-1) }

    fun getNextImageUri(currentUri: String): String? {
        val idx = currentImages.indexOf(currentUri)
        if (idx >= 0 && idx + 1 < currentImages.size) {
            return currentImages[idx + 1]
        }
        return null
    }

    fun getPreviousImageUri(currentUri: String): String? {
        val idx = currentImages.indexOf(currentUri)
        if (idx > 0) {
            return currentImages[idx - 1]
        }
        return null
    }

    NavHost(
        navController = navController,
        startDestination = Routes.ALBUM
    ) {
        composable(Routes.ALBUM) {
            AlbumScreen(
                onImageClick = { imageUri, imageList ->
                    currentImages = imageList
                    currentImageIndex = imageList.indexOf(imageUri)
                    navController.navigate(Routes.imageDetail(imageUri))
                },
                onNavigateToAiSelection = {
                    navController.navigate(Routes.AI_SELECT)
                }
            )
        }

        composable(Routes.TAG) {
            TagScreen(
                onImageClick = { imageUri ->
                    navController.navigate(Routes.imageDetail(imageUri))
                }
            )
        }

        composable(Routes.RATING) {
            RatingScreen(
                onImageClick = { imageUri ->
                    navController.navigate(Routes.imageDetail(imageUri))
                }
            )
        }

        composable(
            route = Routes.IMAGE_DETAIL,
            arguments = listOf(
                navArgument("imageUri") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val imageUriRaw = backStackEntry.arguments?.getString("imageUri") ?: return@composable
            val imageUri = Uri.decode(imageUriRaw)
            ImageDetailScreen(
                imageUriRaw = imageUriRaw,
                onBack = { navController.popBackStack() },
                onNextImage = {
                    getNextImageUri(imageUri)?.let { nextUri ->
                        navController.navigate(Routes.imageDetail(nextUri)) {
                            popUpTo(Routes.ALBUM) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onPreviousImage = {
                    getPreviousImageUri(imageUri)?.let { prevUri ->
                        navController.navigate(Routes.imageDetail(prevUri)) {
                            popUpTo(Routes.ALBUM) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(Routes.AI_RATING) {
            AiRatingScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.AI_SELECT) {
            AiSelectScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.BATCH_EDIT,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val mode = backStackEntry.arguments?.getString("mode") ?: "tag"
            BatchEditScreen(
                mode = mode,
                onBack = { navController.popBackStack() }
            )
        }
    }
}