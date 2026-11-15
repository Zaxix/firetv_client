package com.auroratv.android

import android.content.Context
import android.media.MediaCodec
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.auroratv.core.Log
import com.auroratv.playback.Codecs
import com.auroratv.playback.DolbyVisionCodecs
import com.auroratv.playback.DolbyVisionSupport
import com.auroratv.playback.DoviRoute
import com.auroratv.playback.HevcBitstream
import com.suyashbelekar.exoplayerhdrutils.libdovi.LibDovi
import java.nio.Buffer
import java.nio.ByteBuffer

private const val TAG = "DolbyVision"

/**
 * What the video renderer is doing with the current track. Written on the playback thread when
 * the track's format arrives (before the decoder is created), read by the codec adapter factory on
 * the same thread and by the stats overlay.
 */
class DolbyVisionSession(val support: DolbyVisionSupport) {
    @Volatile var route: DoviRoute? = null
        private set
    @Volatile var sourceCodecs: String? = null
        private set
    @Volatile var convertedUnits = 0L
    @Volatile var maskedHdr10Plus = 0L
    @Volatile var refusedUnits = 0L

    /** The format the decoder should see for [format]; records the decision when [commit]. */
    @OptIn(UnstableApi::class)
    fun route(format: Format, commit: Boolean): Format {
        val codecs = format.codecs
        val profile = if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) DolbyVisionCodecs.profile(codecs) else null
        if (profile == null || codecs == null) {
            if (commit) { route = null; sourceCodecs = null }
            return format
        }
        var decision = support.routeFor(profile)
        // Encrypted samples take the secure path, which the bitstream filter can't touch.
        if (decision == DoviRoute.ConvertToProfile8 && format.drmInitData != null) decision = DoviRoute.BaseLayer
        val routed = when (decision) {
            DoviRoute.Native, DoviRoute.Unsupported -> format
            DoviRoute.ConvertToProfile8 -> format.buildUpon().setCodecs(DolbyVisionCodecs.withProfile(codecs, 8)).build()
            DoviRoute.BaseLayer -> {
                val mime = when (DolbyVisionCodecs.baseCodec(codecs)) {
                    Codecs.H264 -> MimeTypes.VIDEO_H264
                    Codecs.AV1 -> MimeTypes.VIDEO_AV1
                    else -> MimeTypes.VIDEO_H265
                }
                format.buildUpon().setSampleMimeType(mime).setCodecs(null).build()
            }
        }
        if (commit && (route != decision || sourceCodecs != codecs)) {
            route = decision; sourceCodecs = codecs
            Log.i(TAG, "Dolby Vision $codecs → $decision" + (if (routed !== format) " (decoder sees ${routed.sampleMimeType} ${routed.codecs ?: ""})" else ""))
        }
        return routed
    }

    /** Short description for the player's stats panel. */
    fun describe(): String? {
        val r = route ?: return null
        val src = sourceCodecs ?: ""
        return when (r) {
            DoviRoute.Native -> "Dolby Vision ($src)" + if (maskedHdr10Plus > 0) ", HDR10+ masked" else ""
            DoviRoute.ConvertToProfile8 -> "Dolby Vision, profile 7 → 8.1 ($convertedUnits frames converted" + (if (refusedUnits > 0) ", $refusedUnits left as-is" else "") + ")"
            DoviRoute.BaseLayer -> "Base layer of $src (no Dolby Vision path on this device/display)"
            DoviRoute.Unsupported -> "Dolby Vision $src (not declared by the decoder)"
        }
    }
}

/** Video renderer that hands each Dolby Vision track to the decoder chosen by [DolbyVisionSession]. */
@OptIn(UnstableApi::class)
private class DolbyVisionVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    private val session: DolbyVisionSession,
) : MediaCodecVideoRenderer(builder) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, session.route(format, commit = false))

    override fun getDecoderInfos(mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean): List<MediaCodecInfo> =
        super.getDecoderInfos(mediaCodecSelector, session.route(format, commit = false), requiresSecureDecoder)

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        formatHolder.format?.let { formatHolder.format = session.route(it, commit = true) }
        return super.onInputFormatChanged(formatHolder)
    }
}

