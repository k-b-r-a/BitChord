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
            .ifEmpty { DeezerService.currentSession()?.arl.orEmpty() }
        if (arl.isEmpty()) return SourceHealth.Rejected("ARL token not configured")
        return when (val result = DeezerService.validateArl(arl)) {
            is DeezerAuthResult.Authenticated ->
                SourceHealth.Ok("${result.session.userName} · ${result.session.tier.label}")
            is DeezerAuthResult.InvalidToken -> SourceHealth.Rejected(result.reason)
            is DeezerAuthResult.NetworkError -> SourceHealth.Unreachable(result.reason)
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        TrackLog.d(TAG, "▶ Deezer searchTracks() query=\"$query\" limit=$limit request=$request")
        val tier = DeezerService.currentSession()?.tier
            ?: DeezerTier.entries.firstOrNull { AppSettings.deezerTier.value.contains(it.name, ignoreCase = true) }
            ?: DeezerTier.HIFI
        val qualityLabel = DeezerService.orderedFormatsFor(tier, request ?: StreamRequest.Lossless)
            .first().sourceQualityLabel
        return DeezerService.searchTracks(query, limit).take(limit).map { song ->
            song.copy(
                videoId = SourceRegistry.trackKey(config.id, DeezerService.extractTrackId(song.videoId)),
                sourceQuality = qualityLabel,
            )
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        TrackLog.d(TAG, "▶ Deezer getStreamUrl() trackId=$trackId request=$request")
        val res = DeezerService.getStreamUrl(trackId, request) ?: return null.also {
            TrackLog.w(TAG, "  ✗ Deezer had no stream URL for $trackId")
        }
        TrackLog.d(TAG, "  ✓ Deezer ${res.quality.code} (${res.quality.kbps ?: "lossless"}kbps) encrypted=${res.encrypted}")
        return SourceStream(
            url = res.url,
            format = StreamFormat(codec = res.quality.codec, kbps = res.quality.kbps),
            headers = if (res.encrypted) mapOf(DeezerCipher.HEADER_TRACK_ID to res.trackId) else emptyMap(),
            belowRequest = res.belowRequest,
            durationSec = res.durationSec,
        )
    }
}
