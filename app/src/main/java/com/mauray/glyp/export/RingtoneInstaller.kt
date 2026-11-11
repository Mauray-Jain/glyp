package com.mauray.glyp.export

import android.content.ContentUris
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

    /**
     * Saves [file] as the ringtone [title]. Exporting a name again replaces that ringtone in
     * place (same entry, so it stays the default if it was) instead of adding "name (1)".
     */
    fun install(file: ByteArray, title: String): Ringtone {
        val resolver = appContext.contentResolver
        val safeName = title.replace(Regex("[^\\w\\- ]"), "_").ifBlank { "Glyph ringtone" }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val displayName = "$safeName.ogg"
        findOwn(collection, displayName)?.let { uri ->
            try {
                replace(uri, file, title)
                return ringtoneAt(uri)
            } catch (e: SecurityException) {
                // Saved by an earlier install of the app, so no longer ours: add a new one.
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, title)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_RINGTONE, 1)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_ALARM, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("Could not create ringtone file")
        try {
            resolver.openOutputStream(uri)!!.use { it.write(file) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return ringtoneAt(uri)
    }

    /** Our earlier ringtone called [displayName] in Ringtones/Glyp, if there is one. */
    private fun findOwn(collection: Uri, displayName: String): Uri? =
        appContext.contentResolver.query(
            collection,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(displayName, "$RELATIVE_PATH/"),
            null,
        )?.use { if (it.moveToFirst()) ContentUris.withAppendedId(collection, it.getLong(0)) else null }

    /**
     * Overwrites our earlier ringtone at [uri] with [file]. If the write fails part-way the old
     * file is put back, so a ringtone that is the phone's default never ends up truncated.
     */
    private fun replace(uri: Uri, file: ByteArray, title: String) {
        val resolver = appContext.contentResolver
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 1) }, null, null)
        var written = false
        try {
            overwriteWithRollback(
                readOld = { resolver.openInputStream(uri)!!.use { it.readBytes() } },
                write = { bytes -> resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } },
                new = file,
            )
            written = true
        } finally {
            // Never leave the entry pending: the system ringtone picker can't see pending files.
            // The title only changes with the content; after a rollback the old title still fits.
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                if (written) put(MediaStore.Audio.Media.TITLE, title)
            }
            resolver.update(uri, values, null, null)
        }
    }

    private fun ringtoneAt(uri: Uri) = object : Ringtone {
        override fun setAsDefault() = setAsDefault(uri)
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

    private companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_RINGTONES}/Glyp"
    }
}