/**
 * [DefaultRenderersFactory] whose MediaCodec video renderer routes Dolby Vision tracks (see
 * [DoviRoute]) and filters their bitstream where the route needs it. Everything else (extension
 * renderers, audio, text) is built exactly as by the default factory.
 */
@OptIn(UnstableApi::class)
class DolbyVisionRenderersFactory(context: Context, private val support: DolbyVisionSupport) : DefaultRenderersFactory(context) {
    @Volatile var session: DolbyVisionSession? = null
        private set

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val built = ArrayList<Renderer>()
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, built)
        val index = built.indexOfFirst { it.javaClass == MediaCodecVideoRenderer::class.java }
        if (index >= 0) {
            val s = DolbyVisionSession(support).also { session = it }
            val builder = MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(BitstreamFilteringAdapterFactory(getCodecAdapterFactory(), s))
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            built[index] = DolbyVisionVideoRenderer(builder, s)
        }
        out.addAll(built)
    }
}

/** Wraps video codecs with an [AccessUnitFilter] when the current Dolby Vision route needs one. */
@OptIn(UnstableApi::class)
private class BitstreamFilteringAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
    private val session: DolbyVisionSession,
) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = delegate.createAdapter(configuration)
        val route = session.route ?: return adapter
        val dvDecoder = configuration.codecInfo.mimeType == MimeTypes.VIDEO_DOLBY_VISION
        val profile = DolbyVisionCodecs.profile(session.sourceCodecs) ?: 0
        val filter = AccessUnitFilter(
            session = session,
            maskHdr10Plus = dvDecoder && session.support.masksHdr10Plus && route != DoviRoute.BaseLayer,
            // Profile 7 enhancement layer: dropped for the conversion (8.1 is single layer) and for
            // base-layer playback (HEVC decoders should ignore it, but not every one does).
            dropEnhancementLayer = profile == 7 && route != DoviRoute.Native,
            convertToProfile8 = route == DoviRoute.ConvertToProfile8 && dvDecoder,
        )
        if (!filter.active) return adapter
        Log.i(TAG, "Filtering ${configuration.codecInfo.name}: $filter")
        return FilteringCodecAdapter(adapter, filter)
    }
}

@OptIn(UnstableApi::class)
private class FilteringCodecAdapter(delegate: MediaCodecAdapter, private val filter: AccessUnitFilter) : ForwardingMediaCodecAdapter(delegate) {
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        var queued = size
        if (size > 0 && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
            getInputBuffer(index)?.let { queued = filter.apply(it, offset, size) }
        }
        super.queueInputBuffer(index, offset, queued, presentationTimeUs, flags)
    }
}

/**
 * Edits one access unit on its way into the decoder: HDR10+ SEI masking, enhancement-layer
 * removal and the libdovi RPU rewrite. The access unit is copied once into a reusable heap array,
 * edited there and written back only when something changed, so untouched frames cost one bulk
 * copy and a scan. Any failure leaves the access unit exactly as it arrived.
 */
