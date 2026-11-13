package com.auroratv.core

import kotlinx.serialization.json.Json

/** Lenient JSON shared by the Jellyfin client and local persistence. */
val AuroraJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
}

/** Generates a random lowercase hex id (used for device id and play-session bookkeeping). */
fun randomHexId(bytes: Int = 16): String {
    val chars = "0123456789abcdef"
    return buildString(bytes * 2) {
        repeat(bytes * 2) { append(chars[kotlin.random.Random.nextInt(16)]) }
    }
}
