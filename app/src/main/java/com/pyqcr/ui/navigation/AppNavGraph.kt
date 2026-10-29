package com.pyqcr.ui.navigation

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pyqcr.ai.AiEditSelectionStore
import com.pyqcr.data.model.ImageItem
import com.pyqcr.ui.screen.*

object Routes {
    const val ALBUM = "album"
    const val TAG = "tag"
    const val RATING = "rating"
    const val IMAGE_DETAIL = "image_detail/{imageUri}"
    const val AI_RATING = "ai_rating?imageUri={imageUri}"
    const val AI_EDIT = "ai_edit"
    const val AI_SELECT = "ai_select"
    const val BATCH_EDIT = "batch_edit/{mode}"
    const val VAULT = "vault"

    /**
     * URL-encode the imageUri so that Navigation Compose doesn't break
     * on URI path separators (e.g., "content://media/...").
     */
    fun imageDetail(imageUri: String) = "image_detail/${Uri.encode(imageUri)}"

    /** AI Rating route with an optional pre-selected image ("" = no preselect). */
    fun aiRating(imageUri: String) = "ai_rating?imageUri=${Uri.encode(imageUri)}"

    fun batchEdit(mode: String) = "batch_edit/$mode"
}

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()

    // Track current image list so ImageDetailScreen can navigate prev/next
    var currentImages by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var currentImageIndex by remember { mutableIntStateOf(-1) }
    // Direction of the last viewer swipe: +1 = left (next), -1 = right (previous),
    // 0 = opened from the grid. Drives the short slide-in of the new photo.
    var viewerEnterDirection by remember { mutableIntStateOf(0) }

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
                    viewerEnterDirection = 0
                    navController.navigate(Routes.imageDetail(imageUri))
                },
                onNavigateToAiSelection = {
                    navController.navigate(Routes.AI_SELECT)
                },
                onNavigateToAiEdit = {
                    navController.navigate(Routes.AI_EDIT)
                },
                onNavigateToVault = {
                    navController.navigate(Routes.VAULT)
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
            val context = LocalContext.current
            val nextUri = getNextImageUri(imageUri)
            val previousUri = getPreviousImageUri(imageUri)
            ImageDetailScreen(
                imageUriRaw = imageUriRaw,
                onBack = {
                    // Only pop while this viewer is on top: a duplicate close
                    // (e.g. two fast swipe-downs) must never also pop the album
                    // and leave a blank screen.
                    if (navController.currentDestination?.route == Routes.IMAGE_DETAIL) {
                        navController.popBackStack()
                    }
                },
                onNextImage = {
                    nextUri?.let { next ->
                        viewerEnterDirection = 1
                        navController.navigate(Routes.imageDetail(next)) {
                            popUpTo(Routes.ALBUM) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onPreviousImage = {
                    previousUri?.let { prev ->
                        viewerEnterDirection = -1
                        navController.navigate(Routes.imageDetail(prev)) {
                            popUpTo(Routes.ALBUM) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                hasNextImage = nextUri != null,
                hasPreviousImage = previousUri != null,
                enterDirection = viewerEnterDirection,
                onOpenAiRating = {
                    navController.navigate(Routes.aiRating(imageUri))
                },
                onOpenAiEdit = {
                    // Mark this image as an AI Edit first-image source, then open
                    // the AI Edit screen (it imports sources from these prefs).
                    AiEditSelectionStore.addSource(context, imageUri)
                    navController.navigate(Routes.AI_EDIT)
                }
            )
        }

        composable(
            route = Routes.AI_RATING,
            arguments = listOf(
                navArgument("imageUri") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val preSelected = backStackEntry.arguments?.getString("imageUri")
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.decode(it) }
            AiRatingScreen(
                onBack = { navController.popBackStack() },
                initialSelectedUris = if (preSelected != null) setOf(preSelected) else emptySet()
            )
        }

        composable(Routes.AI_EDIT) {
            AiEditScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.AI_SELECT) {
            AiSelectScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.VAULT) {
            VaultScreen(
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