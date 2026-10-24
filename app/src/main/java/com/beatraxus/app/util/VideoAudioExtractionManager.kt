package com.beatraxus.app.util

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.beatraxus.app.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs "extract audio track" jobs and publishes their progress. One place for both entry points: the Audio Tracks
 * sheet of the video player and the long-press menu of the video library. Runs on its own scope, so a started
 * extraction still finishes when the player or the menu is closed. Only one extraction runs at a time.
 */
object VideoAudioExtractionManager {

    /** @property language label shown as "[Tamil]"; @property progress 0f..1f. */
    data class State(val videoId: String, val language: String, val progress: Float)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _state = MutableStateFlow<State?>(null)
    /** Null while nothing is being extracted. */
    val state: StateFlow<State?> = _state.asStateFlow()

    /**
     * @param nativeAudioIndex position of the track among the audio tracks inside the video file.
     * @param language "Tamil" (or the track's name); the saved file's title starts with it as "[Tamil]".
     * @param treeUri folder picked with ACTION_OPEN_DOCUMENT_TREE.
     */
    fun start(context: Context, video: Video, nativeAudioIndex: Int, language: String, treeUri: Uri) {
        val app = context.applicationContext
        if (_state.value != null) {
            Toast.makeText(app, "Another audio extraction is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val title = video.title.ifBlank { video.displayName.substringBeforeLast('.') }
        val baseName = VideoAudioExtractor.buildBaseName(title, language)

        _state.value = State(video.id, language, 0f)
        Toast.makeText(app, "Extracting audio [$language]…", Toast.LENGTH_SHORT).show()
        job = scope.launch {
            var cancelled = false
            val result = try {
                VideoAudioExtractor.extract(
                    context = app,
                    source = video.uri,
                    nativeAudioIndex = nativeAudioIndex,
                    treeUri = treeUri,
                    baseName = baseName,
                    onProgress = { p -> _state.update { s -> s?.copy(progress = p) } },
                    fallbackDurationUs = video.durationMs * 1000L
                )
            } catch (e: CancellationException) {
                cancelled = true
                null
            } finally {
                _state.value = null
            }
            withContext(NonCancellable + Dispatchers.Main) {
                val text = when {
                    cancelled || result == null -> "Extraction cancelled"
                    result is VideoAudioExtractor.Result.Saved -> "Audio saved: ${result.fileName}"
                    result is VideoAudioExtractor.Result.Failed -> result.reason
                    else -> ""
                }
                if (text.isNotEmpty()) Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Stops a running extraction; the half-written file is deleted by the extractor. */
    fun cancel() {
        job?.cancel()
    }
}
