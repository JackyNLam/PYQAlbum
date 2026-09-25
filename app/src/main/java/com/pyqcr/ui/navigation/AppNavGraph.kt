package com.pyqcr.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

    NavHost(
        navController = navController,
        startDestination = Routes.ALBUM
    ) {
        composable(Routes.ALBUM) {
            AlbumScreen(
                onImageClick = { imageUri ->
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
            val imageUri = backStackEntry.arguments?.getString("imageUri") ?: return@composable
            ImageDetailScreen(
                imageUri = imageUri,
                onBack = { navController.popBackStack() }
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