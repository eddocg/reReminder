package com.olaf.rereminder.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored id is all that survives an update, so it must keep resolving to a real sound. */
class GentleSoundTest {

    @Test
    fun `there are exactly three bundled sounds`() {
        assertEquals(3, GentleSound.entries.size)
    }

    @Test
    fun `every sound is found again by its stored id`() {
        GentleSound.entries.forEach { assertEquals(it, GentleSound.fromId(it.id)) }
    }

    @Test
    fun `ids and raw resources are unique`() {
        assertEquals(GentleSound.entries.size, GentleSound.entries.map { it.id }.toSet().size)
        assertEquals(GentleSound.entries.size, GentleSound.entries.map { it.rawRes }.toSet().size)
    }

    @Test
    fun `a missing or unknown id falls back to the soft chime`() {
        assertEquals(GentleSound.SOFT_CHIME, GentleSound.DEFAULT)
        assertEquals(GentleSound.DEFAULT, GentleSound.fromId(null))
        assertEquals(GentleSound.DEFAULT, GentleSound.fromId("air_horn"))
    }
}
