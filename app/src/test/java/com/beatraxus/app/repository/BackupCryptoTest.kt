package com.beatraxus.app.repository

import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {

    private val plain = """{"dsp":{"eq_enabled":true},"drive":{"token":"secret-token-123"},"version":2}"""
        .toByteArray(Charsets.UTF_8)
    private val passphrase = "correct horse battery".toCharArray()

    @Test
    fun roundTripRestoresOriginalBytes() {
        val blob = BackupCrypto.encrypt(plain, passphrase)
        assertArrayEquals(plain, BackupCrypto.decrypt(blob, passphrase))
    }

    @Test
    fun encryptedFileIsDetectedAndDoesNotLeakPlaintext() {
        val blob = BackupCrypto.encrypt(plain, passphrase)
        assertTrue(BackupCrypto.isEncrypted(blob))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("secret-token-123"))
    }

    @Test
    fun everyEncryptionUsesFreshSaltAndIv() {
        assertFalse(BackupCrypto.encrypt(plain, passphrase).contentEquals(BackupCrypto.encrypt(plain, passphrase)))
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun wrongPassphraseIsRejected() {
        BackupCrypto.decrypt(BackupCrypto.encrypt(plain, passphrase), "not the passphrase".toCharArray())
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun tamperedCiphertextIsRejected() {
        val blob = BackupCrypto.encrypt(plain, passphrase)
        blob[blob.size - 5] = (blob[blob.size - 5].toInt() xor 1).toByte()
        BackupCrypto.decrypt(blob, passphrase)
    }

    @Test(expected = BackupCrypto.WrongPassphraseException::class)
    fun tamperedHeaderIsRejected() {
        val blob = BackupCrypto.encrypt(plain, passphrase)
        blob[9] = (blob[9].toInt() xor 1).toByte() // iteration count, still inside the allowed range
        BackupCrypto.decrypt(blob, passphrase)
    }

    @Test(expected = BackupCrypto.InvalidBackupException::class)
    fun absurdlyLowIterationCountIsRefused() {
        val blob = BackupCrypto.encrypt(plain, passphrase)
        blob[7] = 0; blob[8] = 0; blob[9] = 0; blob[10] = 1
        BackupCrypto.decrypt(blob, passphrase)
    }

    @Test(expected = BackupCrypto.InvalidBackupException::class)
    fun truncatedFileIsRefused() {
        BackupCrypto.decrypt(BackupCrypto.encrypt(plain, passphrase).copyOf(20), passphrase)
    }

    @Test(expected = BackupCrypto.InvalidBackupException::class)
    fun plainJsonIsNotAnEncryptedBackup() {
        BackupCrypto.decrypt(plain, passphrase)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPassphraseIsRefusedOnExport() {
        BackupCrypto.encrypt(plain, "short".toCharArray())
    }

    @Test
    fun unicodePassphraseWorks() {
        val unicode = "pässwörd-密码-🔐".toCharArray()
        assertArrayEquals(plain, BackupCrypto.decrypt(BackupCrypto.encrypt(plain, unicode), unicode))
    }
}
