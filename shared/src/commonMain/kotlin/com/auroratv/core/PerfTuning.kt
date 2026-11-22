package com.auroratv.core

/**
 * Which version of the performance-audit changes runs (CI only, `--ei perfLevel 0|1`), so the
 * same build can time the code before the audit against the code now, alternating rounds on one
 * emulator (runner speed varies too much between CI runs to compare across them). Normal use is
 * always [CURRENT].
 */
object PerfTuning {
    const val CURRENT = 1
    @kotlin.concurrent.Volatile var level: Int = CURRENT

    /** The audit's changes are on. */
    val on: Boolean get() = level >= 1
}
