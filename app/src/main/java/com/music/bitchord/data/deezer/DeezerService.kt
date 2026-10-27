package com.music.bitchord.data.deezer

import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.StreamRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val TAG = "DeezerService"
private const val GATEWAY_URL = "https://www.deezer.com/ajax/gw-light.php"
private const val MEDIA_URL = "https://media.deezer.com/v1/get_url"
private const val PUBLIC_API_URL = "https://api.deezer.com"
private const val CDN_COVER_BASE = "https://e-cdns-images.dzcdn.net/images/cover"
private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val SESSION_TTL_MS = 25L * 60L * 1000L

enum class DeezerTier(
    val label: String,
    val canStreamLossless: Boolean,
    val canStreamHq: Boolean,
) {
    HIFI("HiFi (FLAC)", canStreamLossless = true, canStreamHq = true),
    PREMIUM("Premium (320 kbps)", canStreamLossless = false, canStreamHq = true),
    FREE("Free (128 kbps)", canStreamLossless = false, canStreamHq = false),
}

enum class DeezerQuality(
    val code: String,
    val codec: String,
    val kbps: Int?,
    val sourceQualityLabel: String,
) {
    FLAC("FLAC", "flac", null, "LOSSLESS"),
    MP3_320("MP3_320", "mp3", 320, "HIGH"),
    MP3_128("MP3_128", "mp3", 128, "LOW"),
}

data class DeezerSession(
    val arl: String,
    val sid: String,
    val apiToken: String,
    val licenseToken: String,
    val userId: Long,
    val userName: String,
    val tier: DeezerTier,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val canStreamLossless: Boolean get() = tier.canStreamLossless
    val canStreamHq: Boolean get() = tier.canStreamHq

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        sid.isBlank() || licenseToken.isBlank() || (nowMs - createdAtMs) > SESSION_TTL_MS
}

sealed class DeezerAuthResult {
    data class Authenticated(val session: DeezerSession) : DeezerAuthResult()
    data class InvalidToken(val reason: String) : DeezerAuthResult()
    data class NetworkError(val reason: String) : DeezerAuthResult()
}

data class ResolvedDeezerStream(
    val url: String,
    val trackId: String,
    val quality: DeezerQuality,
    val encrypted: Boolean,
    val belowRequest: Boolean,
    val durationSec: Int? = null,
)

private fun JsonObject?.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this?.get(key) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
}

private fun JsonObject?.boolFlag(key: String): Boolean {
    val prim = this?.get(key) as? JsonPrimitive ?: return false
    return prim.booleanOrNull ?: (prim.intOrNull != null && prim.intOrNull != 0) || prim.content.equals("true", true)
}

/**
 * Deezer gateway, public catalog, and media delivery client.
 */
object DeezerService {

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client by lazy {
        HttpClient(OkHttp) { engine { preconfigured = Http.client } }
    }

    private val sessionMutex = Mutex()

    @Volatile
    private var activeSession: DeezerSession? = null

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    internal var gatewayTransport: (suspend (
        method: String,
        apiToken: String,
        sid: String?,
        arl: String,
        payload: JsonObject,
    ) -> Pair<String, String?>)? = null

    internal var mediaTransport: (suspend (licenseToken: String, payload: JsonObject) -> String)? = null

    fun currentSession(): DeezerSession? = activeSession

    fun invalidateSession(clearError: Boolean = true) {
        activeSession = null
        if (clearError) _authError.value = null
    }

    fun extractTrackId(songId: String): String = songId.trim().removePrefix("dz:").trim()

    internal fun extractSidFromSetCookies(setCookieHeaders: List<String>): String? =
        setCookieHeaders.firstNotNullOfOrNull { header ->
            Regex("""(?:^|;\s*)sid=([^;\s]+)""", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() && it != "deleted" }
        }

