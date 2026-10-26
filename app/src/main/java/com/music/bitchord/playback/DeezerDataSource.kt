package com.music.bitchord.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.deezer.DeezerCipher

/**
 * Media3 [DataSource] that transparently decrypts Deezer `BF_CBC_STRIPE` streams on the fly.
 *
 * Activated only when [DataSpec.httpRequestHeaders] contains [DeezerCipher.HEADER_TRACK_ID]
 * (or the URI contains query parameter `dz_tid`). All other streams pass through untouched.
 *
 * Handles arbitrary byte-range seeks (`dataSpec.position`) by aligning the upstream request
 * down to the nearest 2048-byte chunk boundary, assembling full 2048-byte chunks across
 * partial network reads, decrypting every 3rd chunk (`chunkIndex % 3 == 0`), and discarding
 * the pre-seek prefix bytes before delivering plaintext to the caller.
 */
@OptIn(UnstableApi::class)
class DeezerDataSource(
    private val upstream: DataSource,
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            DeezerDataSource(upstreamFactory.createDataSource())
    }

    private var passthrough: Boolean = true
    private var key: ByteArray? = null
    private val chunkBuffer = ByteArray(DeezerCipher.CHUNK_SIZE)
    private var chunkIndex: Long = 0L
    private var pendingSkip: Int = 0
    private var chunkPos: Int = 0
    private var chunkLimit: Int = 0
    private var upstreamEof: Boolean = false
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val headerTrackId = dataSpec.httpRequestHeaders.entries
            .firstOrNull { it.key.equals(DeezerCipher.HEADER_TRACK_ID, ignoreCase = true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val trackId = headerTrackId ?: runCatching {
            dataSpec.uri.getQueryParameter("dz_tid")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        if (trackId == null) {
            passthrough = true
            key = null
            return upstream.open(dataSpec)
        }

        passthrough = false
        key = DeezerCipher.deriveKey(trackId)

        val requestedStart = dataSpec.position.coerceAtLeast(0L)
        val alignedStart = (requestedStart / DeezerCipher.CHUNK_SIZE) * DeezerCipher.CHUNK_SIZE
        val skipInFirstChunk = (requestedStart - alignedStart).toInt()

        chunkIndex = alignedStart / DeezerCipher.CHUNK_SIZE
        pendingSkip = skipInFirstChunk
        chunkPos = 0
        chunkLimit = 0
        upstreamEof = false

        val cleanHeaders = dataSpec.httpRequestHeaders.filterKeys {
            !it.equals(DeezerCipher.HEADER_TRACK_ID, ignoreCase = true)
        }
        val upstreamSpec = dataSpec.buildUpon()
            .setPosition(alignedStart)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(cleanHeaders)
            .build()

        val upstreamLen = upstream.open(upstreamSpec)
        val availableFromRequestedStart = if (upstreamLen != C.LENGTH_UNSET.toLong()) {
            (upstreamLen - skipInFirstChunk).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() && availableFromRequestedStart != C.LENGTH_UNSET.toLong() ->
                minOf(dataSpec.length, availableFromRequestedStart)
            dataSpec.length != C.LENGTH_UNSET.toLong() ->
                dataSpec.length
            else ->
                availableFromRequestedStart
        }

        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (passthrough) {
            return upstream.read(buffer, offset, length)
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }

        if (chunkPos >= chunkLimit && !fillNextChunk()) {
            return C.RESULT_END_OF_INPUT
        }

        val maxDeliver = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }
        val availableInChunk = chunkLimit - chunkPos
        val toCopy = minOf(maxDeliver, availableInChunk)
        System.arraycopy(chunkBuffer, chunkPos, buffer, offset, toCopy)
        chunkPos += toCopy
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= toCopy
        }
        return toCopy
    }

    private fun fillNextChunk(): Boolean {
        val activeKey = key ?: return false
        while (!upstreamEof) {
            var totalRead = 0
            while (totalRead < DeezerCipher.CHUNK_SIZE) {
                val n = upstream.read(
                    chunkBuffer,
                    totalRead,
                    DeezerCipher.CHUNK_SIZE - totalRead,
                )
                if (n == C.RESULT_END_OF_INPUT) {
                    upstreamEof = true
                    break
                }
                if (n > 0) {
                    totalRead += n
                }
            }
            if (totalRead == 0) {
                return false
            }
            if (DeezerCipher.isEncryptedChunk(chunkIndex, totalRead)) {
                DeezerCipher.decryptChunkInPlace(activeKey, chunkBuffer, 0)
            }
            chunkIndex++
            val skip = pendingSkip.coerceAtMost(totalRead)
            pendingSkip -= skip
            chunkPos = skip
            chunkLimit = totalRead
            if (chunkPos < chunkLimit) {
                return true
            }
        }
        return false
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        key = null
        chunkPos = 0
        chunkLimit = 0
        upstreamEof = false
        bytesRemaining = C.LENGTH_UNSET.toLong()
        upstream.close()
    }
}
