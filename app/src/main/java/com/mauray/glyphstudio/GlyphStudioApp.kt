package com.mauray.glyphstudio

import android.app.Application
import com.mauray.glyphstudio.export.RingtoneExporter
import com.mauray.glyphstudio.glyph.GlyphController
import com.mauray.glyphstudio.model.ProjectStore

/** Holds the app-wide singletons; small enough that a DI framework would be overkill. */
class GlyphStudioApp : Application() {
    val glyph by lazy { GlyphController(this) }
    val store by lazy { ProjectStore(this) }
    val exporter by lazy { RingtoneExporter(this) }
}
