package com.mauray.glyp.model

import com.mauray.glyp.audio.Timebase
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
        sweepAbandoned()
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
        try {
            val audioFile = File(dir, "audio." + fileName.substringAfterLast('.', "audio"))
            writeAtomically(audioFile) { out -> openAudio().use { it.copyTo(out) } }
            val time = now()
            val meta = ProjectMeta(id, fileName.substringBeforeLast('.'), audioFile.name, durationMs, time, time, autoCompose)
            val lights = LightTrack(Timebase.framesFor(durationMs))
            Project(save(meta, lights), audioFile, lights)
        } catch (e: Exception) {
            // A failed or cancelled import would otherwise leave a half-copied song nobody can
            // see or delete. Cancellation is an Exception too, so it cleans up and propagates.
            dir.deleteRecursively()
            throw e
        }
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
        writeAtomically(File(dir, LIGHTS)) { out -> DataOutputStream(out.buffered()).also(lights::writeTo).flush() }
        writeMeta(meta)
    }

    /** Renames project [id], leaving its lights and audio alone. */
    suspend fun rename(id: String, name: String): ProjectMeta = withContext(io) {
        writeMeta(readMeta(File(root, id)).copy(name = name))
    }

    /** Writes [meta] stamped with the current time, and returns what was written. */
    private fun writeMeta(meta: ProjectMeta): ProjectMeta {
        val stamped = meta.copy(updatedAt = now())
        writeAtomically(File(File(root, meta.id), META)) { out -> out.write(json.encodeToString(ProjectMeta.serializer(), stamped).toByteArray()) }
        return stamped
    }

    suspend fun delete(id: String) = withContext(io) {
        File(root, id).deleteRecursively()
    }

    private fun readMeta(dir: File): ProjectMeta =
        json.decodeFromString(ProjectMeta.serializer(), File(dir, META).readText())

    private fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP)
        try {
            tmp.outputStream().use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Removes what crashed or killed writes left behind: project folders that never got a
     * project.json, and stray temp files. Only once they are an hour old: a folder without
     * project.json may be an import still copying its song right now.
     */
    private fun sweepAbandoned() {
        val cutoff = now() - ABANDONED_MS
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory) continue
            if (!File(dir, META).exists()) {
                if (dir.lastModified() < cutoff) dir.deleteRecursively()
                continue
            }
            dir.listFiles { f -> f.isFile && f.name.endsWith(TMP) && f.lastModified() < cutoff }
                .orEmpty()
                .forEach { it.delete() }
        }
    }

    private companion object {
        const val META = "project.json"
        const val LIGHTS = "lights.bin"
        const val TMP = ".tmp"
        const val ABANDONED_MS = 60 * 60_000L
    }
}
