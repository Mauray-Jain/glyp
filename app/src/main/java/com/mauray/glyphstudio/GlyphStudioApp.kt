package com.mauray.glyphstudio

import android.app.Application
import com.mauray.glyphstudio.export.RingtoneExporter
import com.mauray.glyphstudio.export.RingtoneInstaller
import com.mauray.glyphstudio.glyph.GlyphController
import com.mauray.glyphstudio.model.ProjectStore
import java.io.File

/** Holds the app-wide singletons; small enough that a DI framework would be overkill. */
class GlyphStudioApp : Application() {
    val glyph by lazy { GlyphController(this) }
    val store by lazy { ProjectStore(File(filesDir, "projects")) }
    val exporter by lazy { RingtoneExporter(File(cacheDir, "export"), RingtoneInstaller(this)) }
}
