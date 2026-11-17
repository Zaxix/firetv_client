package com.auroratv.jellyfin

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * OkHttp with room for a screen's worth of parallel requests to one server (OkHttp's default is
 * 5 per host, which queued Home's row and poster requests behind each other) and connections kept
 * warm between screens.
 */
actual fun platformHttpEngine(maxRequestsPerHost: Int): HttpClientEngine? = OkHttp.create {
    val perHost = maxRequestsPerHost
    config {
        dispatcher(okhttp3.Dispatcher().also { d ->
            d.maxRequests = 64
            d.maxRequestsPerHost = perHost
        })
        connectionPool(okhttp3.ConnectionPool(perHost, 5, TimeUnit.MINUTES))
        retryOnConnectionFailure(true)
    }
}