    private suspend fun executeGatewayCall(
        method: String,
        apiToken: String,
        sid: String?,
        arl: String,
        payload: JsonObject = buildJsonObject {},
    ): Pair<String, String?> {
        gatewayTransport?.let { return it(method, apiToken, sid, arl, payload) }
        val cookie = if (sid.isNullOrBlank()) "arl=${arl.trim()}" else "arl=${arl.trim()}; sid=${sid.trim()}"
        val response = client.post(GATEWAY_URL) {
            parameter("method", method)
            parameter("input", "3")
            parameter("api_version", "1.0")
            parameter("api_token", apiToken.ifBlank { "null" })
            if (!sid.isNullOrBlank()) parameter("cid", (100_000_000..999_999_999).random().toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        return response.bodyAsText() to extractSidFromSetCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
    }

    private suspend fun executeMediaCall(licenseToken: String, payload: JsonObject): String {
        mediaTransport?.let { return it(licenseToken, payload) }
        return client.post(MEDIA_URL) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }.bodyAsText()
    }

    fun parseUserDataSession(
        rawJson: String,
        arl: String,
        cookieSid: String? = null,
        fallbackApiToken: String = "",
        fallbackLicenseToken: String = "",
    ): DeezerAuthResult {
        val cleanArl = arl.trim()
        if (cleanArl.isEmpty()) return DeezerAuthResult.InvalidToken("ARL token is empty")
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrElse {
            return DeezerAuthResult.NetworkError("Malformed gateway response: ${it.message}")
        }

        val errorObj = root["error"] as? JsonObject
        if (!errorObj.isNullOrEmpty()) {
            val msg = errorObj.entries.joinToString(", ") { (k, v) -> "$k: ${(v as? JsonPrimitive)?.content ?: v}" }
            return DeezerAuthResult.InvalidToken(msg)
        }

        val results = root["results"] as? JsonObject
            ?: return DeezerAuthResult.NetworkError("Missing results object in gateway response")
        val userObj = (results["USER"] as? JsonObject) ?: results
        val userId = (userObj["USER_ID"] ?: results["USER_ID"])?.jsonPrimitive?.longOrNull ?: 0L
        if (userId <= 0L) return DeezerAuthResult.InvalidToken("ARL token is invalid or expired")

        val optionsObj = (userObj["OPTIONS"] ?: results["OPTIONS"]) as? JsonObject
        val canLossless = optionsObj.boolFlag("web_lossless") || optionsObj.boolFlag("mobile_lossless")
        val canHq = canLossless || optionsObj.boolFlag("web_hq") || optionsObj.boolFlag("mobile_hq")
        val tier = when {
            canLossless -> DeezerTier.HIFI
            canHq -> DeezerTier.PREMIUM
            else -> DeezerTier.FREE
        }

        return DeezerAuthResult.Authenticated(
            DeezerSession(
                arl = cleanArl,
                sid = results.str("SESSION_ID", "SESSION") ?: cookieSid.orEmpty(),
                apiToken = results.str("checkForm", "CHECKFORM") ?: fallbackApiToken,
                licenseToken = optionsObj.str("license_token") ?: results.str("PLAYER_TOKEN") ?: fallbackLicenseToken,
                userId = userId,
                userName = userObj.str("BLOG_NAME", "FIRSTNAME") ?: "Deezer #$userId",
                tier = tier,
            )
        )
    }

    private fun hasGatewayAuthError(rawJson: String): Boolean {
        val err = (runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.get("error") as? JsonObject)
            ?: return false
        return err.keys.any { k ->
            listOf("TOKEN", "AUTH", "SESSION", "GATEWAY", "VALID").any { k.contains(it, ignoreCase = true) }
        }
    }

    suspend fun validateArl(
        arl: String,
        persistOnSuccess: Boolean = true,
    ): DeezerAuthResult = withContext(Dispatchers.IO) {
        val cleanArl = arl.trim()
        if (cleanArl.isEmpty()) {
            return@withContext DeezerAuthResult.InvalidToken("ARL token cannot be empty").also {
                _authError.value = it.reason
            }
        }

        _validating.value = true
        try {
            val (rawUserData, cookieSid) = executeGatewayCall("deezer.getUserData", "null", null, cleanArl)
            var result = parseUserDataSession(rawUserData, cleanArl, cookieSid)

            if (result is DeezerAuthResult.Authenticated && (result.session.licenseToken.isBlank() || result.session.apiToken.isBlank())) {
                val base = result.session
                runCatching {
                    executeGatewayCall("deezer.page.getUserData", base.apiToken.ifBlank { "null" }, base.sid.ifBlank { cookieSid }, cleanArl)
                }.getOrNull()?.let { (pageRaw, pageCookieSid) ->
                    val pageRes = parseUserDataSession(pageRaw, cleanArl, pageCookieSid ?: base.sid, base.apiToken, base.licenseToken)
                    if (pageRes is DeezerAuthResult.Authenticated) result = pageRes
                }
            }

            if (result is DeezerAuthResult.Authenticated && result.session.sid.isBlank()) {
                val base = result.session
                runCatching {
                    executeGatewayCall("deezer.ping", base.apiToken.ifBlank { "null" }, null, cleanArl)
                }.getOrNull()?.let { (pingRaw, pingCookieSid) ->
                    val pingRes = parseUserDataSession(pingRaw, cleanArl, pingCookieSid, base.apiToken, base.licenseToken)
                    if (pingRes is DeezerAuthResult.Authenticated) {
                        result = DeezerAuthResult.Authenticated(
                            base.copy(
                                sid = pingRes.session.sid.ifBlank { base.sid },
                                apiToken = pingRes.session.apiToken.ifBlank { base.apiToken },
                            )
                        )
                    }
                }
            }

            when (val r = result) {
                is DeezerAuthResult.Authenticated -> {
                    activeSession = r.session
                    _authError.value = null
                    if (persistOnSuccess) {
                        runCatching {
                            AppSettings.setDeezerSessionInfo(
                                arl = r.session.arl,
                                sid = r.session.sid,
                                licenseToken = r.session.licenseToken,
                                userName = r.session.userName,
                                tier = r.session.tier.label,
                            )
                        }
                    }
                }
                is DeezerAuthResult.InvalidToken -> {
                    activeSession = null
                    _authError.value = r.reason
                    if (persistOnSuccess) runCatching { AppSettings.clearDeezerSessionTokens() }
                }
                is DeezerAuthResult.NetworkError -> _authError.value = r.reason
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate Deezer ARL", e)
            DeezerAuthResult.NetworkError(e.message ?: "Network error contacting Deezer").also {
                _authError.value = it.reason
            }
        } finally {
            _validating.value = false
        }
    }

    suspend fun ensureSession(
        arlOverride: String? = null,
        forceRefresh: Boolean = false,
    ): DeezerSession? = sessionMutex.withLock {
        val configuredArl = (arlOverride
            ?: runCatching { AppSettings.deezerArl.value.takeIf { it.isNotBlank() } }.getOrNull()
            ?: activeSession?.arl)?.trim().orEmpty()

        if (configuredArl.isEmpty()) {
            activeSession = null
            return@withLock null
        }

        val current = activeSession
        if (!forceRefresh && current != null && current.arl == configuredArl && !current.isExpired()) {
            return@withLock current
        }

        (validateArl(configuredArl, persistOnSuccess = true) as? DeezerAuthResult.Authenticated)?.session
    }

    private suspend fun callAuthenticatedGateway(
        method: String,
        payload: JsonObject,
        arlOverride: String? = null,
    ): JsonObject? {
        var session = ensureSession(arlOverride = arlOverride) ?: return null
        var (raw, _) = executeGatewayCall(method, session.apiToken, session.sid, session.arl, payload)
        if (hasGatewayAuthError(raw)) {
            session = ensureSession(arlOverride = session.arl, forceRefresh = true) ?: return null
            raw = executeGatewayCall(method, session.apiToken, session.sid, session.arl, payload).first
        }
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    // ─── Stream Resolution & Tier Fallback ───────────────────────────────────────

    fun orderedFormatsFor(tier: DeezerTier, request: StreamRequest): List<DeezerQuality> = buildList {
        if (request == StreamRequest.Lossless && tier.canStreamLossless) add(DeezerQuality.FLAC)
        if (tier.canStreamHq && (request !is StreamRequest.Capped || request.maxKbps >= 320)) add(DeezerQuality.MP3_320)
        add(DeezerQuality.MP3_128)
    }

    internal fun buildMediaRequestPayload(
        licenseToken: String,
        trackToken: String,
        formats: List<DeezerQuality>,
    ): JsonObject = buildJsonObject {
        put("license_token", licenseToken)
        put("media", buildJsonArray {
            add(buildJsonObject {
                put("type", "FULL")
                put("formats", buildJsonArray {
                    for (fmt in formats) {
                        add(buildJsonObject {
                            put("cipher", "BF_CBC_STRIPE")
                            put("format", fmt.code)
                        })
                    }
                })
            })
        })
        put("track_tokens", buildJsonArray { add(JsonPrimitive(trackToken)) })
    }

    fun parseMediaStreamResponse(
        rawJson: String,
        trackId: String,
        requestedFormats: List<DeezerQuality>,
        originalRequest: StreamRequest,
        durationSec: Int? = null,
    ): ResolvedDeezerStream? {
        val dataArr = (runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()?.get("data") as? JsonArray)
            ?: return null

        data class Candidate(val quality: DeezerQuality, val url: String, val encrypted: Boolean)
        val candidates = dataArr.flatMap { (it as? JsonObject)?.get("media") as? JsonArray ?: emptyList() }
            .mapNotNull { mediaElem ->
                val mediaObj = mediaElem as? JsonObject ?: return@mapNotNull null
                if (mediaObj.str("media_type").equals("PREVIEW", ignoreCase = true)) return@mapNotNull null
                val quality = DeezerQuality.entries.firstOrNull { it.code.equals(mediaObj.str("format"), true) }
                    ?: return@mapNotNull null
                val cipherType = (mediaObj["cipher"] as? JsonObject).str("type")
                val encrypted = cipherType == null || cipherType.equals("BF_CBC_STRIPE", ignoreCase = true)
                val url = (mediaObj["sources"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject).str("url") }
                    ?: return@mapNotNull null
                Candidate(quality, url, encrypted)
            }
        if (candidates.isEmpty()) return null

        val chosen = (requestedFormats + DeezerQuality.entries).distinct()
            .firstNotNullOfOrNull { desired -> candidates.firstOrNull { it.quality == desired } }
            ?: candidates.first()

        val belowRequest = when (originalRequest) {
            StreamRequest.Lossless -> chosen.quality != DeezerQuality.FLAC
            StreamRequest.Best -> chosen.quality == DeezerQuality.MP3_128
            is StreamRequest.Capped -> (chosen.quality.kbps ?: Int.MAX_VALUE) < if (originalRequest.maxKbps >= 320) 320 else 128
        }

        return ResolvedDeezerStream(
            url = chosen.url,
            trackId = extractTrackId(trackId),
            quality = chosen.quality,
            encrypted = chosen.encrypted,
            belowRequest = belowRequest,
            durationSec = durationSec,
        )
    }

    suspend fun getStreamUrl(
        songId: String,
        request: StreamRequest = StreamRequest.Lossless,
        arlOverride: String? = null,
    ): ResolvedDeezerStream? = withContext(Dispatchers.IO) {
        try {
            val cleanTrackId = extractTrackId(songId).takeIf { it.isNotEmpty() } ?: return@withContext null
            var session = ensureSession(arlOverride = arlOverride) ?: return@withContext null

            val results = callAuthenticatedGateway(
                method = "song.getData",
                payload = buildJsonObject { put("SNG_ID", cleanTrackId) },
                arlOverride = session.arl,
            )?.get("results") as? JsonObject ?: return@withContext null

            val songObj = when {
                results["TRACK_TOKEN"] != null -> results
                results["DATA"] is JsonObject -> results["DATA"] as JsonObject
                results["data"] is JsonArray -> (results["data"] as JsonArray).firstOrNull() as? JsonObject
                else -> null
            } ?: return@withContext null

            val effective = (songObj["FALLBACK"] as? JsonObject)?.takeIf { !it.str("TRACK_TOKEN").isNullOrEmpty() } ?: songObj
            val trackToken = effective.str("TRACK_TOKEN") ?: return@withContext null
            val resolvedTrackId = effective.str("SNG_ID") ?: cleanTrackId
            val durationSec = (effective["DURATION"] ?: songObj["DURATION"])?.jsonPrimitive?.intOrNull

            suspend fun tryFormats(lic: String, fmts: List<DeezerQuality>): ResolvedDeezerStream? = runCatching {
                val raw = executeMediaCall(lic, buildMediaRequestPayload(lic, trackToken, fmts))
                parseMediaStreamResponse(raw, resolvedTrackId, fmts, request, durationSec)
            }.getOrNull()

            session = activeSession ?: session
            tryFormats(session.licenseToken, orderedFormatsFor(session.tier, request))?.let { return@withContext it }

            val refreshed = ensureSession(arlOverride = session.arl, forceRefresh = true)
            if (refreshed != null && refreshed.licenseToken != session.licenseToken) {
                session = refreshed
                tryFormats(session.licenseToken, orderedFormatsFor(session.tier, request))?.let { return@withContext it }
            }

            orderedFormatsFor(session.tier, request).firstNotNullOfOrNull { fmt ->
                tryFormats(session.licenseToken, listOf(fmt))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Deezer stream for $songId", e)
            null
        }
    }

    // ─── Catalog & Metadata Mapping ──────────────────────────────────────────────

    fun mapTrackToSong(item: JsonObject, sourceQuality: String = "LOSSLESS"): Song? {
        val rawId = item.str("SNG_ID", "id") ?: return null
        val baseTitle = item.str("SNG_TITLE", "title") ?: return null
        val version = item.str("VERSION").orEmpty()
        val title = if (version.isNotEmpty() && !baseTitle.contains(version, true)) "$baseTitle $version" else baseTitle

        val artistsList = ((item["ARTISTS"] ?: item["contributors"]) as? JsonArray)?.mapNotNull {
            (it as? JsonObject).str("ART_NAME", "name")
        }
        val artistObj = item["artist"] as? JsonObject
        val artistName = artistsList?.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: item.str("ART_NAME")
            ?: artistObj.str("name")
            ?: "Unknown Artist"

        val albumObj = item["album"] as? JsonObject
        val coverUrl = albumObj.str("cover_xl", "cover_big", "cover_medium")
            ?: item.str("ALB_PICTURE")?.let { if (it.startsWith("http")) it else "$CDN_COVER_BASE/$it/500x500-000000-80-0-0.jpg" }

        val durationSec = (item["DURATION"] ?: item["duration"])?.jsonPrimitive?.longOrNull ?: 0L
        val exp = (item["EXPLICIT_LYRICS"] ?: item["explicit_lyrics"]) as? JsonPrimitive

        return Song(
            videoId = if (rawId.startsWith("dz:")) rawId else "dz:$rawId",
            title = title,
            artist = artistName,
            thumbnailUrl = coverUrl,
            durationText = if (durationSec > 0L) "%d:%02d".format(durationSec / 60L, durationSec % 60L) else null,
            artistId = item.str("ART_ID") ?: artistObj.str("id"),
            albumId = item.str("ALB_ID") ?: albumObj.str("id"),
            albumName = item.str("ALB_TITLE") ?: albumObj.str("title"),
            sourceQuality = sourceQuality,
            isExplicit = exp != null && (exp.booleanOrNull == true || exp.content == "1" || exp.content == "4"),
        )
    }

    fun parseTrackSearchResponse(rawJson: String): List<Song> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonObject
        val dataArray = (root["data"] as? JsonArray)
            ?: (results?.get("data") as? JsonArray)
            ?: ((results?.get("TRACK") as? JsonObject)?.get("data") as? JsonArray)
            ?: return emptyList()
        return dataArray.mapNotNull { (it as? JsonObject)?.let(::mapTrackToSong) }
            .sortedByDescending { it.isExplicit == true }
    }

    suspend fun searchTracks(query: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val gwSongs = callAuthenticatedGateway(
                method = "deezer.pageSearch",
                payload = buildJsonObject {
                    put("query", query)
                    put("start", 0)
                    put("nb", limit)
                },
            )?.let { parseTrackSearchResponse(it.toString()) }.orEmpty()
            if (gwSongs.isNotEmpty()) return@withContext gwSongs.take(limit)

            val raw = client.get("$PUBLIC_API_URL/search/track") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("q", query)
                parameter("limit", limit)
            }.bodyAsText()
            parseTrackSearchResponse(raw).take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer track search failed for '$query'", e)
            emptyList()
        }
    }
}
