package com.auroratv.discmenu

import kotlin.math.abs
import kotlin.math.min

enum class Dir { Up, Down, Left, Right }

/** One focusable region on a menu screen. */
data class NavItem(val id: String, val rect: Rect)

/** Where the 1920x1080 reference canvas sits inside a window (letterboxed, never stretched). */
data class CanvasFit(val scale: Float, val offsetX: Float, val offsetY: Float)

object MenuGeometry {
    fun fit(windowW: Float, windowH: Float, canvas: PackCanvas): CanvasFit {
        require(windowW > 0f && windowH > 0f) { "window must have a size" }
        val scale = min(windowW / canvas.w, windowH / canvas.h)
        return CanvasFit(scale, (windowW - canvas.w * scale) / 2f, (windowH - canvas.h * scale) / 2f)
    }

    /** Window pixel to canvas pixel; `null` inside the letterbox bars. */
    fun windowToCanvas(px: Float, py: Float, windowW: Float, windowH: Float, canvas: PackCanvas): Pair<Float, Float>? {
        val f = fit(windowW, windowH, canvas)
        val x = (px - f.offsetX) / f.scale
        val y = (py - f.offsetY) / f.scale
        return if (x < 0f || y < 0f || x >= canvas.w || y >= canvas.h) null else x to y
    }

    /**
     * The item to move to from [from] when [dir] is pressed: the nearest centre inside a cone around that direction.
     * ASSUMED navigation rule: the disc's own button order is not recoverable from a screen recording.
     * Items for which [enabled] is false are skipped.
     */
    fun neighbour(from: String, items: List<NavItem>, dir: Dir, enabled: (String) -> Boolean = { true }): String? {
        val cur = items.firstOrNull { it.id == from } ?: return null
        var best: String? = null
        var bestScore = Float.MAX_VALUE
        for (it in items) {
            if (it.id == from || !enabled(it.id)) continue
            val dx = it.rect.centerX - cur.rect.centerX
            val dy = it.rect.centerY - cur.rect.centerY
            val along = when (dir) { Dir.Up -> -dy; Dir.Down -> dy; Dir.Left -> -dx; Dir.Right -> dx }
            val across = if (dir == Dir.Up || dir == Dir.Down) abs(dx) else abs(dy)
            if (along <= 4f) continue
            if (across > along * 2.2f + 80f) continue
            val score = along + across * 1.5f
            if (score < bestScore) { bestScore = score; best = it.id }
        }
        return best
    }

    /** Frame index in a looping sequence of [frames] frames at [fps], [elapsedMs] after its start. */
    fun loopIndex(elapsedMs: Long, fps: Double, frames: Int): Int {
        require(frames > 0) { "empty sprite sequence" }
        return ((maxOf(0L, elapsedMs) * fps / 1000.0).toLong() % frames).toInt()
    }

    /** Same, but holds on the last frame (play once). */
    fun holdIndex(elapsedMs: Long, fps: Double, frames: Int): Int {
        require(frames > 0) { "empty sprite sequence" }
        return min((frames - 1).toLong(), (maxOf(0L, elapsedMs) * fps / 1000.0).toLong()).toInt()
    }
}
