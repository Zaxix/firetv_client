package com.auroratv.discmenu

import androidx.compose.ui.graphics.ImageBitmap
import com.auroratv.core.AuroraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** One animated overlay (a button's sparkle): where it sits on the canvas and where each WebP frame lies in the data file. */
@Serializable
data class SpriteSequence(
    val x: Int, val y: Int, val w: Int, val h: Int,
    val frames: Int, val fps: Double,
    val offsets: List<Int>, val lengths: List<Int>,
)

/**
 * The sparkle frames. About 1,400 small WebP images sit in one file; only the ones about to be shown are decoded, on a
 * background thread, and a small number are kept. All state is touched from the main thread only.
 */
class SpriteBank(private val assets: DiscMenuAssets, private val sparkles: PackSparkles, private val capacity: Int = 96) {
    private var index: Map<String, SpriteSequence> = emptyMap()
    private var data: ByteArray? = null
    private val cache = LinkedHashMap<Long, ImageBitmap>()
    private val inFlight = HashSet<Long>()

    fun sequence(name: String): SpriteSequence? = index[name]

    /** Reads the index and the data file (about 5 MB) off the main thread. Throws when either is missing or malformed. */
    suspend fun load() {
        val (idx, bytes) = withContext(Dispatchers.Default) {
            val parsed = AuroraJson.decodeFromString(MapSerializer(String.serializer(), SpriteSequence.serializer()), assets.readText(sparkles.index))
            for ((name, seq) in parsed) {
                require(seq.offsets.size == seq.frames && seq.lengths.size == seq.frames) { "sprite '$name' has inconsistent frame tables" }
            }
            parsed to assets.readBytes(sparkles.data)
        }
        index = idx
        data = bytes
    }

    private fun key(name: String, frame: Int): Long = (name.hashCode().toLong() shl 20) xor frame.toLong()

    /** The decoded frame if it is ready, otherwise null (the caller keeps showing its previous frame). */
    fun peek(name: String, frame: Int): ImageBitmap? = cache[key(name, frame)]?.also { cache.remove(key(name, frame)); cache[key(name, frame)] = it }

    /** Decodes [frame] (and caches it) unless it is cached or already being decoded. */
    suspend fun ensure(name: String, frame: Int) {
        val seq = index[name] ?: return
        val f = ((frame % seq.frames) + seq.frames) % seq.frames
        val k = key(name, f)
        if (cache.containsKey(k) || !inFlight.add(k)) return
        val bytes = data ?: run { inFlight.remove(k); return }
        try {
            val bmp = withContext(Dispatchers.Default) { assets.decode(bytes, seq.offsets[f], seq.lengths[f]) }
            if (bmp != null) {
                cache[k] = bmp
                while (cache.size > capacity) cache.remove(cache.keys.first())
            }
        } finally {
            inFlight.remove(k)
        }
    }

    fun clear() { cache.clear(); inFlight.clear(); data = null }
}