private class AccessUnitFilter(
    private val session: DolbyVisionSession,
    private val maskHdr10Plus: Boolean,
    private val dropEnhancementLayer: Boolean,
    convertToProfile8: Boolean,
) {
    private var converter: LibDovi? = if (convertToProfile8) LibDoviLoader.create() else null
    val active get() = maskHdr10Plus || dropEnhancementLayer || converter != null

    private var staging = ByteArray(INITIAL_CAPACITY)
    private var scratch: ByteBuffer? = null

    fun apply(data: ByteBuffer, offset: Int, size: Int): Int {
        if (size < 5) return size
        if (staging.size < size) staging = ByteArray(size + size / 4)
        val a = staging
        window(data, offset, size).get(a, 0, size)
        if (!HevcBitstream.startsWithStartCode(a, 0, size)) return size
        var length = size
        var changed = false
        if (maskHdr10Plus) {
            val n = HevcBitstream.maskHdr10PlusSei(a, length)
            if (n > 0) { changed = true; session.maskedHdr10Plus += n }
        }
        val conv = converter
        if (conv != null && HevcBitstream.containsNalType(a, length, HevcBitstream.NAL_DOVI_RPU)) {
            val withoutEl = HevcBitstream.removeNalType(a, length, HevcBitstream.NAL_DOVI_EL)
            val converted = convert(conv, a, withoutEl)
            if (converted > 0) {
                length = converted; changed = true; session.convertedUnits++
            } else if (withoutEl != length) {
                // Rewrite refused: keep the EL out anyway (a P8 decoder can't use it) but keep the RPU.
                length = withoutEl; changed = true
            }
        } else if (dropEnhancementLayer) {
            val n = HevcBitstream.removeNalType(a, length, HevcBitstream.NAL_DOVI_EL)
            if (n != length) { length = n; changed = true }
        }
        if (changed) window(data, offset, length).put(a, 0, length)
        return length
    }

    /** Runs libdovi's 7 → 8.1 rewrite on `a[0, length)`; returns the new length, or 0 to leave it. */
    private fun convert(conv: LibDovi, a: ByteArray, length: Int): Int {
        val buf = scratch?.takeIf { it.capacity() >= length + RPU_HEADROOM }
            ?: ByteBuffer.allocateDirect(maxOf(length + RPU_HEADROOM, INITIAL_CAPACITY)).also { scratch = it }
        (buf as Buffer).clear()
        buf.put(a, 0, length)
        return try {
            val profile = conv.getFrameInfo(buf, length)?.doviProfile ?: 0
            if (profile != 7) return 0
            val out = conv.processHevcFrame(buf, length, CONVERT_TO_PROFILE_8, false)
            if (out <= 0 || out > a.size) { refuse("libdovi returned $out for $length bytes"); return 0 }
            (buf as Buffer).clear()
            buf.get(a, 0, out)
            out
        } catch (t: Throwable) {
            refuse(t.toString()); 0
        }
    }

    private fun refuse(reason: String) {
        if (session.refusedUnits == 0L) Log.w(TAG, "Profile 7 frame left unconverted: $reason")
        session.refusedUnits++
    }

    override fun toString() = buildList {
        if (maskHdr10Plus) add("HDR10+ masking")
        if (converter != null) add("profile 7 → 8.1 (libdovi)") else if (dropEnhancementLayer) add("drop enhancement layer")
    }.joinToString()

    private companion object {
        const val INITIAL_CAPACITY = 512 * 1024
        const val RPU_HEADROOM = 4 * 1024
        /** `LibDovi.processHevcFrame` transform: rewrite the RPU to profile 8.1. */
        const val CONVERT_TO_PROFILE_8 = 1

        /** A view of `[offset, offset + length)` with its own position/limit (Buffer casts: API < 34). */
        fun window(data: ByteBuffer, offset: Int, length: Int): ByteBuffer {
            val view = data.duplicate()
            (view as Buffer).clear()
            (view as Buffer).position(offset)
            (view as Buffer).limit(offset + length)
            return view
        }
    }
}

/** libdovi (via ExoplayerHdrUtils, MIT) ships for arm64-v8a, armeabi-v7a and x86_64 only. */
object LibDoviLoader {
    val available: Boolean by lazy { create() != null }

    fun create(): LibDovi? = try {
        LibDovi()
    } catch (t: Throwable) {
        Log.w(TAG, "libdovi unavailable on this ABI; profile 7 plays as its HDR10 base layer (${t.javaClass.simpleName})")
        null
    }
}
