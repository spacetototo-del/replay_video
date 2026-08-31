package com.diving.replay.camera

import com.diving.replay.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The ring has to delete old footage reliably (the phone would otherwise fill up over a session)
 * while never dropping something a marker might still need. Both halves are checked here.
 */
class SegmentRotationManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val retention = Constants.BUFFER_RETENTION_MS
    private val margin = Constants.BUFFER_SAFETY_MARGIN_MS

    private fun manager() = SegmentRotationManager(temp.root, retention)

    /** Registers a real (empty) file so deletion can actually be observed. */
    private fun SegmentRotationManager.add(startedAtMs: Long, durationMs: Long = 15_000): File {
        val f = newSegmentFile(startedAtMs).apply { writeText("x") }
        register(f, startedAtMs, durationMs)
        return f
    }

    @Test
    fun `a new session starts from an empty buffer`() {
        // Footage from a previous run is separated from now by an unknown gap, so keeping it
        // would report bogus coverage and let the scrubber wander into unrelated old video.
        val leftover = File(temp.root, "seg_1234.mp4").apply { writeText("old") }
        val other = File(temp.root, "notes.txt").apply { writeText("keep me") }

        val m = manager()

        assertFalse(leftover.exists())
        assertTrue("only mp4 buffer files are cleared", other.exists())
        assertTrue(m.segments.value.isEmpty())
    }

    @Test
    fun `registered segments are published oldest first`() {
        val m = manager()
        val now = System.currentTimeMillis()
        m.add(now)
        m.add(now + 15_000)

        assertEquals(2, m.segments.value.size)
        assertEquals(now, m.segments.value.first().startedAtMs)
    }

    @Test
    fun `footage past retention plus margin is deleted from disk`() {
        val m = manager()
        val now = System.currentTimeMillis()
        val old = m.add(now)
        val recent = m.add(now + retention + margin)

        m.prune(now = now + retention + margin + 20_000)

        assertFalse("stale segment should be gone", old.exists())
        assertTrue("recent segment must survive", recent.exists())
        assertEquals(1, m.segments.value.size)
    }

    @Test
    fun `footage inside the retention window is kept`() {
        val m = manager()
        val now = System.currentTimeMillis()
        val f = m.add(now)

        m.prune(now = now + retention) // still inside retention + margin

        assertTrue(f.exists())
        assertEquals(1, m.segments.value.size)
    }

    @Test
    fun `snapshot is ordered oldest first and detached from the ring`() {
        val m = manager()
        val now = System.currentTimeMillis()
        m.add(now + 15_000)
        m.add(now)

        val snap = m.snapshot()
        assertEquals(listOf(now, now + 15_000), snap.map { it.startedAtMs })

        m.clear()
        assertEquals("snapshot must not change under the caller", 2, snap.size)
    }

    @Test
    fun `isStartTruncated reports when a marker predates the buffer`() {
        val m = manager()
        val now = System.currentTimeMillis()

        assertTrue("an empty buffer can never satisfy a marker", m.isStartTruncated(now))

        m.add(now)
        assertTrue(m.isStartTruncated(now - 1))
        assertFalse(m.isStartTruncated(now))
        assertFalse(m.isStartTruncated(now + 5_000))
    }

    @Test
    fun `coverage spans the first frame to the last`() {
        val m = manager()
        val now = System.currentTimeMillis()
        assertEquals(0L, m.coverageMs())

        m.add(now)
        m.add(now + 15_300) // includes a 300ms changeover gap
        assertEquals(30_300L, m.coverageMs())
    }

    @Test
    fun `clear wipes both the list and the files`() {
        val m = manager()
        val now = System.currentTimeMillis()
        val f = m.add(now)

        m.clear()

        assertFalse(f.exists())
        assertTrue(m.segments.value.isEmpty())
        assertEquals(0L, m.coverageMs())
    }

    @Test
    fun `re-registering the same file replaces rather than duplicates it`() {
        val m = manager()
        val now = System.currentTimeMillis()
        val f = m.newSegmentFile(now).apply { writeText("x") }

        m.register(f, now, 0)
        m.register(f, now, 15_000)

        assertEquals(1, m.segments.value.size)
        assertEquals(15_000L, m.segments.value.single().durationMs)
    }
}
