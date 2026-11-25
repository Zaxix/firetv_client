package com.auroratv.core

/**
 * Which version of the performance-audit changes runs (CI only, `--ei perfLevel 0|1|2`), so the
 * same build can time the code before the audit against the code now, alternating rounds on one
 * emulator (runner speed varies too much between CI runs to compare across them). Normal use is
 * always [CURRENT].
 */
object PerfTuning {
    const val CURRENT = 2
    @kotlin.concurrent.Volatile var level: Int = CURRENT

    /** The audit's changes are on. */
    val on: Boolean get() = level >= 1

    /**
     * The second pass (Nov 2026): fewer repeated requests (several titles prepared ahead, a title's
     * details kept briefly, no guess while preparing in the background, the update check after
     * Home's first requests). Level 1 = the build before it.
     */
    val pass2: Boolean get() = level >= 2
}
