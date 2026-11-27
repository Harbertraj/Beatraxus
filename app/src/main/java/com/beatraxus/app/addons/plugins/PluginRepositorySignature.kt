package com.beatraxus.app.addons.plugins

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies detached RSA/SHA-256 signatures for declarative plugin repository payloads.
 *
 * The public key is deliberately supplied by the caller as a locally pinned key. A key shipped
 * beside a signature in the same downloaded document is NOT a trust anchor.
 */
object PluginRepositorySignature {
    private const val ALGORITHM = "SHA256withRSA"

    /**
     * [payload] must be the exact UTF-8 bytes covered by the publisher's signature.
     * Both keys are X.509 SubjectPublicKeyInfo DER encoded and Base64 encoded.
     */
    fun verify(
        payload: ByteArray,
        signatureBase64: String,
        publicKeyBase64: String,
        pinnedPublicKeyBase64: String
    ): Boolean = runCatching {
        require(payload.isNotEmpty()) { "Signed payload is empty" }
        val suppliedKey = Base64.getDecoder().decode(publicKeyBase64)
        val pinnedKey = Base64.getDecoder().decode(pinnedPublicKeyBase64)
        require(MessageDigest.isEqual(suppliedKey, pinnedKey)) { "Repository signing key does not match the pinned key" }

        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(pinnedKey))
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(payload)
        verifier.verify(Base64.getDecoder().decode(signatureBase64))
    }.getOrDefault(false)

    fun fingerprint(publicKeyBase64: String): String = MessageDigest.getInstance("SHA-256")
        .digest(Base64.getDecoder().decode(publicKeyBase64))
        .joinToString("") { "%02X".format(it) }
        .chunked(4)
        .joinToString(":")
}
