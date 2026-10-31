package com.mauray.glyphstudio.model

import com.mauray.glyphstudio.audio.Timebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.CoroutineContext

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
 * Projects live in [root]/<id>/ as project.json + lights.bin + a copy of the audio, so they
 * survive the original file being moved or deleted. Every file is written to a temp file and
 * renamed into place, so a crash mid-save leaves the previous version intact.
 */
class ProjectStore(
    private val root: File,
    private val io: CoroutineContext = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun list(): List<ProjectMeta> = withContext(io) {
        root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
            .sortedByDescending { it.updatedAt }
    }

    /**
     * Copies the audio from [openAudio] into a new project named [fileName] (without its
     * extension), with an empty light track of matching length. With [autoCompose] the Studio
     * fills the track on first open; otherwise it stays blank.
     */
    suspend fun create(
        fileName: String,
        durationMs: Long,
        autoCompose: Boolean,
        openAudio: () -> InputStream,
    ): Project = withContext(io) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val audioFile = File(dir, "audio." + fileName.substringAfterLast('.', "audio"))
        writeAtomically(audioFile) { out -> openAudio().use { it.copyTo(out) } }
        val time = now()
        val meta = ProjectMeta(id, fileName.substringBeforeLast('.'), audioFile.name, durationMs, time, time, autoCompose)
        val lights = LightTrack(Timebase.framesFor(durationMs))
        Project(save(meta, lights), audioFile, lights)
    }

    suspend fun load(id: String): Project = withContext(io) {
        val dir = File(root, id)
        val meta = readMeta(dir)
        val lights = DataInputStream(File(dir, LIGHTS).inputStream().buffered()).use(LightTrack::readFrom)
        Project(meta, File(dir, meta.audioFileName), lights)
    }

    /** Writes [meta] (stamped with the current time) and [lights], which must be a snapshot. */
    suspend fun save(meta: ProjectMeta, lights: LightTrack): ProjectMeta = withContext(io) {
        val dir = File(root, meta.id)
        val stamped = meta.copy(updatedAt = now())
        writeAtomically(File(dir, LIGHTS)) { out -> DataOutputStream(out.buffered()).also(lights::writeTo).flush() }
        writeAtomically(File(dir, META)) { out -> out.write(json.encodeToString(ProjectMeta.serializer(), stamped).toByteArray()) }
        stamped
    }

    suspend fun delete(id: String) = withContext(io) {
        File(root, id).deleteRecursively()
    }

    private fun readMeta(dir: File): ProjectMeta =
        json.decodeFromString(ProjectMeta.serializer(), File(dir, META).readText())

    private fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val META = "project.json"
        const val LIGHTS = "lights.bin"
    }
}
