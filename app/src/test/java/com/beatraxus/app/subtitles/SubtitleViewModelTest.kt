package com.beatraxus.app.subtitles

import android.app.Application
import android.net.Uri
import com.beatraxus.app.model.Video
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCache
import com.beatraxus.app.subtitles.domain.DownloadInfo
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import com.beatraxus.app.subtitles.domain.SubtitleLanguage
import com.beatraxus.app.subtitles.domain.SubtitleRepository
import com.beatraxus.app.subtitles.domain.SubtitleResult
import com.beatraxus.app.subtitles.domain.SubtitleSearchQuery
import com.beatraxus.app.subtitles.player.SubtitlePlayerController
import com.beatraxus.app.subtitles.viewmodel.AutoSearchMode
import com.beatraxus.app.subtitles.viewmodel.SubtitleViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class FakeSubtitleRepository : SubtitleRepository {
    var searchCount = 0
    var downloadRequestCount = 0
    var searchResult: Result<List<SubtitleResult>> = Result.success(emptyList())
    var downloadInfoResult: Result<DownloadInfo> = Result.success(DownloadInfo("https://example.com/sub.srt", "sub.srt", 10, null))

    override suspend fun searchSubtitles(query: SubtitleSearchQuery): Result<List<SubtitleResult>> {
        searchCount++
        return searchResult
    }

    override suspend fun getLanguages(): Result<List<SubtitleLanguage>> {
        return Result.success(listOf(SubtitleLanguage("en", "English"), SubtitleLanguage("es", "Spanish")))
    }

    override suspend fun requestDownload(fileId: Long): Result<DownloadInfo> {
        downloadRequestCount++
        return downloadInfoResult
    }

    override suspend fun downloadToFile(downloadUrl: String, destination: File): Result<File> {
        destination.writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n", Charsets.UTF_8)
        return Result.success(destination)
    }

    override fun getLastKnownRemainingDownloads(): Int = 10
    override fun getLastKnownResetTime(): String? = null
}

class FakeSubtitlePlayerController : SubtitlePlayerController {
    override val activeExternalTrackId = MutableStateFlow<String?>(null)
    override val activeExternalSubtitleName = MutableStateFlow<String?>(null)
    override val isDelaySupported = MutableStateFlow(false)
    override val currentDelayMs = MutableStateFlow(0L)

    var attachCount = 0

    override suspend fun attachExternalSubtitle(
        videoId: String, file: File, language: String?, label: String, mimeType: String, subtitleId: String
    ) {
        attachCount++
        activeExternalTrackId.value = subtitleId
        activeExternalSubtitleName.value = label
        isDelaySupported.value = true
    }

    override suspend fun removeExternalSubtitle(videoId: String) {
        activeExternalTrackId.value = null
        activeExternalSubtitleName.value = null
        isDelaySupported.value = false
    }

    override fun selectEmbeddedTrack(groupIndex: Int) {}
    override fun disableSubtitles() {}
    override fun setSubtitleDelay(videoId: String, delayMs: Long, originalFileProvider: () -> File?) {}
    override fun detachPlayer() {}
}

class SubtitleViewModelTest {

    private lateinit var fakeContext: FakeTestContext
    private lateinit var fakeRepository: FakeSubtitleRepository
    private lateinit var fakeController: FakeSubtitlePlayerController
    private lateinit var fakeCredentialsStore: FakeSubtitleCredentialsStore
    private lateinit var authManager: SubtitleAuthManager
    private lateinit var viewModel: SubtitleViewModel

    @Before
    fun setUp() {
        fakeContext = FakeTestContext()
        fakeRepository = FakeSubtitleRepository()
        fakeController = FakeSubtitlePlayerController()
        fakeCredentialsStore = FakeSubtitleCredentialsStore(fakeContext)
        authManager = SubtitleAuthManager(FakeOpenSubtitlesApi(), fakeCredentialsStore)

        val app = fakeContext.applicationContext as Application
        viewModel = SubtitleViewModel(app, fakeRepository, authManager, fakeController, fakeCredentialsStore, SubtitleCache(fakeContext))
    }

