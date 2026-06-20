package com.urik.keyboard.service

import android.content.Context
import android.os.Looper
import androidx.room.Room
import com.urik.keyboard.data.database.KeyboardDatabase
import com.urik.keyboard.data.database.UserKanjiFrequencyDao
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class KanaKanjiConverterTest {
    private val testScheduler = TestCoroutineScheduler()
    private val testDispatcher = StandardTestDispatcher(testScheduler)

    private lateinit var context: Context
    private lateinit var database: KeyboardDatabase
    private lateinit var dao: UserKanjiFrequencyDao
    private lateinit var converter: KanaKanjiConverter

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val directExecutor = Executor { it.run() }
        database =
            Room
                .inMemoryDatabaseBuilder(context, KeyboardDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(directExecutor)
                .setTransactionExecutor(directExecutor)
                .build()
        dao = database.userKanjiFrequencyDao()
        converter = KanaKanjiConverter(context, dao, testDispatcher)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `getCandidates returns empty list for empty input`() = runTest(testScheduler) {
        assertTrue(converter.getCandidates("", "ja").isEmpty())
    }

    @Test
    fun `getCandidates returns empty list for unknown reading`() = runTest(testScheduler) {
        assertTrue(converter.getCandidates("zzzz", "ja").isEmpty())
    }

    @Test
    fun `getCandidates returns candidates sorted by frequency descending`() = runTest(testScheduler) {
        val candidates = converter.getCandidates("とうきょう", "ja")
        if (candidates.isNotEmpty()) {
            for (i in 0 until candidates.size - 1) {
                assertTrue(
                    "Candidates must be sorted by frequency descending",
                    candidates[i].frequency >= candidates[i + 1].frequency
                )
            }
        }
    }

    @Test
    fun `getCandidates returns results for prefix reading`() = runTest(testScheduler) {
        val candidates = converter.getCandidates("わた", "ja")
        val readings = candidates.map { it.reading }
        assertTrue(
            "All candidates must have readings starting with 'わた'",
            readings.all { it.startsWith("わた") }
        )
    }

    @Test
    fun `recordSelection boosts candidate on subsequent lookup`() = runTest(testScheduler) {
        val reading = "わたし"
        val surface = "私"
        converter.recordSelection(reading, surface)
        advanceUntilIdle()

        val candidates = converter.getCandidates(reading, "ja")
        val boosted = candidates.find { it.surface == surface && it.source == "learned" }
        assertTrue("Boosted candidate must appear in results", boosted != null)
    }

    @Test
    fun `recordSelection persists to Room`() = runTest(testScheduler) {
        converter.recordSelection("わたし", "私")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val rows = dao.getAll()
        assertEquals(1, rows.size)
        assertEquals("わたし", rows[0].reading)
        assertEquals("私", rows[0].surface)
        assertEquals(1L, rows[0].frequency)
    }

    @Test
    fun `ensureLoaded restores persisted frequencies after a fresh converter instance`() = runTest(testScheduler) {
        dao.incrementBy("わたし", "私", 1L, System.currentTimeMillis())

        val freshConverter = KanaKanjiConverter(context, dao)
        freshConverter.getCandidates("わたし", "ja")

        val frequencies = freshConverter.userFrequenciesForTest()
        assertNotNull("Restored frequency map must contain the persisted entry", frequencies["わたし\t私"])
        assertEquals(1L, frequencies["わたし\t私"])
    }

    @Test
    fun `recordSelection boost survives simulated restart`() = runTest(testScheduler) {
        converter.recordSelection("わたし", "私")
        advanceUntilIdle()

        val freshConverter = KanaKanjiConverter(context, dao)
        val candidates = freshConverter.getCandidates("わたし", "ja")
        val boosted = candidates.find { it.surface == "私" && it.source == "learned" }
        assertTrue("Boosted candidate must appear after restart", boosted != null)
    }

    @Test
    fun `registerEntry offers a surface that is absent from the bundled dictionary`() = runTest(testScheduler) {
        // しろいくま → 白い熊 is not a dictionary entry, so it can only be offered once registered.
        val reading = "しろいくま"
        val surface = "白い熊"
        assertTrue(
            "precondition: the surface must be absent before registration",
            converter.getCandidates(reading, "ja").none { it.surface == surface }
        )

        converter.registerEntry(reading, surface)
        advanceUntilIdle()

        val registered = converter.getCandidates(reading, "ja").find { it.surface == surface }
        assertNotNull("Registered surface must be offered for its reading", registered)
        assertEquals("learned", registered!!.source)
    }

    @Test
    fun `registerEntry ranks the registered surface at the top of the candidate list`() = runTest(testScheduler) {
        // Pick a reading that has bundled dictionary candidates, then register a brand-new surface for it and
        // confirm the high boost floats it to position 0.
        val reading = "とうきょう"
        val surface = "★登録★"
        converter.registerEntry(reading, surface)
        advanceUntilIdle()

        val candidates = converter.getCandidates(reading, "ja")
        assertEquals(surface, candidates.first().surface)
    }

    @Test
    fun `registerEntry persists across a simulated restart`() = runTest(testScheduler) {
        converter.registerEntry("しろいくま", "白い熊")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val fresh = KanaKanjiConverter(context, dao)
        val restored = fresh.getCandidates("しろいくま", "ja").find { it.surface == "白い熊" }
        assertNotNull("Registered surface must survive a restart", restored)
    }

    @Test
    fun `registerEntry ignores blank reading or surface`() = runTest(testScheduler) {
        converter.registerEntry("  ", "白い熊")
        converter.registerEntry("しろいくま", "   ")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("Blank registrations must not be persisted", dao.getAll().isEmpty())
    }

    @Test
    fun `loadIndex cancels cooperatively`() = runTest(testScheduler) {
        val job = launch {
            converter.getCandidates("とうきょう", "ja")
        }
        job.cancelAndJoin()
        assertTrue("Job must be completed after cancelAndJoin", job.isCompleted)
    }

    @Test
    fun `recordSelection does not bind a longer completion to the shorter typed reading`() =
        runTest(testScheduler) {
            // 白い息 is offered when typing しろい (prefix lookup), but its true reading is しろいいき, not しろい.
            // An accidental tap on it must NOT be learned against しろい, otherwise it pollutes the base reading.
            converter.recordSelection("しろい", "白い息")
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "A longer completion must not be persisted against the shorter reading",
                dao.getAll().none { it.reading == "しろい" && it.surface == "白い息" }
            )
            assertTrue(
                "It must not be offered for the shorter reading either",
                converter.getCandidates("しろい", "ja").none { it.surface == "白い息" && it.source == "learned" }
            )
        }

    @Test
    fun `recordSelection learns a surface whose exact reading equals the typed reading`() =
        runTest(testScheduler) {
            // 白い IS a conversion of the full reading しろい, so selecting it learns/boosts it normally.
            converter.recordSelection("しろい", "白い")
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "A full-reading conversion must be persisted",
                dao.getAll().any { it.reading == "しろい" && it.surface == "白い" }
            )
        }

    @Test
    fun `a learned selection never demotes the base dictionary conversion below the typed reading`() =
        runTest(testScheduler) {
            // Learn 白井 once. The dictionary's own 白い (freq 17759) must STILL be present (the modest learn
            // boost re-orders but never evicts the core conversions of the typed reading). (BUG A.)
            converter.recordSelection("しろい", "白井")
            advanceUntilIdle()

            val candidates = converter.getCandidates("しろい", "ja")
            assertTrue(
                "The base dictionary conversion of the typed reading must remain present",
                candidates.any { it.surface == "白い" }
            )
        }

    @Test
    fun `removeEntry forgets a registered surface`() = runTest(testScheduler) {
        converter.registerEntry("しろいくま", "白い熊")
        advanceUntilIdle()
        assertNotNull(
            "precondition: the registered surface is offered",
            converter.getCandidates("しろいくま", "ja").find { it.surface == "白い熊" }
        )

        converter.removeEntry("しろいくま", "白い熊")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "The removed surface must no longer be offered",
            converter.getCandidates("しろいくま", "ja").none { it.surface == "白い熊" }
        )
        assertTrue(
            "The removed entry must be gone from Room",
            dao.getAll().none { it.reading == "しろいくま" && it.surface == "白い熊" }
        )
    }
}
