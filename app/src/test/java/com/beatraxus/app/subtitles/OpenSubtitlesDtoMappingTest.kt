package com.beatraxus.app.subtitles

import com.beatraxus.app.subtitles.api.DownloadResponse
import com.beatraxus.app.subtitles.api.LanguagesResponse
import com.beatraxus.app.subtitles.api.LoginResponse
import com.beatraxus.app.subtitles.api.SubtitlesResponse
import com.beatraxus.app.subtitles.domain.SubtitleResult
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesDtoMappingTest {

    private val gson = Gson()

    @Test
    fun testSubtitlesResponseMapping() {
        val json = """
            {
              "total_pages": 1,
              "total_count": 1,
              "page": 1,
              "data": [
                {
                  "id": "sub_1001",
                  "type": "subtitle",
                  "attributes": {
                    "subtitle_id": "1001",
                    "language": "en",
                    "download_count": 1500,
                    "hearing_impaired": true,
                    "fps": 23.976,
                    "ratings": 9.0,
                    "release": "The.Matrix.1999.1080p.BluRay",
                    "uploader": {
                      "uploader_id": 42,
                      "name": "MatrixFan"
                    },
                    "feature_details": {
                      "title": "The Matrix",
                      "year": 1999,
                      "imdb_id": 133093,
                      "tmdb_id": 603
                    },
                    "files": [
                      {
                        "file_id": 9901,
                        "file_name": "The.Matrix.1999.srt"
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, SubtitlesResponse::class.java)
        assertNotNull(response)
        assertEquals(1, response.totalPages)
        assertEquals(1, response.data?.size)

        val dto = response.data!!.first()
        val attr = dto.attributes!!
        val file = attr.files!!.first()

        val mapped = SubtitleResult(
            id = dto.id ?: attr.subtitleId ?: file.fileId.toString(),
            fileId = file.fileId!!,
            fileName = file.fileName ?: attr.release ?: "subtitle.srt",
            releaseName = attr.release,
            language = attr.language ?: "en",
            languageName = attr.language,
            downloadCount = attr.downloadCount ?: 0,
            isHearingImpaired = attr.hearingImpaired ?: false,
            isMachineTranslated = attr.machineTranslated ?: false,
            isAiTranslated = attr.aiTranslated ?: false,
            rating = attr.ratings ?: 0f,
            uploaderName = attr.uploader?.name,
            fps = attr.fps,
            featureTitle = attr.featureDetails?.title,
            year = attr.featureDetails?.year,
            imdbId = attr.featureDetails?.imdbId,
            tmdbId = attr.featureDetails?.tmdbId
        )

        assertEquals("sub_1001", mapped.id)
        assertEquals(9901L, mapped.fileId)
        assertEquals("The.Matrix.1999.srt", mapped.fileName)
        assertEquals("The.Matrix.1999.1080p.BluRay", mapped.releaseName)
        assertEquals("en", mapped.language)
        assertEquals(1500, mapped.downloadCount)
        assertTrue(mapped.isHearingImpaired)
        assertEquals(9.0f, mapped.rating, 0.001f)
        assertEquals("MatrixFan", mapped.uploaderName)
        assertEquals("The Matrix", mapped.featureTitle)
        assertEquals(1999, mapped.year)
        assertEquals(133093L, mapped.imdbId)
        assertEquals(603L, mapped.tmdbId)
    }

    @Test
    fun testLoginResponseMapping() {
        val json = """
            {
              "user": {
                "user_id": 1234,
                "username": "beatraxus_user",
                "level": "user",
                "vip": false
              },
              "token": "mock_jwt_token_12345",
              "status": 200,
              "message": "User logged in successfully"
            }
        """.trimIndent()

        val response = gson.fromJson(json, LoginResponse::class.java)
        assertNotNull(response)
        assertEquals("mock_jwt_token_12345", response.token)
        assertEquals("beatraxus_user", response.user?.username)
        assertEquals(1234L, response.user?.userId)
    }

    @Test
    fun testDownloadResponseMapping() {
        val json = """
            {
              "link": "https://api.opensubtitles.com/download/file/mock_download_link",
              "file_name": "The.Matrix.1999.srt",
              "requests": 1,
              "remaining": 99,
              "message": "Download link generated",
              "reset_time": "24 hours",
              "reset_time_utc": "2023-01-02T00:00:00Z"
            }
        """.trimIndent()

        val response = gson.fromJson(json, DownloadResponse::class.java)
        assertNotNull(response)
        assertEquals("https://api.opensubtitles.com/download/file/mock_download_link", response.link)
        assertEquals("The.Matrix.1999.srt", response.fileName)
        assertEquals(99, response.remaining)
        assertEquals("24 hours", response.resetTime)
    }

    @Test
    fun testLanguagesResponseMapping() {
        val json = """
            {
              "data": [
                {
                  "language_code": "en",
                  "language_name": "English"
                },
                {
                  "language_code": "es",
                  "language_name": "Spanish"
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, LanguagesResponse::class.java)
        assertNotNull(response)
        assertEquals(2, response.data?.size)
        assertEquals("en", response.data?.get(0)?.languageCode)
        assertEquals("English", response.data?.get(0)?.languageName)
    }
}
