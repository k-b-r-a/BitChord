package com.music.bitchord.data.deezer

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure JVM implementation of Deezer's `BF_CBC_STRIPE` track encryption/decryption.
 *
 * Every 3rd full 2048-byte chunk (`chunkIndex % 3 == 0`) is encrypted with
 * `Blowfish/CBC/NoPadding` using a static 8-byte IV (`0..7`) and a 16-byte key
 * derived from the MD5 hex digest of `SNG_ID` XORed with Deezer's secret.
 */
object DeezerCipher {
    const val CHUNK_SIZE = 2048
    const val CHUNK_STRIDE = 3
    const val HEADER_TRACK_ID = "X-Deezer-Track-Id"

    private const val SECRET = "g4el58wc0zvf9na1"
    private val IV_SPEC = IvParameterSpec(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))

    fun deriveKey(trackId: String): ByteArray {
        val cleanId = trackId.trim()
        require(cleanId.isNotEmpty()) { "Deezer trackId must not be blank" }
        val md5Hex = MessageDigest.getInstance("MD5")
            .digest(cleanId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ByteArray(16) { i ->
            (md5Hex[i].code xor md5Hex[i + 16].code xor SECRET[i].code).toByte()
        }
    }

    fun extractTrackId(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals(HEADER_TRACK_ID, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }

    fun stripTrackHeader(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals(HEADER_TRACK_ID, ignoreCase = true) }

    fun isEncryptedChunk(chunkIndex: Long, chunkLength: Int): Boolean =
        chunkIndex >= 0L && chunkIndex % CHUNK_STRIDE == 0L && chunkLength == CHUNK_SIZE

    private fun transformChunk(
        mode: Int,
        key: SecretKeySpec,
        buffer: ByteArray,
        offset: Int,
        cipher: Cipher = Cipher.getInstance("Blowfish/CBC/NoPadding"),
    ) {
        cipher.init(mode, key, IV_SPEC)
        cipher.doFinal(buffer, offset, CHUNK_SIZE, buffer, offset)
    }

    fun decryptChunkInPlace(key: ByteArray, buffer: ByteArray, offset: Int = 0) =
        transformChunk(Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), buffer, offset)

    fun encryptChunkInPlace(key: ByteArray, buffer: ByteArray, offset: Int = 0) =
        transformChunk(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"), buffer, offset)

    fun decryptStream(data: ByteArray, trackId: String, startOffset: Long = 0L): ByteArray =
        DecryptingInputStream(data.inputStream(), trackId, startOffset).use { it.readBytes() }

    /**
     * Streaming [InputStream] that assembles 2048-byte chunks from [upstream], decrypts every
     * 3rd full chunk in place, and skips any sub-chunk prefix for [startOffset] seeks.
     */
    class DecryptingInputStream(
        private val upstream: InputStream,
        trackId: String,
        startOffset: Long = 0L,
    ) : InputStream() {
        private val secretKey = SecretKeySpec(deriveKey(trackId), "Blowfish")
        private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
        private val chunkBuffer = ByteArray(CHUNK_SIZE)
        private var chunkIndex = startOffset.coerceAtLeast(0L) / CHUNK_SIZE
        private var pendingSkip = (startOffset.coerceAtLeast(0L) % CHUNK_SIZE).toInt()
        private var chunkPos = 0
        private var chunkLimit = 0
        private var eof = false

        private fun fillNextChunk(): Boolean {
            while (!eof) {
                var total = 0
                while (total < CHUNK_SIZE) {
                    val n = upstream.read(chunkBuffer, total, CHUNK_SIZE - total)
                    if (n < 0) { eof = true; break }
                    total += n
                }
                if (total == 0) return false
                if (isEncryptedChunk(chunkIndex++, total)) {
                    transformChunk(Cipher.DECRYPT_MODE, secretKey, chunkBuffer, 0, cipher)
                }
                val skip = pendingSkip.coerceAtMost(total)
                pendingSkip -= skip
                chunkPos = skip
                chunkLimit = total
                if (chunkPos < chunkLimit) return true
            }
            return false
        }

        override fun read(): Int =
            if (chunkPos >= chunkLimit && !fillNextChunk()) -1 else chunkBuffer[chunkPos++].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (chunkPos >= chunkLimit && !fillNextChunk()) return -1
            val toCopy = minOf(len, chunkLimit - chunkPos)
            System.arraycopy(chunkBuffer, chunkPos, b, off, toCopy)
            chunkPos += toCopy
            return toCopy
        }

        override fun close() = upstream.close()
    }
}
