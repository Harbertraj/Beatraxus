package com.beatraxus.app.engine.aieq

import android.content.Context
import android.util.Log
import com.beatraxus.app.engine.PcmFeatureSource
import com.beatraxus.app.model.Song
import com.beatraxus.app.model.SongSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the AI EQ lifecycle: predicts the playing song immediately and PRELOADS the next [PRELOAD_COUNT]
 * songs of the queue so their EQ is already waiting when they start. Works while music plays (the audio
 * measurement is short and runs on a background thread) and always ends with a profile for every song.
 */
class AiEqManager(
    context: Context,
    private val scope: CoroutineScope,
    pcmFeatureSource: PcmFeatureSource? = null,
    /** Called on a background thread when a profile for [songId] has just been produced. */
    private val onProfileReady: (AiEqProfile) -> Unit = {}
) {
    private val store = AiEqStore.getInstance(context)
    private val engine = AiEqEngine(context.applicationContext, pcmFeatureSource)

    private val _profiles = MutableStateFlow<Map<String, AiEqProfile>>(emptyMap())
    /** Profiles of every song predicted so far (songId -> profile). */
    val profiles: StateFlow<Map<String, AiEqProfile>> = _profiles.asStateFlow()

    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    /** Songs being predicted right now or waiting in the queue. */
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val lock = Any()
    private var urgent: Song? = null
    private val pending = LinkedHashMap<String, Song>()
    private val inProgress = HashSet<String>()
    private val upgradeTried = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.Default) {
            try { _profiles.value = store.snapshot() } catch (_: Throwable) {}
            for (signal in wake) {
                while (true) {
                    val song = nextSong() ?: break
                    try {
                        val profile = engine.predict(song)
                        store.put(profile)
                        _profiles.update { it + (profile.songId to profile) }
                        try { onProfileReady(profile) } catch (t: Throwable) { Log.w(TAG, "onProfileReady failed: ${t.message}") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // predict() is built not to throw; if it ever does, store a neutral profile rather than "failed".
                        Log.e(TAG, "Prediction failed for ${song.title}: ${t.message}", t)
                        val fallback = AiEqProfile(
                            songId = song.id, genre = "Pop", mood = "Happy", language = "English",
                            gains = AiEqKnowledge.curveFor("Pop"), confidence = 0.2f, source = AiEqProfile.Source.METADATA
                        )
                        store.put(fallback)
                        _profiles.update { it + (fallback.songId to fallback) }
                        try { onProfileReady(fallback) } catch (_: Throwable) {}
                    } finally {
                        synchronized(lock) { inProgress.remove(song.id) }
                        publishBusy()
                    }
                    store.flush()
                    delay(150) // breathing room for the decoder / CPU between songs
                }
            }
        }
    }

    /** Ready profile for [songId] from memory or disk cache (current engine version only), else null. */
    fun cached(songId: String): AiEqProfile? = _profiles.value[songId]?.takeIf { it.version == AiEqKnowledge.ENGINE_VERSION }
        ?: store.get(songId)

    /** Predict [song] now (jumps the queue). No-op if a fresh profile exists. */
    fun requestNow(song: Song) {
        if (!needsWork(song)) return
        synchronized(lock) {
            urgent = song
            pending.remove(song.id)
        }
        publishBusy()
        wake.trySend(Unit)
    }

    /**
     * The playing song first, then the next [count] songs of the queue in order. Anything queued earlier that
     * is no longer in the upcoming window (user skipped / reshuffled) is dropped.
     */
    fun preload(current: Song?, upcoming: List<Song>, count: Int = PRELOAD_COUNT) {
        synchronized(lock) {
            pending.clear()
            current?.takeIf { needsWork(it) }?.let { urgent = it }
            for (s in upcoming.asSequence().take(count)) {
                if (s.id != current?.id && needsWork(s) && s.id != urgent?.id) pending[s.id] = s
            }
        }
        publishBusy()
        wake.trySend(Unit)
    }

    /** Forget everything queued (AI EQ switched off). Cached profiles are kept. */
    fun cancelPending() {
        synchronized(lock) { urgent = null; pending.clear() }
        publishBusy()
    }

    /** Throws away every cached profile and re-predicts the playing song / queue on the next preload. */
    fun resetAll() {
        store.clear()
        _profiles.value = emptyMap()
        synchronized(lock) { upgradeTried.clear() }
    }

    private fun needsWork(song: Song): Boolean {
        val have = cached(song.id)
        if (have == null) return true
        // A tags-only profile of a local file is upgraded once per session in case the audio is readable now.
        return have.source == AiEqProfile.Source.METADATA && song.source == SongSource.LOCAL &&
            synchronized(lock) { song.id !in upgradeTried }
    }

    private fun nextSong(): Song? = synchronized(lock) {
        val s = urgent?.also { urgent = null } ?: pending.entries.firstOrNull()?.also { pending.remove(it.key) }?.value
        if (s != null) { inProgress.add(s.id); upgradeTried.add(s.id) }
        s
    }

    private fun publishBusy() {
        val ids = synchronized(lock) {
            HashSet<String>().also { set ->
                set.addAll(inProgress); urgent?.let { set.add(it.id) }; set.addAll(pending.keys)
            }
        }
        _busy.value = ids
    }

    companion object {
        private const val TAG = "AiEqManager"
        const val PRELOAD_COUNT = 8
    }
}
