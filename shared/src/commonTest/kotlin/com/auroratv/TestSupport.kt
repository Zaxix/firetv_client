package com.auroratv

import com.auroratv.jellyfin.ClientIdentity
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.createJellyfinHttpClient
import com.auroratv.platform.FileStore
import com.auroratv.platform.SecureVault
import com.auroratv.playback.Codecs
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.HdrFormat
import com.auroratv.playback.VideoCodecSupport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

class MemoryFileStore : FileStore {
    val data = mutableMapOf<String, String>()
    override fun read(name: String) = data[name]
    override fun write(name: String, content: String) { data[name] = content }
    override fun delete(name: String) { data.remove(name) }
}

class MemoryVault : SecureVault {
    val data = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { data[key] = value }
    override fun get(key: String) = data[key]
    override fun remove(key: String) { data.remove(key) }
    override val description = "memory"
}

/** Records requests and answers them with [handler]. */
class FakeServer(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
    val requests = mutableListOf<HttpRequestData>()
    val client: HttpClient = createJellyfinHttpClient(HttpClient(MockEngine { req -> requests += req; handler(req) }))
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

val testIdentity = ClientIdentity(deviceName = "Test TV", deviceId = "dev123")

fun api(server: FakeServer, token: String? = "tok", userId: String? = "u1") =
    JellyfinApi(server.client, "http://jf.local:8096", testIdentity, token, userId)

/** A typical Fire TV 4K: hardware H.264/HEVC Main10/VP9/AV1, HDR10 display, AC3/E-AC3 passthrough. */
val fireTv4k = DeviceCapabilities(
    engineName = "test",
    video = listOf(
        VideoCodecSupport(Codecs.H264, true, 3840, 2160, 8, listOf("high", "main", "baseline", "constrained baseline")),
        VideoCodecSupport(Codecs.HEVC, true, 3840, 2160, 10, listOf("main", "main 10")),
        VideoCodecSupport(Codecs.VP9, true, 3840, 2160, 10),
        VideoCodecSupport(Codecs.AV1, true, 3840, 2160, 10),
    ),
    hdr = setOf(HdrFormat.HDR10, HdrFormat.HLG),
    canToneMapHdr = false,
    audioDecode = setOf("aac", "mp3", "flac", "opus"),
    audioPassthrough = setOf("ac3", "eac3"),
    maxAudioChannels = 6,
    containers = setOf("mkv", "mp4"),
    subtitleEmbedded = setOf("ass", "ssa", "srt", "subrip", "pgssub"),
    subtitleExternal = setOf("ass", "ssa", "srt", "subrip", "vtt"),
    libassRendering = true,
    supportsSubtitleDelay = false,
)

/** An older 1080p stick: H.264 only. */
val oldStick = fireTv4k.copy(
    video = listOf(VideoCodecSupport(Codecs.H264, true, 1920, 1080, 8, listOf("high", "main"))),
    hdr = emptySet(),
    audioPassthrough = emptySet(),
    maxAudioChannels = 2,
)
