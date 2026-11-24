package com.beatraxus.app.addons.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRepositoryCodecTest {
    @Test
    fun parsesHttpsRepositoryAndResolvesRelativeManifestUrl() {
        val index = JSONObject(
            """
            {
              "schemaVersion": 1,
              "name": "Community providers",
              "plugins": [
                {
                  "id": "jamendo_community",
                  "name": "Jamendo Community",
                  "version": "1.0.0",
                  "category": "music_source",
                  "adapterId": "jamendo",
                  "manifestUrl": "plugins/jamendo.json"
                }
              ]
            }
            """.trimIndent()
        )

        val repository = PluginRepositoryCodec.parseIndex(
            "https://raw.githubusercontent.com/example/providers/main/repository.json",
            index
        )

        assertEquals("Community providers", repository.name)
        assertEquals(1, repository.plugins.size)
        assertEquals(
            "https://raw.githubusercontent.com/example/providers/main/plugins/jamendo.json",
            repository.plugins.single().manifestUrl
        )
    }

    @Test
    fun marksUnsupportedAdapterAsNotInstallable() {
        val index = JSONObject(
            """
            {
              "schemaVersion": 1,
              "name": "Invalid providers",
              "plugins": [{
                "id": "unknown_provider",
                "name": "Unknown provider",
                "version": "1.0.0",
                "category": "music_source",
                "adapterId": "downloaded_code",
                "manifestUrl": "https://example.com/provider.json"
              }]
            }
            """.trimIndent()
        )

        val repository = PluginRepositoryCodec.parseIndex("https://example.com/repository.json", index)
        assertEquals(false, repository.plugins.single().supported)
    }

    @Test
    fun rejectsNonHttpsManifestUrl() {
        val index = JSONObject(
            """
            {
              "schemaVersion": 1,
              "name": "Invalid URL",
              "plugins": [{
                "id": "jamendo_community",
                "name": "Jamendo Community",
                "version": "1.0.0",
                "category": "music_source",
                "adapterId": "jamendo",
                "manifestUrl": "http://example.com/provider.json"
              }]
            }
            """.trimIndent()
        )

        assertThrows(IllegalArgumentException::class.java) {
            PluginRepositoryCodec.parseIndex("https://example.com/repository.json", index)
        }
    }
}
