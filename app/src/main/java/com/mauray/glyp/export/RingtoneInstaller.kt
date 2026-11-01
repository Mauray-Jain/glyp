package com.mauray.glyp.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings

/** A ringtone that has been saved to the device. */
interface Ringtone {
    /**
     * Makes this the default ringtone. Returns false, after opening the "Modify system
     * settings" screen, when the app is not allowed to yet.
     */
    fun setAsDefault(): Boolean
}

/**
 * The Android side of a ringtone: publishes the file to Ringtones/Glyp through
 * MediaStore (so it shows up in the system picker) and owns the WRITE_SETTINGS flow for
 * making it the default.
 */
class RingtoneInstaller(context: Context) {

    private val appContext = context.applicationContext

    fun install(file: ByteArray, title: String): Ringtone {
        val resolver = appContext.contentResolver
        val safeName = title.replace(Regex("[^\\w\\- ]"), "_").ifBlank { "Glyph ringtone" }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safeName.ogg")
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RINGTONES}/Glyp")
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("Could not create ringtone file")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(file) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return object : Ringtone {
            override fun setAsDefault() = setAsDefault(uri)
        }
    }

    private fun setAsDefault(uri: Uri): Boolean {
        if (!Settings.System.canWrite(appContext)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
            appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        RingtoneManager.setActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE, uri)
        return true
    }
}
