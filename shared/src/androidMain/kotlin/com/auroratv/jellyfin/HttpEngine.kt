package com.auroratv.jellyfin

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One OkHttp connection pool for the whole app: API calls, artwork and the video stream itself all
 * reuse the same warm connections to the server. Pressing Play (or resuming) then skips the TCP and
 * TLS set-up the player would otherwise do on its own, which matters most for remote HTTPS servers
 * and for MKV resumes, which open several byte ranges in a row.
 */
object SharedOkHttp {
    val dispatcher = Dispatcher().also { it.maxRequests = 64 }
    val pool = ConnectionPool(16, 5, TimeUnit.MINUTES)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(pool)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Raises the per-server limit for parallel API/artwork requests (synchronous video reads aren't counted). */
    fun allowPerHost(n: Int) { if (n > dispatcher.maxRequestsPerHost) dispatcher.maxRequestsPerHost = n }
}

/**
 * OkHttp with room for a screen's worth of parallel requests to one server (OkHttp's default is
 * 5 per host, which queued Home's row and poster requests behind each other) and connections kept
 * warm between screens.
 */
actual fun platformHttpEngine(maxRequestsPerHost: Int): HttpClientEngine? = OkHttp.create {
    SharedOkHttp.allowPerHost(maxRequestsPerHost)
    preconfigured = SharedOkHttp.client
    // Set again explicitly so the engine can't swap in its own (5-per-host) dispatcher or a separate pool.
    config {
        dispatcher(SharedOkHttp.dispatcher)
        connectionPool(SharedOkHttp.pool)
        retryOnConnectionFailure(true)
    }
}
