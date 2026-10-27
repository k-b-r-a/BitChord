package com.music.bitchord.data.deezer

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JVM implementation of Deezer's `BF_CBC_STRIPE` track encryption/decryption.
 *
 * Deezer encrypts every 3rd full 2048-byte chunk (`chunkIndex % 3 == 0`) using
 * `Blowfish/CBC/NoPadding` with a static 8-byte IV (`0..7`) and a 16-byte track-specific
 * key derived from the MD5 hex digest of the numeric `SNG_ID` XORed with Deezer's secret.
 */
object DeezerCipher {
    const val CHUNK_SIZE = 2048
    const val CHUNK_STRIDE = 3
    const val HEADER_TRACK_ID = "X-Deezer-Track-Id"

    private const val SECRET = "g4el58wc0zvf9na1"
    private val IV = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    val IV_SPEC = IvParameterSpec(IV)

    /**
     * Derives the 16-byte Blowfish key for a given Deezer `trackId` (`SNG_ID`).
     */
    fun deriveKey(trackId: String): ByteArray {
        val cleanId = trackId.trim()
        require(cleanId.isNotEmpty()) { "Deezer trackId must not be blank" }
        val md5Bytes = MessageDigest.getInstance("MD5").digest(cleanId.toByteArray(Charsets.UTF_8))
        val md5Hex = buildString(32) {
            for (b in md5Bytes) {
                append(Character.forDigit((b.toInt() ushr 4) and 0x0F, 16))
                append(Character.forDigit(b.toInt() and 0x0F, 16))
            }
        }
        return ByteArray(16) { i ->
            (md5Hex[i].code xor md5Hex[i + 16].code xor SECRET[i].code).toByte()
        }
    }

    fun extractTrackId(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals(HEADER_TRACK_ID, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    fun stripTrackHeader(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals(HEADER_TRACK_ID, ignoreCase = true) }

    /**
     * Only full 2048-byte chunks at indices `0, 3, 6, 9, ...` are encrypted.
     * Trailing EOF chunks shorter than 2048 bytes are left unencrypted by Deezer.
     */
    fun isEncryptedChunk(chunkIndex: Long, chunkLength: Int): Boolean =
        chunkIndex >= 0L && chunkIndex % CHUNK_STRIDE == 0L && chunkLength == CHUNK_SIZE

    fun createCipher(): Cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")

    fun decryptChunkInPlace(
        cipher: Cipher,
        secretKey: SecretKeySpec,
        buffer: ByteArray,
        offset: Int = 0,
    ) {
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IV_SPEC)
        cipher.doFinal(buffer, offset, CHUNK_SIZE, buffer, offset)
    }

    fun decryptChunkInPlace(key: ByteArray, buffer: ByteArray, offset: Int = 0) {
        decryptChunkInPlace(createCipher(), SecretKeySpec(key, "Blowfish"), buffer, offset)
    }

    fun encryptChunkInPlace(
        cipher: Cipher,
        secretKey: SecretKeySpec,
        buffer: ByteArray,
        offset: Int = 0,
    ) {
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IV_SPEC)
        cipher.doFinal(buffer, offset, CHUNK_SIZE, buffer, offset)
    }

    fun encryptChunkInPlace(key: ByteArray, buffer: ByteArray, offset: Int = 0) {
        encryptChunkInPlace(createCipher(), SecretKeySpec(key, "Blowfish"), buffer, offset)
    }

    /**
     * Decrypts an encrypted byte array starting at [startOffset] (where [upstream] bytes start
     * at the chunk boundary `(startOffset / 2048) * 2048`).
     */
    fun decryptStream(data: ByteArray, trackId: String, startOffset: Long = 0L): ByteArray =
        DecryptingInputStream(data.inputStream(), trackId, startOffset).use { it.readBytes() }

    /**
     * Streaming [InputStream] wrapper that reads 2048-byte chunks from [upstream],
     * decrypts every 3rd full chunk in place, and supports an arbitrary [startOffset]
     * (assuming [upstream] starts at the aligned chunk boundary `(startOffset / 2048) * 2048`).
     */
    class DecryptingInputStream(
        private val upstream: InputStream,
        trackId: String,
        startOffset: Long = 0L,
    ) : InputStream() {
        private val secretKey = SecretKeySpec(deriveKey(trackId), "Blowfish")
        private val cipher = createCipher()
        private val chunkBuffer = ByteArray(CHUNK_SIZE)
        private var chunkIndex: Long = (startOffset.coerceAtLeast(0L)) / CHUNK_SIZE
        private var pendingSkip: Int = ((startOffset.coerceAtLeast(0L)) % CHUNK_SIZE).toInt()
        private var chunkPos: Int = 0
        private var chunkLimit: Int = 0
        private var eof: Boolean = false

        private fun fillNextChunk(): Boolean {
            while (!eof) {
                var total = 0
                while (total < CHUNK_SIZE) {
                    val n = upstream.read(chunkBuffer, total, CHUNK_SIZE - total)
                    if (n == -1) { eof = true; break }
                    if (n > 0) total += n
                }
                if (total == 0) return false
                if (isEncryptedChunk(chunkIndex++, total)) {
                    decryptChunkInPlace(cipher, secretKey, chunkBuffer, 0)
                }
                val skip = pendingSkip.coerceAtMost(total)
                pendingSkip -= skip
                chunkPos = skip
                chunkLimit = total
                if (chunkPos < chunkLimit) return true
            }
            return false
        }

        override fun read(): Int {
            if (chunkPos >= chunkLimit && !fillNextChunk()) return -1
            return chunkBuffer[chunkPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) {
                throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
            }
            if (len == 0) return 0
            if (chunkPos >= chunkLimit && !fillNextChunk()) return -1
            val available = chunkLimit - chunkPos
            val toCopy = minOf(len, available)
            System.arraycopy(chunkBuffer, chunkPos, b, off, toCopy)
            chunkPos += toCopy
            return toCopy
        }

        override fun close() {
            upstream.close()
        }
    }
}
