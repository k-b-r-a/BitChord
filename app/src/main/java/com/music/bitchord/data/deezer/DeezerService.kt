package com.music.bitchord.data.deezer

import android.util.Log
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.StreamRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
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
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "DeezerService"
private const val GATEWAY_URL = "https://www.deezer.com/ajax/gw-light.php"
private const val MEDIA_URL = "https://media.deezer.com/v1/get_url"
private const val PUBLIC_API_URL = "https://api.deezer.com"
private const val CDN_COVER_BASE = "https://e-cdns-images.dzcdn.net/images/cover"
private const val CDN_ARTIST_BASE = "https://e-cdns-images.dzcdn.net/images/artist"
private const val CDN_PLAYLIST_BASE = "https://e-cdns-images.dzcdn.net/images/playlist"
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
) {
    FLAC("FLAC", "flac", null),
    MP3_320("MP3_320", "mp3", 320),
    MP3_128("MP3_128", "mp3", 128),
}

data class DeezerSession(
    val arl: String,
    val sid: String,
    val apiToken: String,
    val licenseToken: String,
    val userId: Long,
    val userName: String,
    val tier: DeezerTier,
    val canStreamLossless: Boolean = tier.canStreamLossless,
    val canStreamHq: Boolean = tier.canStreamHq,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
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

/**
 * Deezer gateway, public catalog, and media delivery client.
 *
 * Handles ARL authentication (`deezer.getUserData` / `deezer.page.getUserData` / `deezer.ping`),
 * session lifecycle (`sid` + `license_token` caching and transparent refresh), catalog queries
 * (tracks, albums, artists, playlists), and tier-aware stream URL resolution (`FLAC`, `MP3_320`, `MP3_128`).
 */
object DeezerService {

    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            engine {
                config {
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                    followRedirects(true)
                }
            }
        }
    }

    private val sessionMutex = Mutex()

    @Volatile
    private var activeSession: DeezerSession? = null

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _validating = MutableStateFlow(false)
    val validating: StateFlow<Boolean> = _validating.asStateFlow()

    /**
     * Pluggable gateway transport for unit testing without live network calls.
     * Input: `(method, apiToken, sid, arl, payload)` -> `(responseBody, setCookieSid)`.
     */
    internal var gatewayTransport: (suspend (
        method: String,
        apiToken: String,
        sid: String?,
        arl: String,
        payload: JsonObject,
    ) -> Pair<String, String?>)? = null

    /**
     * Pluggable media delivery transport for unit testing without live network calls.
     * Input: `(licenseToken, payload)` -> `responseBody`.
     */
    internal var mediaTransport: (suspend (
        licenseToken: String,
        payload: JsonObject,
    ) -> String)? = null

    fun currentSession(): DeezerSession? = activeSession

    fun invalidateSession(clearError: Boolean = true) {
        activeSession = null
        if (clearError) {
            _authError.value = null
        }
    }

    fun extractTrackId(songId: String): String =
        songId.trim().removePrefix("dz:").trim()

    private fun buildCookieHeader(arl: String, sid: String? = null): String = buildString {
        append("arl=").append(arl.trim())
        if (!sid.isNullOrBlank()) {
            append("; sid=").append(sid.trim())
        }
    }

    internal fun extractSidFromSetCookies(setCookieHeaders: List<String>): String? {
        for (header in setCookieHeaders) {
            val match = Regex("""(?:^|;\s*)sid=([^;\s]+)""", RegexOption.IGNORE_CASE).find(header)
            val candidate = match?.groupValues?.getOrNull(1)?.trim()
            if (!candidate.isNullOrEmpty() && candidate != "deleted") {
                return candidate
            }
        }
        return null
    }

    private suspend fun executeGatewayCall(
        method: String,
        apiToken: String,
        sid: String?,
        arl: String,
        payload: JsonObject = buildJsonObject {},
    ): Pair<String, String?> {
        gatewayTransport?.let { transport ->
            return transport(method, apiToken, sid, arl, payload)
        }
        val response = client.post(GATEWAY_URL) {
            parameter("method", method)
            parameter("input", "3")
            parameter("api_version", "1.0")
            parameter("api_token", apiToken.ifBlank { "null" })
            if (!sid.isNullOrBlank()) {
                parameter("cid", (100_000_000..999_999_999).random().toString())
            }
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.AcceptLanguage, "en-US,en;q=0.9")
            header(HttpHeaders.Cookie, buildCookieHeader(arl, sid))
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val bodyText = response.bodyAsText()
        val cookieSid = extractSidFromSetCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
        return bodyText to cookieSid
    }

    private suspend fun executeMediaCall(
        licenseToken: String,
        payload: JsonObject,
    ): String {
        mediaTransport?.let { transport ->
            return transport(licenseToken, payload)
        }
        val response = client.post(MEDIA_URL) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        return response.bodyAsText()
    }

    /**
     * Parses a gateway `deezer.getUserData`, `deezer.page.getUserData`, or `deezer.ping` JSON payload
     * into a [DeezerAuthResult].
     */
    fun parseUserDataSession(
        rawJson: String,
        arl: String,
        cookieSid: String? = null,
        fallbackApiToken: String = "",
        fallbackLicenseToken: String = "",
    ): DeezerAuthResult {
        val cleanArl = arl.trim()
        if (cleanArl.isEmpty()) {
            return DeezerAuthResult.InvalidToken("ARL token is empty")
        }
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            return DeezerAuthResult.NetworkError("Malformed gateway response: ${e.message}")
        }

        val errorElement = root["error"]
        if (errorElement is JsonObject && errorElement.isNotEmpty()) {
            val errorMsg = errorElement.entries.joinToString(", ") { (k, v) ->
                val detail = (v as? JsonPrimitive)?.content ?: v.toString()
                "$k: $detail"
            }
            return DeezerAuthResult.InvalidToken(errorMsg)
        }

        val results = (root["results"] as? JsonObject)
            ?: return DeezerAuthResult.NetworkError("Missing results object in gateway response")

        val userObj = (results["USER"] as? JsonObject) ?: results
        val userId = userObj["USER_ID"]?.jsonPrimitive?.longOrNull
            ?: results["USER_ID"]?.jsonPrimitive?.longOrNull
            ?: 0L

        if (userId <= 0L) {
            return DeezerAuthResult.InvalidToken("ARL token is invalid or expired")
        }

        val sid = results["SESSION_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: results["SESSION"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: cookieSid?.takeIf { it.isNotBlank() }
            ?: ""

        val apiToken = results["checkForm"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: results["CHECKFORM"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fallbackApiToken

        val optionsObj = (userObj["OPTIONS"] as? JsonObject)
            ?: (results["OPTIONS"] as? JsonObject)

        val licenseToken = optionsObj?.get("license_token")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: results["PLAYER_TOKEN"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fallbackLicenseToken

        val userName = userObj["BLOG_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: userObj["FIRSTNAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "Deezer #$userId"

        val canLossless = optionsObj.boolFlag("web_lossless") ||
            optionsObj.boolFlag("mobile_lossless")
        val canHq = canLossless ||
            optionsObj.boolFlag("web_hq") ||
            optionsObj.boolFlag("mobile_hq")

        val tier = when {
            canLossless -> DeezerTier.HIFI
            canHq -> DeezerTier.PREMIUM
            else -> DeezerTier.FREE
        }

        return DeezerAuthResult.Authenticated(
            DeezerSession(
                arl = cleanArl,
                sid = sid,
                apiToken = apiToken,
                licenseToken = licenseToken,
                userId = userId,
                userName = userName,
                tier = tier,
                canStreamLossless = canLossless,
                canStreamHq = canHq,
            )
        )
    }

    private fun JsonObject?.boolFlag(key: String): Boolean {
        val prim = this?.get(key) as? JsonPrimitive ?: return false
        return prim.booleanOrNull
            ?: (prim.intOrNull != null && prim.intOrNull != 0)
            ?: prim.content.equals("true", ignoreCase = true)
    }

    internal fun hasGatewayAuthError(rawJson: String): Boolean {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return false
        val err = root["error"] as? JsonObject ?: return false
        if (err.isEmpty()) return false
        return err.keys.any { key ->
            key.contains("TOKEN", ignoreCase = true) ||
                key.contains("AUTH", ignoreCase = true) ||
                key.contains("SESSION", ignoreCase = true) ||
                key.contains("GATEWAY", ignoreCase = true) ||
                key.contains("VALID", ignoreCase = true)
        }
    }

    /**
     * Validates an ARL token against Deezer's internal gateway (`deezer.getUserData` with fallback
     * to `deezer.page.getUserData` and `deezer.ping`), persists credentials on success, and updates
     * UI state flows.
     */
    suspend fun validateArl(
        arl: String,
        persistOnSuccess: Boolean = true,
    ): DeezerAuthResult = withContext(Dispatchers.IO) {
        val cleanArl = arl.trim()
        if (cleanArl.isEmpty()) {
            val res = DeezerAuthResult.InvalidToken("ARL token cannot be empty")
            _authError.value = res.reason
            return@withContext res
        }

        _validating.value = true
        try {
            val (rawUserData, cookieSid) = executeGatewayCall(
                method = "deezer.getUserData",
                apiToken = "null",
                sid = null,
                arl = cleanArl,
            )
            var result = parseUserDataSession(
                rawJson = rawUserData,
                arl = cleanArl,
                cookieSid = cookieSid,
            )

            // Fallback to deezer.page.getUserData if getUserData returned incomplete tokens
            if (result is DeezerAuthResult.Authenticated &&
                (result.session.licenseToken.isBlank() || result.session.apiToken.isBlank())
            ) {
                val baseSession = result.session
                val (pageRaw, pageCookieSid) = runCatching {
                    executeGatewayCall(
                        method = "deezer.page.getUserData",
                        apiToken = baseSession.apiToken.ifBlank { "null" },
                        sid = baseSession.sid.ifBlank { cookieSid },
                        arl = cleanArl,
                    )
                }.getOrNull() ?: ("" to null)
                if (pageRaw.isNotBlank()) {
                    val pageResult = parseUserDataSession(
                        rawJson = pageRaw,
                        arl = cleanArl,
                        cookieSid = pageCookieSid ?: baseSession.sid,
                        fallbackApiToken = baseSession.apiToken,
                        fallbackLicenseToken = baseSession.licenseToken,
                    )
                    if (pageResult is DeezerAuthResult.Authenticated) {
                        result = pageResult
                    }
                }
            }

            // Ping fallback if sid is still blank
            if (result is DeezerAuthResult.Authenticated && result.session.sid.isBlank()) {
                val baseSession = result.session
                val (pingRaw, pingCookieSid) = runCatching {
                    executeGatewayCall(
                        method = "deezer.ping",
                        apiToken = baseSession.apiToken.ifBlank { "null" },
                        sid = null,
                        arl = cleanArl,
                    )
                }.getOrNull() ?: ("" to null)
                if (pingRaw.isNotBlank()) {
                    val pingResult = parseUserDataSession(
                        rawJson = pingRaw,
                        arl = cleanArl,
                        cookieSid = pingCookieSid,
                        fallbackApiToken = baseSession.apiToken,
                        fallbackLicenseToken = baseSession.licenseToken,
                    )
                    if (pingResult is DeezerAuthResult.Authenticated) {
                        result = DeezerAuthResult.Authenticated(
                            baseSession.copy(
                                sid = pingResult.session.sid.ifBlank { baseSession.sid },
                                apiToken = pingResult.session.apiToken.ifBlank { baseSession.apiToken },
                            )
                        )
                    }
                }
            }

            when (result) {
                is DeezerAuthResult.Authenticated -> {
                    activeSession = result.session
                    _authError.value = null
                    if (persistOnSuccess) {
                        persistSession(result.session)
                    }
                }
                is DeezerAuthResult.InvalidToken -> {
                    activeSession = null
                    _authError.value = result.reason
                    if (persistOnSuccess) {
                        runCatching {
                            AppSettings.clearDeezerSessionTokens()
                        }
                    }
                }
                is DeezerAuthResult.NetworkError -> {
                    _authError.value = result.reason
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate Deezer ARL", e)
            val err = DeezerAuthResult.NetworkError(e.message ?: "Network error contacting Deezer")
            _authError.value = err.reason
            err
        } finally {
            _validating.value = false
        }
    }

    private fun persistSession(session: DeezerSession) {
        runCatching {
            AppSettings.setDeezerSessionInfo(
                arl = session.arl,
                sid = session.sid,
                licenseToken = session.licenseToken,
                userName = session.userName,
                tier = session.tier.label,
            )
        }
    }

    /**
     * Ensures a valid [DeezerSession] is available in memory, refreshing via the stored ARL
     * whenever the session is missing, expired, or [forceRefresh] is true.
     */
    suspend fun ensureSession(
        arlOverride: String? = null,
        forceRefresh: Boolean = false,
    ): DeezerSession? = sessionMutex.withLock {
        val configuredArl = (arlOverride
            ?: runCatching { AppSettings.deezerArl.value.takeIf { it.isNotBlank() } }.getOrNull()
            ?: activeSession?.arl)
            ?.trim()
            .orEmpty()

        if (configuredArl.isEmpty()) {
            activeSession = null
            return@withLock null
        }

        val current = activeSession
        if (!forceRefresh && current != null && current.arl == configuredArl && !current.isExpired()) {
            return@withLock current
        }

        when (val refreshed = validateArl(configuredArl, persistOnSuccess = true)) {
            is DeezerAuthResult.Authenticated -> refreshed.session
            else -> null
        }
    }

    /**
     * Executes an authenticated gateway request, automatically refreshing the session once if
     * the gateway reports an expired `api_token` or `sid`.
     */
    private suspend fun callAuthenticatedGateway(
        method: String,
        payload: JsonObject,
        arlOverride: String? = null,
    ): JsonObject? {
        var session = ensureSession(arlOverride = arlOverride, forceRefresh = false) ?: return null
        var (raw, _) = executeGatewayCall(
            method = method,
            apiToken = session.apiToken,
            sid = session.sid,
            arl = session.arl,
            payload = payload,
        )
        if (hasGatewayAuthError(raw)) {
            session = ensureSession(arlOverride = session.arl, forceRefresh = true) ?: return null
            val retry = executeGatewayCall(
                method = method,
                apiToken = session.apiToken,
                sid = session.sid,
                arl = session.arl,
                payload = payload,
            )
            raw = retry.first
        }
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    // ─── Stream Resolution & Tier Fallback ───────────────────────────────────────

    /**
     * Returns the ordered list of [DeezerQuality] formats to request for a given account [tier]
     * and playback [request] (`FLAC` -> `MP3_320` -> `MP3_128`).
     */
    fun orderedFormatsFor(
        tier: DeezerTier,
        request: StreamRequest,
    ): List<DeezerQuality> = when (request) {
        StreamRequest.Lossless -> when (tier) {
            DeezerTier.HIFI -> listOf(DeezerQuality.FLAC, DeezerQuality.MP3_320, DeezerQuality.MP3_128)
            DeezerTier.PREMIUM -> listOf(DeezerQuality.MP3_320, DeezerQuality.MP3_128)
            DeezerTier.FREE -> listOf(DeezerQuality.MP3_128)
        }
        StreamRequest.Best -> when (tier) {
            DeezerTier.HIFI, DeezerTier.PREMIUM -> listOf(DeezerQuality.MP3_320, DeezerQuality.MP3_128)
            DeezerTier.FREE -> listOf(DeezerQuality.MP3_128)
        }
        is StreamRequest.Capped -> when {
            request.maxKbps >= 320 -> when (tier) {
                DeezerTier.HIFI, DeezerTier.PREMIUM -> listOf(DeezerQuality.MP3_320, DeezerQuality.MP3_128)
                DeezerTier.FREE -> listOf(DeezerQuality.MP3_128)
            }
            else -> listOf(DeezerQuality.MP3_128)
        }
    }

    internal fun buildMediaRequestPayload(
        licenseToken: String,
        trackToken: String,
        formats: List<DeezerQuality>,
    ): JsonObject = buildJsonObject {
        put("license_token", licenseToken)
        put(
            "media",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "FULL")
                        put(
                            "formats",
                            buildJsonArray {
                                for (fmt in formats) {
                                    add(
                                        buildJsonObject {
                                            put("cipher", "BF_CBC_STRIPE")
                                            put("format", fmt.code)
                                        }
                                    )
                                }
                            }
                        )
                    }
                )
            }
        )
        put(
            "track_tokens",
            buildJsonArray {
                add(JsonPrimitive(trackToken))
            }
        )
    }

    /**
     * Parses the response from `https://media.deezer.com/v1/get_url`, selecting the highest-quality
     * available stream according to [requestedFormats] and computing honest [ResolvedDeezerStream.belowRequest]
     * telemetry.
     */
    fun parseMediaStreamResponse(
        rawJson: String,
        trackId: String,
        requestedFormats: List<DeezerQuality>,
        originalRequest: StreamRequest,
        durationSec: Int? = null,
    ): ResolvedDeezerStream? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val dataArr = root["data"] as? JsonArray ?: return null

        data class Candidate(
            val quality: DeezerQuality,
            val url: String,
            val encrypted: Boolean,
        )

        val candidates = mutableListOf<Candidate>()
        for (dataElem in dataArr) {
            val dataObj = dataElem as? JsonObject ?: continue
            val mediaArr = dataObj["media"] as? JsonArray ?: continue
            for (mediaElem in mediaArr) {
                val mediaObj = mediaElem as? JsonObject ?: continue
                val mediaType = mediaObj["media_type"]?.jsonPrimitive?.content
                if (mediaType != null && mediaType.equals("PREVIEW", ignoreCase = true)) {
                    continue
                }
                val formatStr = mediaObj["format"]?.jsonPrimitive?.content ?: continue
                val quality = DeezerQuality.entries.firstOrNull {
                    it.code.equals(formatStr, ignoreCase = true)
                } ?: continue

                val cipherType = (mediaObj["cipher"] as? JsonObject)
                    ?.get("type")?.jsonPrimitive?.content
                val encrypted = cipherType == null || cipherType.equals("BF_CBC_STRIPE", ignoreCase = true)

                val sourcesArr = mediaObj["sources"] as? JsonArray ?: continue
                val sourceUrl = sourcesArr.firstNotNullOfOrNull { srcElem ->
                    (srcElem as? JsonObject)?.get("url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                } ?: continue

                candidates.add(Candidate(quality = quality, url = sourceUrl, encrypted = encrypted))
            }
        }

        if (candidates.isEmpty()) return null

        val priorityOrder = (requestedFormats + DeezerQuality.entries).distinct()
        val chosen = priorityOrder.firstNotNullOfOrNull { desired ->
            candidates.firstOrNull { it.quality == desired }
        } ?: candidates.first()

        val belowRequest = when (originalRequest) {
            StreamRequest.Lossless -> chosen.quality != DeezerQuality.FLAC
            StreamRequest.Best -> chosen.quality != DeezerQuality.MP3_320 && chosen.quality != DeezerQuality.FLAC
            is StreamRequest.Capped -> {
                val deliveredKbps = chosen.quality.kbps ?: Int.MAX_VALUE
                val targetFloor = if (originalRequest.maxKbps >= 320) 320 else 128
                deliveredKbps < targetFloor
            }
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

    internal data class TrackTokenMeta(
        val trackToken: String,
        val trackId: String,
        val durationSec: Int?,
    )

    internal fun extractTrackTokenAndId(
        gatewayRoot: JsonObject?,
        requestedTrackId: String,
    ): TrackTokenMeta? {
        val results = gatewayRoot?.get("results") as? JsonObject ?: return null
        val songObj = when {
            results["TRACK_TOKEN"] != null -> results
            results["DATA"] is JsonObject -> results["DATA"] as JsonObject
            results["data"] is JsonArray -> (results["data"] as JsonArray).firstOrNull() as? JsonObject
            else -> null
        } ?: return null

        val fallbackObj = songObj["FALLBACK"] as? JsonObject
        val effectiveObj = if (fallbackObj?.get("TRACK_TOKEN")?.jsonPrimitive?.content?.isNotBlank() == true) {
            fallbackObj
        } else {
            songObj
        }

        val token = effectiveObj["TRACK_TOKEN"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: songObj["TRACK_TOKEN"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val sngId = effectiveObj["SNG_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: songObj["SNG_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: requestedTrackId
        val durationSec = effectiveObj["DURATION"]?.jsonPrimitive?.intOrNull
            ?: songObj["DURATION"]?.jsonPrimitive?.intOrNull
        return TrackTokenMeta(token, sngId, durationSec)
    }

    /**
     * Resolves a playable Deezer CDN stream URL for [songId], respecting the account's tier
     * and falling back cleanly across `FLAC` -> `MP3_320` -> `MP3_128`.
     */
    suspend fun getStreamUrl(
        songId: String,
        request: StreamRequest = StreamRequest.Lossless,
        arlOverride: String? = null,
    ): ResolvedDeezerStream? = withContext(Dispatchers.IO) {
        try {
            val cleanTrackId = extractTrackId(songId)
            if (cleanTrackId.isEmpty()) return@withContext null

            var session = ensureSession(arlOverride = arlOverride, forceRefresh = false)
                ?: return@withContext null

            val songDataRoot = callAuthenticatedGateway(
                method = "song.getData",
                payload = buildJsonObject { put("SNG_ID", cleanTrackId) },
                arlOverride = session.arl,
            )
            val meta = extractTrackTokenAndId(songDataRoot, cleanTrackId)
                ?: return@withContext null

            session = activeSession ?: session
            val formats = orderedFormatsFor(session.tier, request)
            if (formats.isEmpty()) return@withContext null

            // 1. Try batch media request with all permitted formats in priority order
            val batchPayload = buildMediaRequestPayload(session.licenseToken, meta.trackToken, formats)
            var mediaRaw = executeMediaCall(session.licenseToken, batchPayload)
            var resolved = parseMediaStreamResponse(
                mediaRaw,
                meta.trackId,
                formats,
                request,
                meta.durationSec,
            )
            if (resolved != null) return@withContext resolved

            // 2. If media call failed due to expired license_token, force-refresh session once
            val refreshed = ensureSession(arlOverride = session.arl, forceRefresh = true)
            if (refreshed != null && refreshed.licenseToken != session.licenseToken) {
                session = refreshed
                val retryFormats = orderedFormatsFor(session.tier, request)
                mediaRaw = executeMediaCall(
                    session.licenseToken,
                    buildMediaRequestPayload(session.licenseToken, meta.trackToken, retryFormats),
                )
                resolved = parseMediaStreamResponse(
                    mediaRaw,
                    meta.trackId,
                    retryFormats,
                    request,
                    meta.durationSec,
                )
                if (resolved != null) return@withContext resolved
            }

            // 3. Individual format fallback (FLAC -> MP3_320 -> MP3_128)
            for (fmt in orderedFormatsFor(session.tier, request)) {
                val singleRaw = runCatching {
                    executeMediaCall(
                        session.licenseToken,
                        buildMediaRequestPayload(session.licenseToken, meta.trackToken, listOf(fmt)),
                    )
                }.getOrNull() ?: continue
                val singleResolved = parseMediaStreamResponse(
                    rawJson = singleRaw,
                    trackId = meta.trackId,
                    requestedFormats = listOf(fmt),
                    originalRequest = request,
                    durationSec = meta.durationSec,
                )
                if (singleResolved != null) return@withContext singleResolved
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Deezer stream for $songId", e)
            null
        }
    }

    // ─── Catalog & Metadata Mapping ──────────────────────────────────────────────

    private fun coverUrlFromPictureId(pictureId: String?, category: String = CDN_COVER_BASE): String? {
        val clean = pictureId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
        return "$category/$clean/500x500-000000-80-0-0.jpg"
    }

    /**
     * Maps either a Deezer Public API track object or a Gateway `SNG_*` track object to [Song].
     */
    fun mapTrackToSong(
        item: JsonObject,
        sourceQuality: String = "LOSSLESS",
    ): Song? {
        val rawId = item["SNG_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null

        val baseTitle = item["SNG_TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val version = item["VERSION"]?.jsonPrimitive?.content?.trim().orEmpty()
        val title = if (version.isNotEmpty() && !baseTitle.contains(version, ignoreCase = true)) {
            "$baseTitle $version"
        } else {
            baseTitle
        }

        val artistsList = (item["ARTISTS"] as? JsonArray)?.mapNotNull { artElem ->
            (artElem as? JsonObject)?.get("ART_NAME")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }?.takeIf { it.isNotEmpty() }
            ?: (item["contributors"] as? JsonArray)?.mapNotNull { contrib ->
                (contrib as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }?.takeIf { it.isNotEmpty() }

        val artistObj = item["artist"] as? JsonObject
        val artistName = artistsList?.joinToString(", ")
            ?: item["ART_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: artistObj?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "Unknown Artist"

        val primaryArtistId = item["ART_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: artistObj?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val albumObj = item["album"] as? JsonObject
        val albumName = item["ALB_TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj?.get("title")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val albumId = item["ALB_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val coverUrl = albumObj?.get("cover_xl")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj?.get("cover_big")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj?.get("cover_medium")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(item["ALB_PICTURE"]?.jsonPrimitive?.content)

        val durationSec = item["DURATION"]?.jsonPrimitive?.longOrNull
            ?: item["duration"]?.jsonPrimitive?.longOrNull
            ?: 0L
        val durationText = if (durationSec > 0L) {
            String.format(Locale.ROOT, "%d:%02d", durationSec / 60L, durationSec % 60L)
        } else {
            null
        }

        val explicit = when (val exp = item["EXPLICIT_LYRICS"] ?: item["explicit_lyrics"]) {
            is JsonPrimitive -> exp.booleanOrNull == true || exp.content == "1" || exp.content == "4"
            else -> false
        }

        return Song(
            videoId = if (rawId.startsWith("dz:")) rawId else "dz:$rawId",
            title = title,
            artist = artistName,
            thumbnailUrl = coverUrl,
            durationText = durationText,
            artistId = primaryArtistId,
            albumId = albumId,
            albumName = albumName,
            sourceQuality = sourceQuality,
            isExplicit = explicit,
        )
    }

    fun mapAlbumToBrowseItem(item: JsonObject): BrowseItem? {
        val id = item["ALB_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val title = item["ALB_TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = item["ART_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: (item["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: ""
        val cover = item["cover_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["cover_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["cover_medium"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(item["ALB_PICTURE"]?.jsonPrimitive?.content)

        return BrowseItem(
            browseId = id,
            title = title,
            subtitle = artist,
            thumbnailUrl = cover,
            type = BrowseType.ALBUM,
        )
    }

    fun mapArtistToBrowseItem(item: JsonObject): BrowseItem? {
        val id = item["ART_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val name = item["ART_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val cover = item["picture_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["picture_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["picture_medium"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(item["ART_PICTURE"]?.jsonPrimitive?.content, CDN_ARTIST_BASE)

        return BrowseItem(
            browseId = id,
            title = name,
            subtitle = "Artist",
            thumbnailUrl = cover,
            type = BrowseType.ARTIST,
        )
    }

    fun mapPlaylistToBrowseItem(item: JsonObject): BrowseItem? {
        val id = item["PLAYLIST_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val title = item["TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val creator = item["PARENT_USERNAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: (item["user"] as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: "Playlist"
        val cover = item["picture_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["picture_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: item["picture_medium"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(item["PLAYLIST_PICTURE"]?.jsonPrimitive?.content, CDN_PLAYLIST_BASE)

        return BrowseItem(
            browseId = id,
            title = title,
            subtitle = creator,
            thumbnailUrl = cover,
            type = BrowseType.PLAYLIST,
        )
    }

    fun parseTrackSearchResponse(rawJson: String): List<Song> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
        val dataArray = (root["data"] as? JsonArray)
            ?: ((root["results"] as? JsonObject)?.get("data") as? JsonArray)
            ?: (((root["results"] as? JsonObject)?.get("TRACK") as? JsonObject)?.get("data") as? JsonArray)
            ?: return emptyList()
        val mapped = dataArray.mapNotNull { elem -> (elem as? JsonObject)?.let(::mapTrackToSong) }
        return prioritizeDeezerExplicit(mapped)
    }

    fun parseAlbumDetailsResponse(rawJson: String, fallbackBrowseId: String = ""): DetailPage? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val albumObj = ((root["results"] as? JsonObject)?.get("DATA") as? JsonObject) ?: root
        val browseId = albumObj["ALB_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fallbackBrowseId
        val title = albumObj["ALB_TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = albumObj["ART_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: (albumObj["artist"] as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: ""
        val cover = albumObj["cover_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: albumObj["cover_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(albumObj["ALB_PICTURE"]?.jsonPrimitive?.content)

        val tracksArray = ((albumObj["tracks"] as? JsonObject)?.get("data") as? JsonArray)
            ?: (((root["results"] as? JsonObject)?.get("SONGS") as? JsonObject)?.get("data") as? JsonArray)
            ?: JsonArray(emptyList())

        val songs = tracksArray.mapNotNull { elem ->
            val trackObj = elem as? JsonObject ?: return@mapNotNull null
            mapTrackToSong(trackObj)?.let { s ->
                s.copy(
                    albumName = s.albumName ?: title,
                    thumbnailUrl = s.thumbnailUrl ?: cover,
                )
            }
        }
        return DetailPage(
            browseId = browseId,
            title = title,
            subtitle = artist,
            thumbnailUrl = cover,
            songs = UiState.Success(songs),
            type = BrowseType.ALBUM,
        )
    }

    fun parsePlaylistDetailsResponse(rawJson: String, fallbackBrowseId: String = ""): DetailPage? {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        val playlistObj = ((root["results"] as? JsonObject)?.get("DATA") as? JsonObject) ?: root
        val browseId = playlistObj["PLAYLIST_ID"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: playlistObj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: fallbackBrowseId
        val title = playlistObj["TITLE"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: playlistObj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val subtitle = playlistObj["DESCRIPTION"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: playlistObj["description"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: (playlistObj["creator"] as? JsonObject)?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: ""
        val cover = playlistObj["picture_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: playlistObj["picture_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(playlistObj["PLAYLIST_PICTURE"]?.jsonPrimitive?.content, CDN_PLAYLIST_BASE)

        val tracksArray = ((playlistObj["tracks"] as? JsonObject)?.get("data") as? JsonArray)
            ?: (((root["results"] as? JsonObject)?.get("SONGS") as? JsonObject)?.get("data") as? JsonArray)
            ?: JsonArray(emptyList())

        val songs = tracksArray.mapNotNull { elem -> (elem as? JsonObject)?.let(::mapTrackToSong) }
        return DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = cover,
            songs = UiState.Success(songs),
            type = BrowseType.PLAYLIST,
        )
    }

    fun parseArtistDetailsResponse(
        artistJson: String,
        topTracksJson: String? = null,
        albumsJson: String? = null,
    ): ArtistPage? {
        val root = runCatching { json.parseToJsonElement(artistJson).jsonObject }.getOrNull() ?: return null
        val artistObj = ((root["results"] as? JsonObject)?.get("DATA") as? JsonObject) ?: root
        val name = artistObj["ART_NAME"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: artistObj["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return null
        val cover = artistObj["picture_xl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: artistObj["picture_big"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: coverUrlFromPictureId(artistObj["ART_PICTURE"]?.jsonPrimitive?.content, CDN_ARTIST_BASE)

        val topSongs = topTracksJson?.let(::parseTrackSearchResponse)
            ?: (((root["results"] as? JsonObject)?.get("TOP") as? JsonObject)?.get("data") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(::mapTrackToSong) }
            ?: emptyList()

        val albumsArr = albumsJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject["data"] as? JsonArray }.getOrNull()
        } ?: (((root["results"] as? JsonObject)?.get("ALBUMS") as? JsonObject)?.get("data") as? JsonArray)
            ?: JsonArray(emptyList())

        val albumItems = albumsArr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val id = obj["ALB_ID"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val albTitle = obj["ALB_TITLE"]?.jsonPrimitive?.content ?: obj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val albCover = obj["cover_xl"]?.jsonPrimitive?.content
                ?: obj["cover_big"]?.jsonPrimitive?.content
                ?: coverUrlFromPictureId(obj["ALB_PICTURE"]?.jsonPrimitive?.content)
            val releaseDate = obj["PHYSICAL_RELEASE_DATE"]?.jsonPrimitive?.content
                ?: obj["release_date"]?.jsonPrimitive?.content
            val year = releaseDate?.take(4)?.takeIf { it.length == 4 } ?: "Album"
            ShelfItem(
                title = albTitle,
                subtitle = year,
                thumbnailUrl = albCover,
                videoId = null,
                browseId = id,
            )
        }

        val sections = if (albumItems.isNotEmpty()) {
            listOf(HomeShelf(title = "Albums", items = albumItems))
        } else {
            emptyList()
        }

        return ArtistPage(
            songs = topSongs,
            moreSongsBrowseId = null,
            sections = sections,
            thumbnailUrl = cover,
            name = name,
        )
    }

    suspend fun searchTracks(query: String, limit: Int = 10): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val gwRoot = callAuthenticatedGateway(
                method = "deezer.pageSearch",
                payload = buildJsonObject {
                    put("query", query)
                    put("start", 0)
                    put("nb", limit)
                    put("suggest", false)
                    put("artist_suggest", false)
                    put("top_tracks", false)
                }
            )
            val gwSongs = gwRoot?.let { parseTrackSearchResponse(it.toString()) }.orEmpty()
            if (gwSongs.isNotEmpty()) {
                return@withContext gwSongs.take(limit)
            }

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

    suspend fun searchAlbums(query: String, limit: Int = 10): List<BrowseItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val raw = client.get("$PUBLIC_API_URL/search/album") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("q", query)
                parameter("limit", limit)
            }.bodyAsText()
            val data = json.parseToJsonElement(raw).jsonObject["data"] as? JsonArray ?: return@withContext emptyList()
            data.mapNotNull { (it as? JsonObject)?.let(::mapAlbumToBrowseItem) }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer album search failed for '$query'", e)
            emptyList()
        }
    }

    suspend fun searchArtists(query: String, limit: Int = 10): List<BrowseItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val raw = client.get("$PUBLIC_API_URL/search/artist") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("q", query)
                parameter("limit", limit)
            }.bodyAsText()
            val data = json.parseToJsonElement(raw).jsonObject["data"] as? JsonArray ?: return@withContext emptyList()
            data.mapNotNull { (it as? JsonObject)?.let(::mapArtistToBrowseItem) }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer artist search failed for '$query'", e)
            emptyList()
        }
    }

    suspend fun searchPlaylists(query: String, limit: Int = 10): List<BrowseItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val raw = client.get("$PUBLIC_API_URL/search/playlist") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("q", query)
                parameter("limit", limit)
            }.bodyAsText()
            val data = json.parseToJsonElement(raw).jsonObject["data"] as? JsonArray ?: return@withContext emptyList()
            data.mapNotNull { (it as? JsonObject)?.let(::mapPlaylistToBrowseItem) }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer playlist search failed for '$query'", e)
            emptyList()
        }
    }

    suspend fun getAlbumDetails(albumId: String): DetailPage? = withContext(Dispatchers.IO) {
        try {
            val cleanId = extractTrackId(albumId)
            val raw = client.get("$PUBLIC_API_URL/album/$cleanId") {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.bodyAsText()
            parseAlbumDetailsResponse(raw, cleanId)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer getAlbumDetails failed for $albumId", e)
            null
        }
    }

    suspend fun getPlaylistDetails(playlistId: String): DetailPage? = withContext(Dispatchers.IO) {
        try {
            val cleanId = extractTrackId(playlistId)
            val raw = client.get("$PUBLIC_API_URL/playlist/$cleanId") {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.bodyAsText()
            parsePlaylistDetailsResponse(raw, cleanId)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer getPlaylistDetails failed for $playlistId", e)
            null
        }
    }

    suspend fun getArtistDetails(artistId: String): ArtistPage? = withContext(Dispatchers.IO) {
        try {
            val cleanId = extractTrackId(artistId)
            val artistRaw = client.get("$PUBLIC_API_URL/artist/$cleanId") {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.bodyAsText()
            val topRaw = runCatching {
                client.get("$PUBLIC_API_URL/artist/$cleanId/top") {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    parameter("limit", 10)
                }.bodyAsText()
            }.getOrNull()
            val albumsRaw = runCatching {
                client.get("$PUBLIC_API_URL/artist/$cleanId/albums") {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    parameter("limit", 25)
                }.bodyAsText()
            }.getOrNull()
            parseArtistDetailsResponse(artistRaw, topRaw, albumsRaw)
        } catch (e: Exception) {
            Log.e(TAG, "Deezer getArtistDetails failed for $artistId", e)
            null
        }
    }
}

internal fun prioritizeDeezerExplicit(songs: List<Song>): List<Song> {
    if (songs.size < 2) return songs
    val explicitKeys = songs.asSequence()
        .filter { it.isExplicit == true }
        .map { deezerCanonicalKey(it) }
        .toSet()
    if (explicitKeys.isEmpty()) return songs
    val (preferred, demoted) = songs.partition { song ->
        song.isExplicit == true || deezerCanonicalKey(song) !in explicitKeys
    }
    return preferred + demoted
}

private fun deezerCanonicalKey(s: Song): Pair<String, String> =
    s.title.lowercase().trim() to s.artist.lowercase().trim()
