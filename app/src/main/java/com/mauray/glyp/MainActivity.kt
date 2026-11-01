package com.mauray.glyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.mauray.glyp.ui.home.HomeScreen
import com.mauray.glyp.ui.studio.StudioScreen
import com.mauray.glyp.ui.theme.GlypTheme

class MainActivity : ComponentActivity() {

    private val glyph get() = (application as GlypApp).glyph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlypTheme {
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
