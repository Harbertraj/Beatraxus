package com.beatraxus.app.engine

import org.junit.Assert.*
import org.junit.Test

class NlEqAssistantTest {

    private val assistant = NlEqAssistant()

    @Test
    fun testValidJsonResponseParsing() {
        val validJson = """
            {
                "gains": [2.0, 1.5, 1.0, 0.0, -1.0, 0.5, 1.0, 2.0, 1.5, 0.5],
                "preampDb": -2.0,
                "explanation": "Boosted bass and presence for vocal clarity."
            }
        """.trimIndent()

        val result = assistant.parseAndValidateResponse(validJson)
        assertTrue("Valid JSON should parse successfully", result.isSuccess)

        val response = result.getOrNull()
        assertNotNull(response)
        assertEquals(10, response!!.gains.size)
        assertEquals(2.0f, response.gains[0], 1e-3f)
        assertEquals(-2.0f, response.preampDb, 1e-3f)
        assertEquals("Boosted bass and presence for vocal clarity.", response.explanation)
    }

    @Test
    fun testMarkdownWrappedJson() {
        val markdownJson = """
            ```json
            {
                "gains": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
                "preampDb": 0.0,
                "explanation": "Flat EQ"
            }
            ```
        """.trimIndent()

        val result = assistant.parseAndValidateResponse(markdownJson)
        assertTrue("Markdown-wrapped JSON should be cleaned and parsed", result.isSuccess)
    }

    @Test
    fun testInvalidJsonSchemaRejection() {
        // Only 5 gains instead of 10
        val invalidLengthJson = """
            {
                "gains": [1.0, 2.0, 3.0, 4.0, 5.0],
                "explanation": "Invalid length"
            }
        """.trimIndent()

        val result = assistant.parseAndValidateResponse(invalidLengthJson)
        assertTrue("Invalid gain array length should return failure", result.isFailure)
    }

    @Test
    fun testGainClampingToBounds() {
        // Gain value 20.0 exceeds max limit of 12.0
        val extremeJson = """
            {
                "gains": [20.0, -15.0, 0, 0, 0, 0, 0, 0, 0, 0],
                "explanation": "Extreme gains"
            }
        """.trimIndent()

        val result = assistant.parseAndValidateResponse(extremeJson)
        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals(12.0f, response.gains[0], 1e-3f)
        assertEquals(-12.0f, response.gains[1], 1e-3f)
    }

    @Test
    fun testOfflineFallbackKeywords() {
        val bassResponse = assistant.generateOfflineFallback("more bass and warm sound")
        assertTrue("Bass frequencies should be boosted", bassResponse.gains[0] > 0.0f)
        assertTrue("Preamp should be non-positive to avoid clipping", bassResponse.preampDb <= 0.0f)

        val vocalResponse = assistant.generateOfflineFallback("more vocal clarity")
        assertTrue("Upper midrange should be boosted", vocalResponse.gains[6] > 0.0f)
    }
}
