package com.mauray.glyphstudio.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mauray.glyphstudio.glyph.GlyphZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

@Serializable
data class ProjectMeta(
    val id: String,
    val name: String,
    val audioFileName: String,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
    /** True until the Studio has generated the first automatic composition. */
    val pendingAutoCompose: Boolean = false,
)

/** A loaded project: metadata, its private copy of the audio, and the light track. */
class Project(val meta: ProjectMeta, val audioFile: File, val lights: LightTrack)

/**
 * Projects live in filesDir/projects/<id>/ as project.json + lights.bin + a copy of the audio,
 * so they survive the original file being moved or deleted.
 */
class ProjectStore(context: Context) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "projects").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun list(): List<ProjectMeta> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Copies [audioUri] into a new project with an empty light track of matching length.
     * With [autoCompose] the Studio fills the track on first open; otherwise it stays blank.
     */
    suspend fun create(audioUri: Uri, durationMs: Long, autoCompose: Boolean): Project = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val displayName = queryDisplayName(audioUri) ?: "Untitled"
        val ext = displayName.substringAfterLast('.', "audio")
        val audioFile = File(dir, "audio.$ext")
        appContext.contentResolver.openInputStream(audioUri)!!.use { input ->
            audioFile.outputStream().use { input.copyTo(it) }
        }
        val now = System.currentTimeMillis()
        val meta = ProjectMeta(id, displayName.substringBeforeLast('.'), audioFile.name, durationMs, now, now, autoCompose)
        val project = Project(meta, audioFile, LightTrack(LightTrack.framesForDuration(durationMs)))
        save(project)
        project
    }

    suspend fun load(id: String): Project = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        val meta = readMeta(dir)
        val lights = DataInputStream(File(dir, LIGHTS).inputStream().buffered()).use { input ->
            val frames = input.readInt()
            val data = ShortArray(frames * GlyphZones.COUNT)
            for (i in data.indices) data[i] = input.readShort()
            LightTrack(frames, data)
        }
        Project(meta, File(dir, meta.audioFileName), lights)
    }

    suspend fun save(
        project: Project,
        name: String = project.meta.name,
        pendingAutoCompose: Boolean = project.meta.pendingAutoCompose,
    ): Project = withContext(Dispatchers.IO) {
        val dir = File(root, project.meta.id)
        val meta = project.meta.copy(name = name, updatedAt = System.currentTimeMillis(), pendingAutoCompose = pendingAutoCompose)
        File(dir, META).writeText(json.encodeToString(ProjectMeta.serializer(), meta))
        DataOutputStream(File(dir, LIGHTS).outputStream().buffered()).use { out ->
            out.writeInt(project.lights.frameCount)
            for (v in project.lights.data) out.writeShort(v.toInt())
        }
        Project(meta, project.audioFile, project.lights)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
    }

    private fun readMeta(dir: File): ProjectMeta =
        json.decodeFromString(ProjectMeta.serializer(), File(dir, META).readText())

    private fun queryDisplayName(uri: Uri): String? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private companion object {
        const val META = "project.json"
        const val LIGHTS = "lights.bin"
    }
}
