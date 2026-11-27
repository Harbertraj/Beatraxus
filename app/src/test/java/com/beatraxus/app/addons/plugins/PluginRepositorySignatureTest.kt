package com.beatraxus.app.addons.plugins

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRepositorySignatureTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun sign(payload: ByteArray): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    @Test
    fun verifiesPayloadWhenSignatureAndPinnedKeyMatch() {
        val payload = """{"schemaVersion":1,"name":"Trusted repository","plugins":[]}""".toByteArray()
        assertTrue(PluginRepositorySignature.verify(payload, sign(payload), publicKey, publicKey))
    }

    @Test
    fun rejectsPayloadTampering() {
        val signed = """{"schemaVersion":1,"name":"Trusted repository","plugins":[]}""".toByteArray()
        val changed = """{"schemaVersion":1,"name":"Changed repository","plugins":[]}""".toByteArray()
        assertFalse(PluginRepositorySignature.verify(signed.let { changed }, sign(signed), publicKey, publicKey))
    }

    @Test
    fun rejectsDifferentPinnedKey() {
        val payload = "repository metadata".toByteArray()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(other.public.encoded)
        assertFalse(PluginRepositorySignature.verify(payload, sign(payload), publicKey, otherKey))
    }

    @Test
    fun fingerprintIsStableAndGrouped() {
        val fingerprint = PluginRepositorySignature.fingerprint(publicKey)
        assertTrue(fingerprint.matches(Regex("([0-9A-F]{4}:){15}[0-9A-F]{4}")))
    }
}
