package com.beatraxus.app.engine

import com.beatraxus.app.model.StemQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StemModelDownloaderTest {
    @Test
    fun everyPublishedStemModelHasAValidPinnedSha256() {
        listOf(StemQuality.FAST, StemQuality.MAX).forEach { quality ->
            val model = StemModelDownloader.modelFor(quality)
            assertNotNull("${quality.name} model must have a pinned checksum", model.sha256)
            assertTrue(
                "${quality.name} model checksum must be 64 hexadecimal characters",
                Regex("^[0-9a-fA-F]{64}$").matches(model.sha256.orEmpty())
            )
        }
    }

    @Test
    fun maxModelUsesThePinnedImmutableRevision() {
        assertEquals(
            "https://huggingface.co/StemSplitio/htdemucs-6s-onnx/resolve/49df9b6989cf2150840ea65b0bef77a2e471b678/htdemucs_6s_fp16weights.onnx",
            StemModelDownloader.MAX_MODEL.url
        )
    }
}
