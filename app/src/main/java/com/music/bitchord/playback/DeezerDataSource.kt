package com.music.bitchord.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.data.deezer.DeezerCipher
import java.io.InputStream

/**
 * Media3 [DataSource] that transparently decrypts Deezer `BF_CBC_STRIPE` streams on the fly
 * by delegating chunk assembly and 2048-byte stride decryption to [DeezerCipher.DecryptingInputStream].
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

    private var decryptingStream: DeezerCipher.DecryptingInputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val trackId = DeezerCipher.extractTrackId(dataSpec.httpRequestHeaders)
            ?: runCatching { dataSpec.uri.getQueryParameter("dz_tid")?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

        if (trackId == null) {
            decryptingStream = null
            return upstream.open(dataSpec)
        }

        val requestedStart = dataSpec.position.coerceAtLeast(0L)
        val alignedStart = (requestedStart / DeezerCipher.CHUNK_SIZE) * DeezerCipher.CHUNK_SIZE
        val skipInFirstChunk = requestedStart - alignedStart

        val upstreamSpec = dataSpec.buildUpon()
            .setPosition(alignedStart)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(DeezerCipher.stripTrackHeader(dataSpec.httpRequestHeaders))
            .build()

        val upstreamLen = upstream.open(upstreamSpec)
        val available = if (upstreamLen != C.LENGTH_UNSET.toLong()) {
            (upstreamLen - skipInFirstChunk).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() && available != C.LENGTH_UNSET.toLong() -> minOf(dataSpec.length, available)
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> available
        }

        val upstreamInput = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                upstream.read(b, off, len).let { if (it == C.RESULT_END_OF_INPUT) -1 else it }
        }
        decryptingStream = DeezerCipher.DecryptingInputStream(upstreamInput, trackId, requestedStart)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = decryptingStream ?: return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val maxDeliver = if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), bytesRemaining).toInt()
        } else {
            length
        }
        val read = stream.read(buffer, offset, maxDeliver)
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        decryptingStream = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        upstream.close()
    }
}
