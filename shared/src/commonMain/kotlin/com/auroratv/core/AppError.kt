package com.auroratv.core

/**
 * Errors the UI knows how to explain. Each carries a short, human message suitable for a TV screen.
 */
sealed class AppError(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    class Unreachable(cause: Throwable? = null) :
        AppError("Can't reach the server. Check the address and that the server is running.", cause)

    class Timeout(cause: Throwable? = null) :
        AppError("The server took too long to respond. Your connection may be slow.", cause)

    class Offline(cause: Throwable? = null) :
        AppError("You appear to be offline. Check your network connection.", cause)

    class InvalidCredentials :
        AppError("That username or password wasn't accepted.")

    class SessionExpired :
        AppError("Your session has expired. Please sign in again.")

    class NotJellyfin :
        AppError("That address responded, but it doesn't look like a Jellyfin server.")

    class NotFound :
        AppError("That item no longer exists on the server.")

    class Server(val status: Int) :
        AppError("The server returned an error ($status). Try again in a moment.")

    class BadData(cause: Throwable? = null) :
        AppError("The server sent data Aurora couldn't read.", cause)

    class Playback(message: String, cause: Throwable? = null) : AppError(message, cause)

    class Unknown(cause: Throwable? = null) :
        AppError("Something went wrong. Please try again.", cause)
}

/** Converts any throwable into an [AppError], keeping it if it already is one. */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    else -> {
        val name = this::class.simpleName.orEmpty()
        when {
            name.contains("Timeout", ignoreCase = true) -> AppError.Timeout(this)
            name.contains("UnknownHost") || name.contains("UnresolvedAddress") -> AppError.Unreachable(this)
            name.contains("Connect") || name.contains("NoRouteToHost") || name.contains("Socket") -> AppError.Unreachable(this)
            name.contains("Serialization") || name.contains("JsonDecoding") -> AppError.BadData(this)
            name.contains("SSL", ignoreCase = true) -> AppError.Unreachable(this)
            else -> AppError.Unknown(this)
        }
    }
}

/** Runs [block], converting failures to [AppError] but letting coroutine cancellation propagate. */
suspend inline fun <T> appResult(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (c: kotlin.coroutines.cancellation.CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t.toAppError())
}
