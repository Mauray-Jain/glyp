package com.mauray.glyp.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OverwriteTest {

    private val old = "old ringtone".toByteArray()
    private val new = "new ringtone, longer".toByteArray()

    /** A file in memory whose next writes can be told to fail after truncating. */
    private class FakeFile(var content: ByteArray) {
        var failures = 0
        val writes = mutableListOf<ByteArray>()

        fun write(bytes: ByteArray) {
            writes += bytes
            content = ByteArray(0)
            if (failures > 0) {
                failures--
                throw IOException("disk full")
            }
            content = bytes
        }
    }

    @Test
    fun `a successful write replaces the content`() {
        val file = FakeFile(old)
        overwriteWithRollback({ file.content }, file::write, new)
        assertArrayEquals(new, file.content)
    }

    @Test
    fun `a write that fails after truncating puts the old content back`() {
        val file = FakeFile(old).apply { failures = 1 }
        try {
            overwriteWithRollback({ file.content }, file::write, new)
            fail("expected the IOException")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertArrayEquals(old, file.content)
    }

    @Test
    fun `when the restore fails too the original error carries it as suppressed`() {
        val file = FakeFile(old).apply { failures = 2 }
        try {
            overwriteWithRollback({ file.content }, file::write, new)
            fail("expected the IOException")
        } catch (e: IOException) {
            assertEquals(1, e.suppressed.size)
            assertTrue(e.suppressed.single() is IOException)
            assertEquals("first write was the new content", new.toList(), file.writes.first().toList())
        }
    }

    @Test
    fun `a file we may not read is never written`() {
        val file = FakeFile(old)
        val denied = SecurityException("owned by an earlier install")
        try {
            overwriteWithRollback({ throw denied }, file::write, new)
            fail("expected the SecurityException")
        } catch (e: SecurityException) {
            assertSame(denied, e)
        }
        assertFalse(file.writes.isNotEmpty())
        assertArrayEquals(old, file.content)
    }
}
