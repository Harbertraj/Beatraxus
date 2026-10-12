package com.beatraxus.app.repository

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for exported settings backups.
 *
 * Why a passphrase and not an Android Keystore key: a Keystore key never leaves the device and is
 * wiped on uninstall, so a backup encrypted with it could not be restored on a new phone, which is
 * the whole point of a backup. A passphrase travels with the user instead.
 *
 * File layout (all integers big-endian):
 *
 *   MAGIC "BTXENC1" (7) | PBKDF2 iterations (4) | salt (16) | GCM IV (12) | ciphertext + 16-byte tag
 *
 * Key derivation is PBKDF2-HMAC-SHA256 (256-bit key) and the cipher is AES-256-GCM. Everything in
 * front of the ciphertext is passed to GCM as associated data, so tampering with the header (for
 * example lowering the iteration count) makes decryption fail instead of silently weakening it.
 */
object BackupCrypto {

    /** The passphrase did not decrypt the file, or the file was modified after it was written. */
    class WrongPassphraseException : Exception("Wrong passphrase, or the backup file is corrupted")

    /** The file is not a Beatraxus encrypted backup (or is truncated). */
    class InvalidBackupException(message: String) : Exception(message)

    const val MIN_PASSPHRASE_LENGTH = 8

    private val MAGIC = "BTXENC1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS_LEN = 4
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val HEADER_LEN = 7 + ITERATIONS_LEN + SALT_LEN + IV_LEN
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val KEY_BITS = 256

    /** OWASP's current recommendation for PBKDF2-HMAC-SHA256. Stored in the header, so it can be raised later. */
    private const val DEFAULT_ITERATIONS = 600_000

    /** Bounds applied when reading the iteration count from an untrusted file. */
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000

    private val secureRandom = SecureRandom()

    /** True when [data] starts with the encrypted-backup magic. Only the first bytes are needed. */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return true
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"
        }
        val salt = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }

        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC)
            .putInt(DEFAULT_ITERATIONS)
            .put(salt)
            .put(iv)
            .array()

        val keyBytes = deriveKey(passphrase, salt, DEFAULT_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plain)
            return header + encrypted
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * @throws InvalidBackupException if [blob] is not a Beatraxus encrypted backup
     * @throws WrongPassphraseException if the passphrase is wrong or the file was altered
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (!isEncrypted(blob)) throw InvalidBackupException("Not a Beatraxus encrypted backup")
        if (blob.size < HEADER_LEN + GCM_TAG_BYTES) throw InvalidBackupException("Backup file is truncated")

        val buffer = ByteBuffer.wrap(blob)
        buffer.position(MAGIC.size)
        val iterations = buffer.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw InvalidBackupException("Unsupported backup format")
        val salt = ByteArray(SALT_LEN).also { buffer.get(it) }
        val iv = ByteArray(IV_LEN).also { buffer.get(it) }
        val header = blob.copyOfRange(0, HEADER_LEN)

        val keyBytes = deriveKey(passphrase, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            return cipher.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: BadPaddingException) {
            // GCM reports a bad tag (wrong key or modified data) as AEADBadTagException, a BadPaddingException.
            throw WrongPassphraseException()
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
