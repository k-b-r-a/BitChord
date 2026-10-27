package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.deezer.DeezerAuthResult
import com.music.bitchord.data.deezer.DeezerCipher
import com.music.bitchord.data.deezer.DeezerService
import com.music.bitchord.data.deezer.DeezerTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings

private const val TAG = "BitChord"

/**
 * Built-in source backed by [DeezerService].
 *
 * Uses the user's configured Deezer `arl` token to resolve tier-appropriate streams
 * (`FLAC`, `MP3_320`, `MP3_128`). Encrypted `BF_CBC_STRIPE` streams are tagged with
 * [DeezerCipher.HEADER_TRACK_ID] so [com.music.bitchord.playback.DeezerDataSource] and
 * [com.music.bitchord.download.Downloader] decrypt every 3rd 2048-byte chunk on the fly.
 *
 * Telemetry honesty: [stream] reports only the upstream **Source Metadata**
 * (`StreamFormat(codec = "flac")` or `StreamFormat(codec = "mp3", kbps = 320/128)`)
 * delivered by Deezer's media API without claiming downstream decoder, DSP, or hardware states.
 */
class DeezerSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.DEEZER
    override val displayName: String get() = config.label.ifBlank { SourceKind.DEEZER.label }

    override suspend fun health(): SourceHealth {
        val arl = runCatching { AppSettings.deezerArl.value.trim() }.getOrDefault("")
        if (arl.isEmpty() && DeezerService.currentSession() == null) {
            return SourceHealth.Rejected("ARL token not configured")
        }
        return when (val result = DeezerService.validateArl(arl.ifEmpty { DeezerService.currentSession()?.arl.orEmpty() })) {
            is DeezerAuthResult.Authenticated ->
                SourceHealth.Ok("${result.session.userName} · ${result.session.tier.label}")
            is DeezerAuthResult.InvalidToken ->
                SourceHealth.Rejected(result.reason)
            is DeezerAuthResult.NetworkError ->
                SourceHealth.Unreachable(result.reason)
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ Deezer searchTracks() query=\"$query\" limit=$limit request=$request")
        val results = DeezerService.searchTracks(query, limit)
        val advertisedQuality = advertisedQualityFor(request)
        return results.take(limit).map { song ->
            val rawId = DeezerService.extractTrackId(song.videoId)
            song.copy(
                videoId = SourceRegistry.trackKey(config.id, rawId),
                sourceQuality = advertisedQuality,
            )
        }
    }

    private fun advertisedQualityFor(request: StreamRequest?): String {
        val tier = DeezerService.currentSession()?.tier ?: when {
            AppSettings.deezerTier.value.contains("Premium", true) -> DeezerTier.PREMIUM
            AppSettings.deezerTier.value.contains("Free", true) -> DeezerTier.FREE
            else -> DeezerTier.HIFI
        }
        return when (request) {
            is StreamRequest.Capped -> if (request.maxKbps >= 320 && tier.canStreamHq) "HIGH" else "LOW"
            StreamRequest.Best -> if (tier.canStreamHq) "HIGH" else "LOW"
            StreamRequest.Lossless, null -> when (tier) {
                DeezerTier.HIFI -> "LOSSLESS"
                DeezerTier.PREMIUM -> "HIGH"
                DeezerTier.FREE -> "LOW"
            }
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ Deezer getStreamUrl() trackId=$trackId request=$request")
        val res = DeezerService.getStreamUrl(trackId, request) ?: return null.also {
            TrackLog.w(TAG, "  ✗ Deezer had no stream URL for $trackId")
        }
        val headers = if (res.encrypted) mapOf(DeezerCipher.HEADER_TRACK_ID to res.trackId) else emptyMap()
        TrackLog.d(TAG, "  ✓ Deezer ${res.quality.code} (${res.quality.kbps ?: "lossless"}kbps) encrypted=${res.encrypted}")
        return SourceStream(
            url = res.url,
            format = StreamFormat(codec = res.quality.codec, kbps = res.quality.kbps),
            headers = headers,
            belowRequest = res.belowRequest,
            durationSec = res.durationSec,
        )
    }
}
