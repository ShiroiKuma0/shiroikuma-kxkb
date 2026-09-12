package com.urik.keyboard.data.database

import android.content.Context
import com.urik.keyboard.utils.ErrorLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseFilesTest {
    private lateinit var context: Context
    private lateinit var db: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
        db = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        db.parentFile?.mkdirs()
        clean()
    }

    @After
    fun tearDown() = clean()

    private fun clean() {
        db.parentFile?.listFiles()
            ?.filter { it.name.startsWith(KeyboardDatabase.DATABASE_NAME) }
            ?.forEach { it.delete() }
    }

    @Test
    fun `probe reports ABSENT for a missing file without touching SQLCipher`() {
        assertEquals(DatabaseFiles.Probe.ABSENT, DatabaseFiles.probe(context, ByteArray(32)))
        assertEquals(DatabaseFiles.Probe.ABSENT, DatabaseFiles.probe(context, null))
    }

    @Test
    fun `the legacy zero key is 32 zero bytes and a fresh array each time`() {
        val a = DatabaseFiles.LEGACY_ZERO_KEY
        assertEquals(32, a.size)
        assertTrue(a.all { it == 0.toByte() })
        a[0] = 1
        assertTrue(
            "a caller's scribble must not leak into the next copy",
            DatabaseFiles.LEGACY_ZERO_KEY[0] == 0.toByte()
        )
    }

    @Test
    fun `the encrypted open-helper factory keeps a COPY of the passphrase`() {
        val passphrase = ByteArray(32) { 0x42 }
        val factory = DatabaseFiles.SafeEncryptedOpenHelperFactory(passphrase)
        passphrase.fill(0)

        val held = DatabaseFiles.SafeEncryptedOpenHelperFactory::class.java
            .getDeclaredField("key").apply { isAccessible = true }.get(factory) as ByteArray

        assertTrue(
            "zeroing the caller's array must not zero the key Room opens with",
            held.all { it == 0x42.toByte() }
        )
    }

    @Test
    fun `reencrypt without a live file is false and creates nothing`() {
        val before = db.parentFile!!.list()!!.toSet()
        assertFalse(DatabaseFiles.reencrypt(context, null, ByteArray(32) { 1 }))
        assertEquals(before, db.parentFile!!.list()!!.toSet())
    }

    @Test
    fun `moveAside renames the file and every sidecar, keeping the suffixes`() {
        db.writeText("main")
        File(db.path + "-wal").writeText("wal")
        File(db.path + "-shm").writeText("shm")
        File(db.path + "-journal").writeText("journal")
        // A neighbour that merely shares the prefix must stay.
        val neighbour = File(db.parentFile, KeyboardDatabase.DATABASE_NAME + "_other").apply { writeText("x") }

        val target = DatabaseFiles.moveAside(context, "unreadable")

        assertNotNull(target)
        assertTrue(target!!.name.startsWith(KeyboardDatabase.DATABASE_NAME + ".unreadable-"))
        assertEquals("main", target.readText())
        assertEquals("wal", File(target.path + "-wal").readText())
        assertEquals("shm", File(target.path + "-shm").readText())
        assertEquals("journal", File(target.path + "-journal").readText())
        assertFalse(db.exists())
        assertFalse(File(db.path + "-wal").exists())
        assertTrue(neighbour.exists())
    }

    @Test
    fun `moveAside with only sidecars present still clears them`() {
        File(db.path + "-wal").writeText("stale")

        val target = DatabaseFiles.moveAside(context, "corrupt")

        assertNotNull(target)
        assertFalse(File(db.path + "-wal").exists())
        assertTrue(File(target!!.path + "-wal").exists())
    }

    @Test
    fun `moveAside with nothing present frees the path and touches nothing`() {
        val before = db.parentFile!!.list()!!.toSet()

        val target = DatabaseFiles.moveAside(context, "unreadable")

        assertNotNull(target)
        assertFalse(target!!.exists())
        assertEquals(before, db.parentFile!!.list()!!.toSet())
    }
}
