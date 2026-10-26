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
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.UiState
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
    fun setUp() {
        DeezerService.gatewayTransport = null
        DeezerService.mediaTransport = null
        DeezerService.invalidateSession()
        AppSettings.clearDeezer()
    }

    @After
    fun tearDown() {
        DeezerService.gatewayTransport = null
        DeezerService.mediaTransport = null
        DeezerService.invalidateSession()
        AppSettings.clearDeezer()
    }

    // ─── Authentication & Session Lifecycle ──────────────────────────────────────

    @Test
    fun `parses HiFi session from deezer getUserData response`() {
        val raw = """
            {
              "error": [],
              "results": {
                "SESSION_ID": "sid_hifi_123",
                "checkForm": "token_abc_999",
                "USER": {
                  "USER_ID": 424242,
                  "BLOG_NAME": "AudiophileUser",
                  "OPTIONS": {
                    "license_token": "lic_xyz_777",
                    "web_lossless": true,
                    "mobile_lossless": true,
                    "web_hq": true
                  }
                }
              }
            }
        """.trimIndent()

        val res = DeezerService.parseUserDataSession(raw, arl = "arl_secret_token")
        assertTrue(res is DeezerAuthResult.Authenticated)
        val session = (res as DeezerAuthResult.Authenticated).session
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
            {
              "error": [],
              "results": {
                "SESSION": "sid_prem",
                "CHECKFORM": "tok_prem",
                "USER": {
                  "USER_ID": 1001,
                  "FIRSTNAME": "PremUser",
                  "OPTIONS": {
                    "license_token": "lic_prem",
                    "web_lossless": false,
                    "mobile_lossless": false,
                    "web_hq": true
                  }
                }
              }
            }
        """.trimIndent()
        val premRes = DeezerService.parseUserDataSession(premiumJson, arl = "arl_prem")
        assertTrue(premRes is DeezerAuthResult.Authenticated)
        assertEquals(DeezerTier.PREMIUM, (premRes as DeezerAuthResult.Authenticated).session.tier)

        val freeJson = """
            {
              "error": [],
              "results": {
                "USER": {
                  "USER_ID": 1002,
                  "OPTIONS": {
                    "license_token": "lic_free",
                    "web_lossless": false,
                    "web_hq": false
                  }
                }
              }
            }
        """.trimIndent()
        val freeRes = DeezerService.parseUserDataSession(
            rawJson = freeJson,
            arl = "arl_free",
            cookieSid = "sid_from_cookie",
        )
        assertTrue(freeRes is DeezerAuthResult.Authenticated)
        val freeSession = (freeRes as DeezerAuthResult.Authenticated).session
        assertEquals(DeezerTier.FREE, freeSession.tier)
        assertEquals("sid_from_cookie", freeSession.sid)
    }

    @Test
    fun `rejects anonymous USER_ID 0 or gateway error when validating ARL`() {
        val anonJson = """
            {
              "error": [],
              "results": {
                "SESSION_ID": "anon_sid",
                "USER": {
                  "USER_ID": 0
                }
              }
            }
        """.trimIndent()
        val anonRes = DeezerService.parseUserDataSession(anonJson, arl = "expired_arl")
        assertTrue(anonRes is DeezerAuthResult.InvalidToken)

        val errorJson = """
            {
              "error": {
                "GATEWAY_ERROR": "Invalid user token"
              },
              "results": {}
            }
        """.trimIndent()
        val errRes = DeezerService.parseUserDataSession(errorJson, arl = "bad_arl")
        assertTrue(errRes is DeezerAuthResult.InvalidToken)
        assertTrue((errRes as DeezerAuthResult.InvalidToken).reason.contains("GATEWAY_ERROR"))
    }

    @Test
    fun `validateArl falls back to page getUserData and ping when initial tokens are incomplete`() = runBlocking {
        val calls = mutableListOf<String>()
        DeezerService.gatewayTransport = { method, _, _, _, _ ->
            calls += method
            when (method) {
                "deezer.getUserData" -> """
                    {"error":[],"results":{"USER":{"USER_ID":777,"BLOG_NAME":"Tester"}}}
                """.trimIndent() to null
                "deezer.page.getUserData" -> """
                    {"error":[],"results":{"checkForm":"form_token_2","USER":{"USER_ID":777,"BLOG_NAME":"Tester","OPTIONS":{"license_token":"lic_2","web_lossless":true}}}}
                """.trimIndent() to null
                "deezer.ping" -> """
                    {"error":[],"results":{"SESSION":"sid_from_ping","USER_ID":777,"CHECKFORM":"form_token_2"}}
                """.trimIndent() to "sid_cookie_fallback"
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        val result = DeezerService.validateArl("valid_arl_123")
        assertTrue(result is DeezerAuthResult.Authenticated)
        val session = (result as DeezerAuthResult.Authenticated).session
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
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef",
            extractArlFromCookieHeader(cookieHeader),
        )
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
                    val token = "api_tok_$getUserDataCount"
                    val lic = "lic_tok_$getUserDataCount"
                    val sid = "sid_$getUserDataCount"
                    """
                    {
                      "error": [],
                      "results": {
                        "SESSION_ID": "$sid",
                        "checkForm": "$token",
                        "USER": {
                          "USER_ID": 99,
                          "BLOG_NAME": "RefreshUser",
                          "OPTIONS": {
                            "license_token": "$lic",
                            "web_lossless": true
                          }
                        }
                      }
                    }
                    """.trimIndent() to sid
                }
                "song.getData" -> {
                    songGetDataCount++
                    if (apiToken == "api_tok_1") {
                        // Simulate expired gateway token on first attempt
                        """{"error":{"VALID_TOKEN_REQUIRED":"Invalid CSRF token"},"results":{}}""" to null
                    } else {
                        """
                        {
                          "error": [],
                          "results": {
                            "SNG_ID": "3135556",
                            "DURATION": "215",
                            "TRACK_TOKEN": "trk_tok_fresh"
                          }
                        }
                        """.trimIndent() to null
                    }
                }
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        DeezerService.mediaTransport = { licenseToken, _ ->
            assertEquals("lic_tok_2", licenseToken)
            """
            {
              "data": [
                {
                  "media": [
                    {
                      "media_type": "FULL",
                      "cipher": { "type": "BF_CBC_STRIPE" },
                      "format": "FLAC",
                      "sources": [
                        { "url": "https://e-cdns-proxy-1.dzcdn.net/mobile/1/flac_stream", "provider": "ak" }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
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

    // ─── Metadata Mapping & Quality Fallback ─────────────────────────────────────

    @Test
    fun `maps track metadata and prioritizes explicit recordings`() {
        val rawSearch = """
            {
              "data": [
                {
                  "id": 101,
                  "title": "Harder Better Faster Stronger",
                  "duration": 224,
                  "explicit_lyrics": false,
                  "artist": { "id": 27, "name": "Daft Punk" },
                  "album": {
                    "id": 302127,
                    "title": "Discovery",
                    "cover_xl": "https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg"
                  }
                },
                {
                  "id": 102,
                  "title": "Harder Better Faster Stronger",
                  "duration": 224,
                  "explicit_lyrics": true,
                  "artist": { "id": 27, "name": "Daft Punk" },
                  "album": {
                    "id": 302127,
                    "title": "Discovery",
                    "cover_xl": "https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg"
                  }
                }
              ]
            }
        """.trimIndent()

        val songs = DeezerService.parseTrackSearchResponse(rawSearch)
        assertEquals(2, songs.size)
        // Explicit version prioritized ahead of clean duplicate
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
    fun `maps album, playlist, and artist details to BitChord domain models`() {
        val albumRaw = """
            {
              "id": 302127,
              "title": "Discovery",
              "cover_xl": "https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg",
              "artist": { "id": 27, "name": "Daft Punk" },
              "tracks": {
                "data": [
                  {
                    "id": 3135553,
                    "title": "One More Time",
                    "duration": 320,
                    "explicit_lyrics": false,
                    "artist": { "id": 27, "name": "Daft Punk" }
                  }
                ]
              }
            }
        """.trimIndent()
        val albumPage = DeezerService.parseAlbumDetailsResponse(albumRaw)
        assertNotNull(albumPage)
        assertEquals("302127", albumPage!!.browseId)
        assertEquals("Discovery", albumPage.title)
        assertEquals("Daft Punk", albumPage.subtitle)
        assertEquals(BrowseType.ALBUM, albumPage.type)
        val albumSongs = (albumPage.songs as UiState.Success).data
        assertEquals(1, albumSongs.size)
        assertEquals("One More Time", albumSongs[0].title)
        assertEquals("Discovery", albumSongs[0].albumName)
        assertEquals("5:20", albumSongs[0].durationText)

        val artistRaw = """
            {
              "id": 27,
              "name": "Daft Punk",
              "picture_xl": "https://cdn-images.dzcdn.net/images/artist/dp/1000x1000.jpg"
            }
        """.trimIndent()
        val albumsRaw = """
            {
              "data": [
                {
                  "id": 302127,
                  "title": "Discovery",
                  "release_date": "2001-03-07",
                  "cover_xl": "https://cdn-images.dzcdn.net/images/cover/disc/1000x1000.jpg"
                }
              ]
            }
        """.trimIndent()
        val artistPage = DeezerService.parseArtistDetailsResponse(artistRaw, null, albumsRaw)
        assertNotNull(artistPage)
        assertEquals("Daft Punk", artistPage!!.name)
        assertEquals(1, artistPage.sections.size)
        assertEquals("2001", artistPage.sections[0].items[0].subtitle)
    }

    @Test
    fun `orders formats and falls back FLAC to MP3_320 to MP3_128 with honest telemetry`() = runBlocking {
        // 1. Format ladder per tier & request
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

        // 2. AudioQuality.permits allows Deezer on both LOSSLESS and HIGH
        assertTrue(AudioQuality.LOSSLESS.permits(SourceKind.DEEZER))
        assertTrue(AudioQuality.HIGH.permits(SourceKind.DEEZER))
        assertFalse(AudioQuality.MEDIUM.permits(SourceKind.DEEZER))

        // 3. Fallback when FLAC is unavailable on CDN -> MP3_320 -> MP3_128
        DeezerService.gatewayTransport = { method, _, _, _, _ ->
            when (method) {
                "deezer.getUserData" -> """
                    {"error":[],"results":{"SESSION_ID":"s1","checkForm":"tok1","USER":{"USER_ID":1,"OPTIONS":{"license_token":"lic1","web_lossless":true}}}}
                """.trimIndent() to "s1"
                "song.getData" -> """
                    {"error":[],"results":{"SNG_ID":"555","DURATION":"180","TRACK_TOKEN":"tt_555"}}
                """.trimIndent() to null
                else -> """{"error":[],"results":{}}""" to null
            }
        }

        val requestedFormatLists = mutableListOf<List<String>>()
        DeezerService.mediaTransport = { _, payload: JsonObject ->
            val formats = payload["media"]!!.jsonArray[0].jsonObject["formats"]!!.jsonArray
                .map { it.jsonObject["format"]!!.jsonPrimitive.content }
            requestedFormatLists += formats
            // Simulate batch failing when FLAC is included, and single FLAC failing, so MP3_320 wins
            if ("FLAC" in formats) {
                """{"data":[{"media":[]}]}"""
            } else {
                """
                {
                  "data": [
                    {
                      "media": [
                        {
                          "media_type": "FULL",
                          "cipher": { "type": "BF_CBC_STRIPE" },
                          "format": "MP3_320",
                          "sources": [
                            { "url": "https://e-cdns-proxy.dzcdn.net/mobile/1/mp3_320_url" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            }
        }

        AppSettings.setDeezerArl("arl_fallback_test")
        val source = DeezerSource(SourceConfig(id = "dz-cfg", kind = SourceKind.DEEZER, enabled = true))
        val health = source.health()
        assertTrue(health is SourceHealth.Ok)

        val stream = source.stream("555", StreamRequest.Lossless)
        assertNotNull(stream)
        assertEquals("https://e-cdns-proxy.dzcdn.net/mobile/1/mp3_320_url", stream!!.url)
        // Honest Source Metadata: MP3 320kbps, belowRequest = true because Lossless was requested
        assertEquals("mp3", stream.format.codec)
        assertEquals(320, stream.format.kbps)
        assertEquals(false, stream.format.isLossless)
        assertTrue(stream.belowRequest)
        assertEquals("555", stream.headers[DeezerCipher.HEADER_TRACK_ID])
    }

    // ─── Blowfish CBC 2048-Byte Stride Decryption & Seek Tests ───────────────────

    @Test
    fun `decrypts 2048-byte stride chunks and leaves trailing partial chunk untouched`() {
        val trackId = "3135556"
        val key = DeezerCipher.deriveKey(trackId)
        assertEquals(16, key.size)

        // 7 chunks of 2048 bytes + 519 trailing bytes (chunks 0, 3, 6 must be encrypted)
        val totalSize = 7 * DeezerCipher.CHUNK_SIZE + 519
        val originalPlain = ByteArray(totalSize).also { Random(42).nextBytes(it) }

        val wireEncrypted = originalPlain.copyOf()
        for (chunkIdx in 0 until 7) {
            if (DeezerCipher.isEncryptedChunk(chunkIdx.toLong(), DeezerCipher.CHUNK_SIZE)) {
                DeezerCipher.encryptChunkInPlace(
                    key = key,
                    buffer = wireEncrypted,
                    offset = chunkIdx * DeezerCipher.CHUNK_SIZE,
                )
            }
        }
        // Verify chunks 0, 3, 6 were actually mutated while 1, 2, 4, 5 and trailing 519 bytes were not
        assertFalse(
            originalPlain.copyOfRange(0, 2048).contentEquals(wireEncrypted.copyOfRange(0, 2048)),
        )
        assertArrayEquals(
            originalPlain.copyOfRange(2048, 4096),
            wireEncrypted.copyOfRange(2048, 4096),
        )
        assertFalse(
            originalPlain.copyOfRange(3 * 2048, 4 * 2048)
                .contentEquals(wireEncrypted.copyOfRange(3 * 2048, 4 * 2048)),
        )
        assertArrayEquals(
            originalPlain.copyOfRange(7 * 2048, totalSize),
            wireEncrypted.copyOfRange(7 * 2048, totalSize),
        )

        val decrypted = DeezerCipher.decryptStream(wireEncrypted, trackId)
        assertArrayEquals(originalPlain, decrypted)
    }

    @Test
    fun `DeezerDataSource handles partial network reads, arbitrary mid-chunk seeks, and bounded lengths`() {
        val trackId = "987654321"
        val key = DeezerCipher.deriveKey(trackId)

        // 10 full chunks + 333 trailing bytes = 20,813 bytes
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
            write(byteArrayOf(0x00, 0x00, 0x00, 0x3D)) // Java 17
            write(byteArrayOf(0x00, 0x05)) // constant_pool_count = 5
            write(byteArrayOf(0x07, 0x00, 0x02)) // #1 = Class(#2)
            write(byteArrayOf(0x01, 0x00, thisName.size.toByte()))
            write(thisName) // #2 = Utf8
            write(byteArrayOf(0x07, 0x00, 0x04)) // #3 = Class(#4)
            write(byteArrayOf(0x01, 0x00, superName.size.toByte()))
            write(superName) // #4 = Utf8
            write(byteArrayOf(0x00, 0x21)) // ACC_PUBLIC | ACC_SUPER (not abstract)
            write(byteArrayOf(0x00, 0x01)) // this_class = #1
            write(byteArrayOf(0x00, 0x03)) // super_class = #3
            write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)) // 0 interfaces/fields/methods/attrs
        }.toByteArray()
        val dummyUriClass = object : ClassLoader(Uri::class.java.classLoader) {
            fun load(): Class<*> = defineClass("com.music.bitchord.DummyUri", dummyUriClassBytes, 0, dummyUriClassBytes.size)
        }.load()
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        val dummyUri = allocateInstance.invoke(unsafe, dummyUriClass) as Uri

        var lastOpenedSpec: DataSpec? = null
        // Fake upstream that drips at most 137 bytes per read() to stress partial chunk assembly
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

        // Case 1: Mid-encrypted-chunk seek (start inside chunk 3 at offset 3*2048 + 511, length = 5000)
        val seekStart = 3 * DeezerCipher.CHUNK_SIZE + 511
        val seekLength = 5000
        val spec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(seekStart.toLong())
            .setLength(seekLength.toLong())
            .setHttpRequestHeaders(
                mapOf(
                    DeezerCipher.HEADER_TRACK_ID to trackId,
                    "User-Agent" to "BitChordTest",
                ),
            )
            .build()

        val openedLen = dataSource.open(spec)
        assertEquals(seekLength.toLong(), openedLen)
        // Verify internal X-Deezer-Track-Id header was stripped before hitting upstream HTTP
        val openedSpec = lastOpenedSpec
        assertNotNull(openedSpec)
        assertFalse(openedSpec!!.httpRequestHeaders.containsKey(DeezerCipher.HEADER_TRACK_ID))
        assertEquals("BitChordTest", openedSpec.httpRequestHeaders["User-Agent"])
        // Verify upstream position was aligned down to chunk 3 boundary (3 * 2048 = 6144)
        assertEquals((3 * DeezerCipher.CHUNK_SIZE).toLong(), openedSpec.position)

        // Read in small 61-byte caller buffers
        val out = ByteArrayOutputStream()
        val callerBuf = ByteArray(61)
        while (true) {
            val r = dataSource.read(callerBuf, 0, callerBuf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            out.write(callerBuf, 0, r)
        }
        dataSource.close()

        val received = out.toByteArray()
        assertEquals(seekLength, received.size)
        assertArrayEquals(
            plaintext.copyOfRange(seekStart, seekStart + seekLength),
            received,
        )

        // Case 2: Seek into chunk 9 through the unencrypted trailing 333-byte EOF chunk
        val tailStart = 9 * DeezerCipher.CHUNK_SIZE + 1900
        val tailSpec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(tailStart.toLong())
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(mapOf(DeezerCipher.HEADER_TRACK_ID to trackId))
            .build()

        val tailOpened = dataSource.open(tailSpec)
        assertEquals((totalSize - tailStart).toLong(), tailOpened)
        val tailOut = ByteArrayOutputStream()
        while (true) {
            val r = dataSource.read(callerBuf, 0, callerBuf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            tailOut.write(callerBuf, 0, r)
        }
        dataSource.close()
        assertArrayEquals(
            plaintext.copyOfRange(tailStart, totalSize),
            tailOut.toByteArray(),
        )

        // Case 3: Unencrypted passthrough when X-Deezer-Track-Id is absent
        val passthroughSpec = DataSpec.Builder()
            .setUri(dummyUri)
            .setPosition(123L)
            .setLength(456L)
            .build()
        dataSource.open(passthroughSpec)
        assertEquals(123L, lastOpenedSpec?.position)
        val passOut = ByteArrayOutputStream()
        while (true) {
            val r = dataSource.read(callerBuf, 0, callerBuf.size)
            if (r == C.RESULT_END_OF_INPUT) break
            passOut.write(callerBuf, 0, r)
        }
        dataSource.close()
        assertArrayEquals(
            ciphertext.copyOfRange(123, 123 + 456),
            passOut.toByteArray(),
        )
    }
}
