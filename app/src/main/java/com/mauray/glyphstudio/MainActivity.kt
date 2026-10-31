package com.mauray.glyphstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.mauray.glyphstudio.ui.home.HomeScreen
import com.mauray.glyphstudio.ui.studio.StudioScreen
import com.mauray.glyphstudio.ui.theme.GlyphStudioTheme

class MainActivity : ComponentActivity() {

    private val glyph get() = (application as GlyphStudioApp).glyph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlyphStudioTheme {
                var openProject by rememberSaveable { mutableStateOf<String?>(null) }
                val id = openProject
                if (id == null) {
                    HomeScreen(onOpen = { openProject = it })
                } else {
                    StudioScreen(projectId = id, onBack = { openProject = null })
                }
            }
        }
    }

    // The Glyph SDK only serves foreground apps, so hold the session while visible.
    override fun onStart() {
        super.onStart()
        glyph.start()
    }

    override fun onStop() {
        glyph.stop()
        super.onStop()
    }
}
