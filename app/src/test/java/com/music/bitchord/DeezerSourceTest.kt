package com.music.bitchord

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.music.bitchord.auth.extractArlFromCookieHeader
import com.music.bitchord.data.deezer.DeezerAuthResult
import com.music.bitchord.data.deezer.DeezerCipher
import com.music.bitchord.data.deezer.DeezerQuality
import com.music.bitchord.data.deezer.DeezerService
import com.music.bitchord.data.deezer.DeezerTier
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.permits
import com.music.bitchord.data.sources.DeezerSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.playback.DeezerDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class DeezerSourceTest {

    @Before
    @After
    fun resetState() {
        DeezerService.gatewayTransport = null
        DeezerService.mediaTransport = null
        DeezerService.invalidateSession()
        AppSettings.clearDeezer()
    }

    @Test
    fun `parses HiFi session from deezer getUserData response`() {
        val raw = """
            {"error":[],"results":{"SESSION_ID":"sid_hifi_123","checkForm":"token_abc_999",
            "USER":{"USER_ID":424242,"BLOG_NAME":"AudiophileUser",
            "OPTIONS":{"license_token":"lic_xyz_777","web_lossless":true,"mobile_lossless":true,"web_hq":true}}}}
        """.trimIndent()

        val session = (DeezerService.parseUserDataSession(raw, arl = "arl_secret_token") as DeezerAuthResult.Authenticated).session
        assertEquals("arl_secret_token", session.arl)
        assertEquals("sid_hifi_123", session.sid)
        assertEquals("token_abc_999", session.apiToken)
        assertEquals("lic_xyz_777", session.licenseToken)
        assertEquals(424242L, session.userId)
        assertEquals("AudiophileUser", session.userName)
        assertEquals(DeezerTier.HIFI, session.tier)
        assertTrue(session.canStreamLossless)
        assertTrue(session.canStreamHq)
    }

    @Test
    fun `parses Premium and Free tiers accurately from user options`() {
        val premiumJson = """
            {"error":[],"results":{"SESSION":"sid_prem","CHECKFORM":"tok_prem",
            "USER":{"USER_ID":1001,"FIRSTNAME":"PremUser",
            "OPTIONS":{"license_token":"lic_prem","web_lossless":false,"mobile_lossless":false,"web_hq":true}}}}
        """.trimIndent()
        val premRes = DeezerService.parseUserDataSession(premiumJson, arl = "arl_prem") as DeezerAuthResult.Authenticated
        assertEquals(DeezerTier.PREMIUM, premRes.session.tier)

        val freeJson = """
            {"error":[],"results":{"USER":{"USER_ID":1002,
            "OPTIONS":{"license_token":"lic_free","web_lossless":false,"web_hq":false}}}}
        """.trimIndent()
        val freeRes = DeezerService.parseUserDataSession(
            rawJson = freeJson,
            arl = "arl_free",
            cookieSid = "sid_from_cookie",
        ) as DeezerAuthResult.Authenticated
        assertEquals(DeezerTier.FREE, freeRes.session.tier)
        assertEquals("sid_from_cookie", freeRes.session.sid)
    }

    @Test
    fun `rejects anonymous USER_ID 0 or gateway error when validating ARL`() {
        val anonJson = """{"error":[],"results":{"SESSION_ID":"anon_sid","USER":{"USER_ID":0}}}"""
        assertTrue(DeezerService.parseUserDataSession(anonJson, arl = "expired_arl") is DeezerAuthResult.InvalidToken)

        val errorJson = """{"error":{"GATEWAY_ERROR":"Invalid user token"},"results":{}}"""
        val errRes = DeezerService.parseUserDataSession(errorJson, arl = "bad_arl") as DeezerAuthResult.InvalidToken
        assertTrue(errRes.reason.contains("GATEWAY_ERROR"))
    }

    @Test
    fun `validateArl falls back to page getUserData and ping when initial tokens are incomplete`() = runBlocking {
        val calls = mutableListOf<String>()
        DeezerService.gatewayTransport = { method, _, _, _, _ ->
            calls += method
            when (method) {
                "deezer.getUserData" -> """{"error":[],"results":{"USER":{"USER_ID":777,"BLOG_NAME":"Tester"}}}""" to null
                "deezer.page.getUserData" -> """{"error":[],"results":{"checkForm":"form_token_2","USER":{"USER_ID":777,"BLOG_NAME":"Tester","OPTIONS":{"license_token":"lic_2","web_lossless":true}}}}""" to null
                "deezer.ping" -> """{"error":[],"results":{"SESSION":"sid_from_ping","USER_ID":777,"CHECKFORM":"form_token_2"}}""" to "sid_cookie_fallback"
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        val session = (DeezerService.validateArl("valid_arl_123") as DeezerAuthResult.Authenticated).session
        assertEquals(listOf("deezer.getUserData", "deezer.page.getUserData", "deezer.ping"), calls)
        assertEquals("sid_from_ping", session.sid)
        assertEquals("form_token_2", session.apiToken)
        assertEquals("lic_2", session.licenseToken)
        assertEquals(DeezerTier.HIFI, session.tier)
        assertEquals("valid_arl_123", AppSettings.deezerArl.value)
    }

    @Test
    fun `extracts ARL cookie and Set-Cookie sid accurately`() {
        val cookieHeader = "dzr_uniq_id=dzr.123; sid=fr12345; arl=0123456789abcdef0123456789abcdef0123456789abcdef; Domain=.deezer.com"
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef", extractArlFromCookieHeader(cookieHeader))
        assertNull(extractArlFromCookieHeader("sid=abc; arl=deleted"))
        assertNull(extractArlFromCookieHeader("sid=abc; arl=short"))

        val setCookies = listOf(
            "sid=deleted; expires=Thu, 01-Jan-1970 00:00:01 GMT",
            "sid=live_sid_987654; Path=/; HttpOnly",
        )
        assertEquals("live_sid_987654", DeezerService.extractSidFromSetCookies(setCookies))
    }

    @Test
    fun `automatically refreshes expired session during stream resolution`() = runBlocking {
        var getUserDataCount = 0
        var songGetDataCount = 0

        DeezerService.gatewayTransport = { method, apiToken, _, _, _ ->
            when (method) {
                "deezer.getUserData" -> {
                    getUserDataCount++
                    val n = getUserDataCount
                    """{"error":[],"results":{"SESSION_ID":"sid_$n","checkForm":"api_tok_$n","USER":{"USER_ID":99,"BLOG_NAME":"RefreshUser","OPTIONS":{"license_token":"lic_tok_$n","web_lossless":true}}}}""" to "sid_$n"
                }
                "song.getData" -> {
                    songGetDataCount++
                    if (apiToken == "api_tok_1") {
                        """{"error":{"VALID_TOKEN_REQUIRED":"Invalid CSRF token"},"results":{}}""" to null
                    } else {
                        """{"error":[],"results":{"SNG_ID":"3135556","DURATION":"215","TRACK_TOKEN":"trk_tok_fresh"}}""" to null
                    }
                }
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        DeezerService.mediaTransport = { licenseToken, _ ->
            assertEquals("lic_tok_2", licenseToken)
            """{"data":[{"media":[{"media_type":"FULL","cipher":{"type":"BF_CBC_STRIPE"},"format":"FLAC","sources":[{"url":"https://e-cdns-proxy-1.dzcdn.net/mobile/1/flac_stream"}]}]}]}"""
        }

        AppSettings.setDeezerArl("arl_for_refresh")
        val resolved = DeezerService.getStreamUrl("dz:3135556", StreamRequest.Lossless)
        assertNotNull(resolved)
        assertEquals(2, getUserDataCount)
        assertEquals(2, songGetDataCount)
        assertEquals(DeezerQuality.FLAC, resolved!!.quality)
        assertEquals("3135556", resolved.trackId)
        assertEquals(215, resolved.durationSec)
        assertFalse(resolved.belowRequest)
    }

    @Test
    fun `maps track metadata and prioritizes explicit recordings`() {
        val rawSearch = """
            {"data":[
              {"id":101,"title":"Harder Better Faster Stronger","duration":224,"explicit_lyrics":false,
               "artist":{"id":27,"name":"Daft Punk"},"album":{"id":302127,"title":"Discovery","cover_xl":"https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg"}},
              {"id":102,"title":"Harder Better Faster Stronger","duration":224,"explicit_lyrics":true,
               "artist":{"id":27,"name":"Daft Punk"},"album":{"id":302127,"title":"Discovery","cover_xl":"https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg"}}
            ]}
        """.trimIndent()

        val songs = DeezerService.parseTrackSearchResponse(rawSearch)
        assertEquals(2, songs.size)
        assertEquals("dz:102", songs[0].videoId)
        assertEquals(true, songs[0].isExplicit)
        assertEquals("3:44", songs[0].durationText)
        assertEquals("Discovery", songs[0].albumName)
        assertEquals("302127", songs[0].albumId)
        assertEquals("27", songs[0].artistId)
        assertEquals("dz:101", songs[1].videoId)
        assertEquals(false, songs[1].isExplicit)
    }

    @Test
    fun `orders formats and falls back FLAC to MP3_320 to MP3_128 with honest telemetry`() = runBlocking {
        assertEquals(
            listOf(DeezerQuality.FLAC, DeezerQuality.MP3_320, DeezerQuality.MP3_128),
            DeezerService.orderedFormatsFor(DeezerTier.HIFI, StreamRequest.Lossless),
        )
        assertEquals(
            listOf(DeezerQuality.MP3_320, DeezerQuality.MP3_128),
            DeezerService.orderedFormatsFor(DeezerTier.PREMIUM, StreamRequest.Lossless),
        )
        assertEquals(
            listOf(DeezerQuality.MP3_128),
            DeezerService.orderedFormatsFor(DeezerTier.FREE, StreamRequest.Lossless),
        )
        assertEquals(
            listOf(DeezerQuality.MP3_320, DeezerQuality.MP3_128),
            DeezerService.orderedFormatsFor(DeezerTier.HIFI, StreamRequest.Capped(320)),
        )
        assertEquals(
            listOf(DeezerQuality.MP3_128),
            DeezerService.orderedFormatsFor(DeezerTier.HIFI, StreamRequest.Capped(160)),
        )

        assertTrue(AudioQuality.LOSSLESS.permits(SourceKind.DEEZER))
        assertTrue(AudioQuality.HIGH.permits(SourceKind.DEEZER))
        assertFalse(AudioQuality.MEDIUM.permits(SourceKind.DEEZER))

        DeezerService.gatewayTransport = { method, _, _, _, _ ->
            when (method) {
                "deezer.getUserData" -> """{"error":[],"results":{"SESSION_ID":"s1","checkForm":"tok1","USER":{"USER_ID":1,"OPTIONS":{"license_token":"lic1","web_lossless":true}}}}""" to "s1"
                "song.getData" -> """{"error":[],"results":{"SNG_ID":"555","DURATION":"180","TRACK_TOKEN":"tt_555"}}""" to null
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        DeezerService.mediaTransport = { _, payload: JsonObject ->
            val formats = payload["media"]!!.jsonArray[0].jsonObject["formats"]!!.jsonArray
                .map { it.jsonObject["format"]!!.jsonPrimitive.content }
            if ("FLAC" in formats) {
                """{"data":[{"media":[]}]}"""
            } else {
                """{"data":[{"media":[{"media_type":"FULL","cipher":{"type":"BF_CBC_STRIPE"},"format":"MP3_320","sources":[{"url":"https://e-cdns-proxy.dzcdn.net/mobile/1/mp3_320_url"}]}]}]}"""
            }
        }

        AppSettings.setDeezerArl("arl_fallback_test")
        val source = DeezerSource(SourceConfig(id = "dz-cfg", kind = SourceKind.DEEZER, enabled = true))
        assertTrue(source.health() is SourceHealth.Ok)

        val stream = source.stream("555", StreamRequest.Lossless)
        assertNotNull(stream)
        assertEquals("https://e-cdns-proxy.dzcdn.net/mobile/1/mp3_320_url", stream!!.url)
        assertEquals("mp3", stream.format.codec)
        assertEquals(320, stream.format.kbps)
        assertEquals(false, stream.format.isLossless)
        assertTrue(stream.belowRequest)
        assertEquals("555", stream.headers[DeezerCipher.HEADER_TRACK_ID])
    }

    @Test
    fun `decrypts 2048-byte stride chunks and leaves trailing partial chunk untouched`() {
        val trackId = "3135556"
        val key = DeezerCipher.deriveKey(trackId)
        assertEquals(16, key.size)

        val totalSize = 7 * DeezerCipher.CHUNK_SIZE + 519
        val originalPlain = ByteArray(totalSize).also { Random(42).nextBytes(it) }
        val wireEncrypted = originalPlain.copyOf()
        for (chunkIdx in 0 until 7) {
            if (DeezerCipher.isEncryptedChunk(chunkIdx.toLong(), DeezerCipher.CHUNK_SIZE)) {
                DeezerCipher.encryptChunkInPlace(key, wireEncrypted, chunkIdx * DeezerCipher.CHUNK_SIZE)
            }
        }

        assertFalse(originalPlain.copyOfRange(0, 2048).contentEquals(wireEncrypted.copyOfRange(0, 2048)))
        assertArrayEquals(originalPlain.copyOfRange(2048, 4096), wireEncrypted.copyOfRange(2048, 4096))
        assertFalse(originalPlain.copyOfRange(3 * 2048, 4 * 2048).contentEquals(wireEncrypted.copyOfRange(3 * 2048, 4 * 2048)))
        assertArrayEquals(originalPlain.copyOfRange(7 * 2048, totalSize), wireEncrypted.copyOfRange(7 * 2048, totalSize))
        assertArrayEquals(originalPlain, DeezerCipher.decryptStream(wireEncrypted, trackId))
    }

    @Test
    fun `DeezerDataSource handles partial network reads, arbitrary mid-chunk seeks, and bounded lengths`() {
        val trackId = "987654321"
        val key = DeezerCipher.deriveKey(trackId)
        val totalSize = 10 * DeezerCipher.CHUNK_SIZE + 333
        val plaintext = ByteArray(totalSize) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
        val ciphertext = plaintext.copyOf()
        for (chunkIdx in 0 until 10) {
            if (DeezerCipher.isEncryptedChunk(chunkIdx.toLong(), DeezerCipher.CHUNK_SIZE)) {
                DeezerCipher.encryptChunkInPlace(key, ciphertext, chunkIdx * DeezerCipher.CHUNK_SIZE)
            }
        }

        val dummyUriClassBytes = ByteArrayOutputStream().apply {
            val thisName = "com/music/bitchord/DummyUri".toByteArray(Charsets.UTF_8)
            val superName = "android/net/Uri".toByteArray(Charsets.UTF_8)
            write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            write(byteArrayOf(0x00, 0x00, 0x00, 0x3D, 0x00, 0x05, 0x07, 0x00, 0x02))
            write(byteArrayOf(0x01, 0x00, thisName.size.toByte()))
            write(thisName)
            write(byteArrayOf(0x07, 0x00, 0x04, 0x01, 0x00, superName.size.toByte()))
            write(superName)
            write(byteArrayOf(0x00, 0x21, 0x00, 0x01, 0x00, 0x03))
            write(ByteArray(8))
        }.toByteArray()
        val dummyUriClass = object : ClassLoader(Uri::class.java.classLoader) {
            fun load(): Class<*> = defineClass("com.music.bitchord.DummyUri", dummyUriClassBytes, 0, dummyUriClassBytes.size)
        }.load()
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val dummyUri = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, dummyUriClass) as Uri

        var lastOpenedSpec: DataSpec? = null
        val drippedUpstream = object : DataSource {
            private var pos = 0
            private var end = ciphertext.size
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long {
                lastOpenedSpec = dataSpec
                pos = dataSpec.position.toInt()
                end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(ciphertext.size, (dataSpec.position + dataSpec.length).toInt())
                } else {
                    ciphertext.size
                }
                return (end - pos).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos >= end) return C.RESULT_END_OF_INPUT
                val step = minOf(length, 137, end - pos)
                System.arraycopy(ciphertext, pos, buffer, offset, step)
                pos += step
                return step
            }
            override fun getUri(): Uri = dummyUri
            override fun close() {}
        }

        val dataSource = DeezerDataSource(drippedUpstream)
        fun readAll(): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(61)
            while (true) {
                val r = dataSource.read(buf, 0, buf.size)
                if (r == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, r)
            }
            dataSource.close()
            return out.toByteArray()
        }

        // Case 1: Mid-encrypted-chunk seek (start inside chunk 3 at offset 3*2048 + 511, length = 5000)
        val seekStart = 3 * DeezerCipher.CHUNK_SIZE + 511
        val seekLength = 5000
        val spec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(seekStart.toLong())
            .setLength(seekLength.toLong())
            .setHttpRequestHeaders(mapOf(DeezerCipher.HEADER_TRACK_ID to trackId, "User-Agent" to "BitChordTest"))
            .build()

        assertEquals(seekLength.toLong(), dataSource.open(spec))
        val openedSpec = lastOpenedSpec!!
        assertFalse(openedSpec.httpRequestHeaders.containsKey(DeezerCipher.HEADER_TRACK_ID))
        assertEquals("BitChordTest", openedSpec.httpRequestHeaders["User-Agent"])
        assertEquals((3 * DeezerCipher.CHUNK_SIZE).toLong(), openedSpec.position)
        assertArrayEquals(plaintext.copyOfRange(seekStart, seekStart + seekLength), readAll())

        // Case 2: Seek into chunk 9 through the unencrypted trailing 333-byte EOF chunk
        val tailStart = 9 * DeezerCipher.CHUNK_SIZE + 1900
        val tailSpec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(tailStart.toLong())
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(mapOf(DeezerCipher.HEADER_TRACK_ID to trackId))
            .build()
        assertEquals((totalSize - tailStart).toLong(), dataSource.open(tailSpec))
        assertArrayEquals(plaintext.copyOfRange(tailStart, totalSize), readAll())

        // Case 3: Unencrypted passthrough when X-Deezer-Track-Id is absent
        val passthroughSpec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(123L)
            .setLength(456L)
            .build()
        dataSource.open(passthroughSpec)
        assertEquals(123L, lastOpenedSpec?.position)
        assertArrayEquals(ciphertext.copyOfRange(123, 123 + 456), readAll())
    }
}
