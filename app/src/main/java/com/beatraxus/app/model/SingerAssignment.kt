package com.beatraxus.app.model

/**
 * Works out which side / voice every lyric line belongs to from the TTML "agent" (singer id) that
 * sang it. Pure logic, no Android, so it is unit tested on the JVM.
 *
 * Why this exists: the old inline code matched agents with `String.contains`, so
 * "female_v2" also matched "male" and "she" also matched "he" – female voices were drawn as male.
 * Here agent names are split into whole words first, so only a real "male"/"female" word counts.
 *
 * Rules:
 *  1. A voice whose id/name has a gender word is MALE / FEMALE - used when the song really has
 *     both a male and a female voice (or only one voice that names its gender).
 *  2. Otherwise voices simply alternate sides every time the singer changes (a call-and-response
 *     reads as a conversation, 3+ voices never pile onto one side). First voice starts on the
 *     start side, unless it is declared type "other".
 *  3. Group / chorus voices (type "group", ids v1000, "both", "chorus" ...) are centred and do
 *     not disturb whose turn it is.
 *  4. If nearly every line ended up on the end side the whole song is mirrored back.
 */
object SingerAssignment {

    /** [marker] is the bracket tag written into the enhanced LRC (read back by SpeakerDetector). */
    data class Assigned(val speaker: LyricSpeaker, val marker: String)

    private val NONE = Assigned(LyricSpeaker.NONE, "")
    private val MALE_EXPLICIT = Assigned(LyricSpeaker.MALE, "[Male]")
    private val FEMALE_EXPLICIT = Assigned(LyricSpeaker.FEMALE, "[Female]")
    private val SLOT_START = Assigned(LyricSpeaker.MALE, "[Singer1]")
    private val SLOT_END = Assigned(LyricSpeaker.FEMALE, "[Singer2]")
    private val BOTH = Assigned(LyricSpeaker.DUET_BOTH, "[Both]")
    private val CHORUS = Assigned(LyricSpeaker.CHORUS, "[Chorus]")

    private const val GROUP_ID = "v1000"
    private const val OTHER_ID = "v2000"
    private const val MOSTLY_END = 0.85f

    private val MALE_WORDS = setOf("male", "man", "men", "boy", "he", "m")
    private val FEMALE_WORDS = setOf("female", "woman", "women", "girl", "she", "f")
    private val GROUP_WORDS = setOf("group", "both", "duet", "together", "all")
    private val CHORUS_WORDS = setOf("chorus", "choir")

    private fun words(agent: String): List<String> =
        agent.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    fun genderOf(agent: String): LyricSpeaker? {
        val w = words(agent)
        val male = w.any { it in MALE_WORDS }
        val female = w.any { it in FEMALE_WORDS }
        return when {
            male && !female -> LyricSpeaker.MALE
            female && !male -> LyricSpeaker.FEMALE
            else -> null
        }
    }

    private enum class Kind { PERSON, GROUP, CHORUS, OTHER }

    private fun kindOf(agent: String, types: Map<String, String>): Kind {
        val w = words(agent)
        if (w.any { it in CHORUS_WORDS }) return Kind.CHORUS
        if (w.any { it in GROUP_WORDS } || agent == GROUP_ID) return Kind.GROUP
        return when (types[agent]?.lowercase()) {
            "group" -> Kind.GROUP
            "other" -> Kind.OTHER
            "person" -> Kind.PERSON
            else -> if (agent == OTHER_ID) Kind.OTHER else Kind.PERSON
        }
    }

    /** [singers] one entry per line in time order (null/empty = no agent), [types] agent id -> TTML type. */
    fun assign(singers: List<String?>, types: Map<String, String> = emptyMap()): List<Assigned> {
        val used = singers.filterNotNull().filter { it.isNotEmpty() }.distinct()
        if (used.isEmpty()) return singers.map { NONE }

        val persons = used.filter { kindOf(it, types).let { k -> k == Kind.PERSON || k == Kind.OTHER } }
        val genders = persons.mapNotNull { genderOf(it) }
        val explicit = (LyricSpeaker.MALE in genders && LyricSpeaker.FEMALE in genders) ||
            (persons.size == 1 && genders.size == 1)

        if (persons.size <= 1 && !explicit) {
            // One unlabelled voice: nothing to differentiate. Group lines are still centred.
            return singers.map { s ->
                if (s.isNullOrEmpty()) NONE else when (kindOf(s, types)) {
                    Kind.GROUP -> BOTH
                    Kind.CHORUS -> CHORUS
                    else -> NONE
                }
            }
        }

        var startSide = true
        var lastVoice: String? = null
        var endCount = 0
        var placed = 0
        val result = singers.map { s ->
            if (s.isNullOrEmpty()) return@map NONE
            val kind = kindOf(s, types)
            if (kind == Kind.GROUP) return@map BOTH
            if (kind == Kind.CHORUS) return@map CHORUS
            placed++
            when {
                lastVoice == null -> startSide = kind != Kind.OTHER
                s != lastVoice -> startSide = !startSide
            }
            lastVoice = s
            if (!startSide) endCount++
            val gender = if (explicit) genderOf(s) else null
            when (gender) {
                LyricSpeaker.MALE -> MALE_EXPLICIT
                LyricSpeaker.FEMALE -> FEMALE_EXPLICIT
                else -> if (startSide) SLOT_START else SLOT_END
            }
        }

        // Mirror only pure alternation; explicit genders are never flipped.
        if (explicit || placed == 0 || endCount.toFloat() / placed < MOSTLY_END) return result
        return result.map {
            when (it) {
                SLOT_START -> SLOT_END
                SLOT_END -> SLOT_START
                else -> it
            }
        }
    }
}
