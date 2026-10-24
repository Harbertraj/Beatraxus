package com.beatraxus.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SingerAssignmentTest {

    private fun speakers(vararg agents: String?, types: Map<String, String> = emptyMap()) =
        SingerAssignment.assign(agents.toList(), types).map { it.speaker }

    @Test
    fun `female is not mistaken for male`() {
        assertEquals(LyricSpeaker.FEMALE, SingerAssignment.genderOf("female_v2"))
        assertEquals(LyricSpeaker.MALE, SingerAssignment.genderOf("male_v1"))
    }

    @Test
    fun `she is not mistaken for he`() {
        assertEquals(LyricSpeaker.FEMALE, SingerAssignment.genderOf("she"))
        assertEquals(LyricSpeaker.MALE, SingerAssignment.genderOf("he"))
    }

    @Test
    fun `words that merely contain a gender word are not gendered`() {
        assertNull(SingerAssignment.genderOf("shepherd"))
        assertNull(SingerAssignment.genderOf("v1"))
        assertNull(SingerAssignment.genderOf("fiona"))
    }

    @Test
    fun `explicit male and female voices keep their gender`() {
        val out = SingerAssignment.assign(listOf("male_v1", "female_v2", "male_v1"))
        assertEquals(listOf("[Male]", "[Female]", "[Male]"), out.map { it.marker })
        assertEquals(listOf(LyricSpeaker.MALE, LyricSpeaker.FEMALE, LyricSpeaker.MALE), out.map { it.speaker })
    }

    @Test
    fun `unlabelled voices alternate sides on every change of singer`() {
        assertEquals(
            listOf(LyricSpeaker.MALE, LyricSpeaker.MALE, LyricSpeaker.FEMALE, LyricSpeaker.MALE, LyricSpeaker.FEMALE),
            speakers("v1", "v1", "v2", "v1", "v2")
        )
    }

    @Test
    fun `three voices never share one side back to back`() {
        val s = speakers("v1", "v2", "v3", "v1")
        assertEquals(listOf(LyricSpeaker.MALE, LyricSpeaker.FEMALE, LyricSpeaker.MALE, LyricSpeaker.FEMALE), s)
    }

    @Test
    fun `group lines are centred and do not change whose turn it is`() {
        val s = speakers("v1", "v1000", "v2", "v1000", "v1")
        assertEquals(
            listOf(LyricSpeaker.MALE, LyricSpeaker.DUET_BOTH, LyricSpeaker.FEMALE, LyricSpeaker.DUET_BOTH, LyricSpeaker.MALE),
            s
        )
    }

    @Test
    fun `type group from the head declaration is centred`() {
        val s = speakers("a", "crowd", "b", types = mapOf("crowd" to "group"))
        assertEquals(LyricSpeaker.DUET_BOTH, s[1])
    }

    @Test
    fun `chorus agents get the chorus speaker`() {
        assertEquals(LyricSpeaker.CHORUS, speakers("v1", "chorus", "v2")[1])
    }

    @Test
    fun `a lone other-type voice has nothing to differentiate`() {
        // other starts on the end side; v1 answers on the start side. 100% end would be mirrored.
        val s = speakers("v2000", "v2000", "v2000", types = mapOf("v2000" to "other"))
        assertEquals(listOf(LyricSpeaker.NONE, LyricSpeaker.NONE, LyricSpeaker.NONE), s) // single voice: nothing to separate
    }

    @Test
    fun `mostly end side song is flipped`() {
        // 9 lines of v2 first (end side after other opening) and one v1: >85% end -> flipped.
        val agents = List(9) { "v2" } + "v1"
        val s = SingerAssignment.assign(agents, mapOf("v2" to "other", "v1" to "person")).map { it.speaker }
        assertEquals(LyricSpeaker.MALE, s[0])
        assertEquals(LyricSpeaker.FEMALE, s[9])
    }

    @Test
    fun `single unlabelled voice is not differentiated`() {
        assertEquals(listOf(LyricSpeaker.NONE, LyricSpeaker.NONE), speakers("v1", "v1"))
    }

    @Test
    fun `lines with no agent stay undifferentiated`() {
        assertEquals(
            listOf(LyricSpeaker.NONE, LyricSpeaker.MALE, LyricSpeaker.FEMALE),
            speakers(null, "v1", "v2")
        )
    }

    @Test
    fun `single voice that names its gender keeps it`() {
        assertEquals(listOf(LyricSpeaker.FEMALE, LyricSpeaker.FEMALE), speakers("female_v1", "female_v1"))
    }
}
