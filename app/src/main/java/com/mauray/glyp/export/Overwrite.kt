package com.mauray.glyp.export

/**
 * Writes [new] over a file whose current content [readOld] returns, through [write] (which
 * truncates). If writing fails, the old content is written back before the error is rethrown,
 * so a failed overwrite never leaves a truncated file behind.
 */
internal fun overwriteWithRollback(readOld: () -> ByteArray, write: (ByteArray) -> Unit, new: ByteArray) {
    val old = readOld()
    try {
        write(new)
    } catch (e: Exception) {
        try {
            write(old)
        } catch (restore: Exception) {
            e.addSuppressed(restore)
        }
        throw e
    }
}