    @Test
    fun testPreferredLanguageSelectionAndPersistence() {
        viewModel.setPreferredLanguages(listOf("es", "en"))
        assertEquals(listOf("es", "en"), viewModel.uiState.value.selectedLanguages)
    }

    @Test
    fun testNoDuplicateDownloadIfAlreadyCached() {
        runBlocking {
            val video = Video(
                id = "vid_101",
                uri = Uri.parse("content://media/external/video/media/101"),
                title = "Test Video",
                displayName = "Test.Video.2023.mkv",
                folderPath = "/storage/Movies",
                durationMs = 120000L,
                sizeBytes = 5000000L,
                resolutionWidth = 1920,
                resolutionHeight = 1080,
                mimeType = "video/mp4",
                dateAdded = 1000L
            )

            viewModel.onVideoChanged(video)

            val subResult = SubtitleResult(
                id = "sub_99",
                fileId = 99L,
                fileName = "Test.Video.srt",
                releaseName = "Test.Video.2023",
                language = "en",
                languageName = "English",
                downloadCount = 100,
                isHearingImpaired = false,
                isMachineTranslated = false,
                isAiTranslated = false,
                rating = 8.0f,
                uploaderName = "User",
                fps = 24f,
                featureTitle = "Test Video",
                year = 2023,
                imdbId = null,
                tmdbId = null
            )

            viewModel.downloadAndApply(subResult)
            val initialDownloadRequests = fakeRepository.downloadRequestCount
            assertEquals(1, initialDownloadRequests)

            viewModel.downloadAndApply(subResult)
            assertEquals(initialDownloadRequests, fakeRepository.downloadRequestCount)
        }
    }

    @Test
    fun testAutoSearchRulesDoesNotAutoDownload() {
        runBlocking {
            viewModel.setAutoSearchMode(AutoSearchMode.ON)

            val sampleSub = SubtitleResult(
                id = "sub_1", fileId = 1L, fileName = "Sample.srt", releaseName = "Sample",
                language = "en", languageName = "English", downloadCount = 50, isHearingImpaired = false,
                isMachineTranslated = false, isAiTranslated = false, rating = 7f, uploaderName = null,
                fps = null, featureTitle = "Sample", year = null, imdbId = null, tmdbId = null
            )
            fakeRepository.searchResult = Result.success(listOf(sampleSub))

            val video = Video(
                id = "vid_202",
                uri = Uri.parse("content://media/external/video/media/202"),
                title = "Auto Search Video",
                displayName = "Auto.Search.Video.mkv",
                folderPath = "/storage/Movies",
                durationMs = 60000L,
                sizeBytes = 2000000L,
                resolutionWidth = 1280,
                resolutionHeight = 720,
                mimeType = "video/mp4",
                dateAdded = 2000L
            )

            viewModel.onVideoChanged(video)

            assertEquals(1, fakeRepository.searchCount)
            assertEquals(0, fakeRepository.downloadRequestCount)
            assertEquals(1, viewModel.uiState.value.results.size)
        }
    }

    @Test
    fun testRateLimitErrorHandledInState() {
        runBlocking {
            fakeRepository.searchResult = Result.failure(SubtitleException(SubtitleError.ApiRateLimited(15)))

            val video = Video(
                id = "vid_303",
                uri = Uri.parse("content://media/external/video/media/303"),
                title = "Rate Limit Video",
                displayName = "Rate.Limit.Video.mkv",
                folderPath = "/storage/Movies",
                durationMs = 60000L,
                sizeBytes = 2000000L,
                resolutionWidth = 1280,
                resolutionHeight = 720,
                mimeType = "video/mp4",
                dateAdded = 3000L
            )

            viewModel.onVideoChanged(video)
            viewModel.searchOnline()

            assertFalse(viewModel.uiState.value.isSearching)
            assertTrue(viewModel.uiState.value.error is SubtitleError.ApiRateLimited)
            assertTrue(viewModel.uiState.value.errorMessage!!.contains("Too many requests"))
        }
    }
}
