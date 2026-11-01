package com.mauray.glyp

import android.app.Application
import com.mauray.glyp.export.RingtoneExporter
import com.mauray.glyp.export.RingtoneInstaller
import com.mauray.glyp.glyph.GlyphController
import com.mauray.glyp.model.ProjectStore
import java.io.File

/** Holds the app-wide singletons; small enough that a DI framework would be overkill. */
class GlypApp : Application() {
    val glyph by lazy { GlyphController(this) }
    val store by lazy { ProjectStore(File(filesDir, "projects")) }
    val exporter by lazy { RingtoneExporter(File(cacheDir, "export"), RingtoneInstaller(this)) }
}
