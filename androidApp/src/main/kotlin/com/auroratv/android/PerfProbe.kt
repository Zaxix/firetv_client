package com.auroratv.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * CI-only (`--ez perfTrace true`): finds where the main (UI) thread is blocked. A heartbeat is
 * posted to the main thread every 16 ms; a background thread looks every 8 ms and, while the
 * heartbeat is late by more than [STALL_MS], samples the main thread's stack. After [durationMs] it
 * logs how long the main thread was blocked and the stacks it was most often blocked in (app and
 * framework frames that say where the time went). Costs nothing unless switched on.
 */
object PerfProbe {
    private const val STALL_MS = 48L
    private const val TAG = "Perf"

    fun start(durationMs: Long = 12_000) {
        val main = Handler(Looper.getMainLooper())
        val lastBeat = java.util.concurrent.atomic.AtomicLong(SystemClock.uptimeMillis())
        val beat = object : Runnable {
            override fun run() { lastBeat.set(SystemClock.uptimeMillis()); main.postDelayed(this, 16) }
        }
        main.post(beat)
        Thread({
            val start = SystemClock.uptimeMillis()
            val mainThread = Looper.getMainLooper().thread
            val samples = HashMap<String, Int>()
            var blockedMs = 0L
            var stalls = 0
            var inStall = false
            var longest = 0L
            var stallStart = 0L
            while (SystemClock.uptimeMillis() - start < durationMs) {
                SystemClock.sleep(8)
                val late = SystemClock.uptimeMillis() - lastBeat.get()
                if (late > STALL_MS) {
                    if (!inStall) { inStall = true; stalls++; stallStart = lastBeat.get() }
                    blockedMs += 8
                    val key = describe(mainThread.stackTrace)
                    samples[key] = (samples[key] ?: 0) + 1
                } else if (inStall) {
                    inStall = false
                    longest = maxOf(longest, SystemClock.uptimeMillis() - stallStart)
                }
            }
            main.removeCallbacks(beat)
            android.util.Log.i(TAG, "Main thread blocked ${blockedMs} ms in $stalls stall(s) over ${STALL_MS} ms (longest ${longest} ms) in the first ${durationMs / 1000} s")
            samples.entries.sortedByDescending { it.value }.take(12).forEach { (stack, n) ->
                android.util.Log.i(TAG, "  ~${n * 8} ms in: $stack")
            }
        }, "perf-probe").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
    }

    /** The frames that say where the time went: the first few of the app, Compose, Coil, I/O or class loading. */
    private fun describe(stack: Array<StackTraceElement>): String {
        val interesting = stack.asSequence().filter { f ->
            val c = f.className
            c.startsWith("com.auroratv") || c.startsWith("androidx.compose.ui.text") || c.startsWith("coil") ||
                c.startsWith("kotlinx.serialization") || c.startsWith("io.ktor") || c.startsWith("dalvik.system") ||
                c.startsWith("java.io") || c.startsWith("android.graphics") || c.contains("ClassLoader") || c.contains("Typeface") ||
                c.startsWith("androidx.compose.runtime.Recomposer") || c.contains("measure", true) || c.startsWith("android.app.ActivityThread")
        }.take(4).map { "${it.className.substringAfterLast('.')}.${it.methodName}" }.toList()
        return if (interesting.isEmpty()) stack.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        else interesting.joinToString(" < ")
    }
}
