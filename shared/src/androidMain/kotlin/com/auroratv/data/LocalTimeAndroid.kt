package com.auroratv.data

actual fun localUtcOffsetMs(epochMs: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMs).toLong()
