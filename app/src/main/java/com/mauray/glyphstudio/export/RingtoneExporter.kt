package com.mauray.glyphstudio.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import com.mauray.glyphstudio.audio.AudioDecoder
import com.mauray.glyphstudio.model.LightTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exports a project as a Glyph ringtone: Ogg Opus audio with the light data in its tags,
 * saved to Ringtones/Glyph Studio so it shows up in the system ringtone picker.
 */
class RingtoneExporter(context: Context) {

    private val appContext = context.applicationContext

    /** [lights] must be a snapshot: it is read on the IO dispatcher. */
    suspend fun export(
        audioFile: File,
        lights: LightTrack,
        durationMs: Long,
        title: String,
        onProgress: (Float) -> Unit = {},
    ): Uri = withContext(Dispatchers.IO) {
        val work = File(appContext.cacheDir, "export").apply { mkdirs() }
        val opus = File(work, "audio.ogg")
        val tagged = File(work, "tagged.ogg")

        if (isOggOpus(audioFile)) {
            audioFile.copyTo(opus, overwrite = true)
            onProgress(0.8f)
        } else {
            val pcm = AudioDecoder.decode(audioFile) { onProgress(it * 0.4f) }
            OpusTranscoder.encode(pcm, opus) { onProgress(0.4f + it * 0.4f) }
        }
        OggOpusTagger.retag(opus, tagged, GlyphMetadata.tags(title, lights, durationMs))
        val uri = publish(tagged, title)
        opus.delete()
        tagged.delete()
        onProgress(1f)
        uri
    }

    /** Needs the "Modify system settings" special permission; see [writeSettingsIntent]. */
    fun setAsDefaultRingtone(uri: Uri): Boolean {
        if (!Settings.System.canWrite(appContext)) return false
        RingtoneManager.setActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE, uri)
        return true
    }

    fun canSetDefault() = Settings.System.canWrite(appContext)

    fun writeSettingsIntent() = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))

    private fun publish(file: File, title: String): Uri {
        val resolver = appContext.contentResolver
        val safeName = title.replace(Regex("[^\\w\\- ]"), "_").ifBlank { "Glyph ringtone" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeName.ogg")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/Glyph Studio")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create ringtone file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun isOggOpus(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(64)
            val n = input.read(head)
            n > 36 && String(head, 0, 4, Charsets.US_ASCII) == "OggS" &&
                String(head, 28, 8, Charsets.US_ASCII) == "OpusHead"
        }
    }.getOrDefault(false)
}
