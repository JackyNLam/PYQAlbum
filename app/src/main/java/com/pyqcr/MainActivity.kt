package com.pyqcr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.compose.LocalImageLoader
import com.pyqcr.ui.navigation.AppNavGraph
import com.pyqcr.ui.theme.PyqCrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val app = context.applicationContext as PyqCrApp
            val imageLoader = remember { app.imageLoader }

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                PyqCrTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavGraph()
                    }
                }
            }
        }
    }
}