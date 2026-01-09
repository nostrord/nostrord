package org.nostr.nostrord.utils

/**
 * Sealed class representing the result of an operation.
 * Provides type-safe error handling without exceptions.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error<T>(val error: AppError) : Result<T>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }

    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Error -> error
    }

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> Error(error)
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (AppError) -> Unit): Result<T> {
        if (this is Error) action(error)
        return this
    }
}

/**
 * Sealed class representing application errors.
 * Provides structured error types for different failure scenarios.
 */
sealed class AppError(
    open val message: String,
    open val cause: Throwable? = null
) {
    /** Network-related errors */
    sealed class Network(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause) {
        data class ConnectionFailed(
            val relayUrl: String,
            override val cause: Throwable? = null
        ) : Network("Failed to connect to relay: $relayUrl", cause)

        data class Timeout(
            val operation: String,
            override val cause: Throwable? = null
        ) : Network("Operation timed out: $operation", cause)

        data class Disconnected(
            val relayUrl: String
        ) : Network("Disconnected from relay: $relayUrl")
    }

    /** Authentication errors */
    sealed class Auth(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause) {
        data class SigningFailed(
            override val cause: Throwable? = null
        ) : Auth("Failed to sign event", cause)

        data class BunkerError(
            override val message: String,
            override val cause: Throwable? = null
        ) : Auth(message, cause)

        data object NotAuthenticated : Auth("User not authenticated")
    }

    /** Group operation errors */
    sealed class Group(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause) {
        data class JoinFailed(
            val groupId: String,
            override val cause: Throwable? = null
        ) : Group("Failed to join group: $groupId", cause)

        data class LeaveFailed(
            val groupId: String,
            override val cause: Throwable? = null
        ) : Group("Failed to leave group: $groupId", cause)

        data class SendFailed(
            val groupId: String,
            override val cause: Throwable? = null
        ) : Group("Failed to send message to group: $groupId", cause)

        data class MessageRejected(
            val groupId: String,
            val reason: String
        ) : Group("Message rejected by relay: $reason (group: $groupId)")

        data class SendTimeout(
            val groupId: String
        ) : Group("Timed out waiting for relay confirmation (group: $groupId)")
    }

    /** Generic/unknown errors */
    data class Unknown(
        override val message: String,
        override val cause: Throwable? = null
    ) : AppError(message, cause)
}

/**
 * Execute a block and wrap the result in Result.Success or Result.Error
 */
inline fun <T> runCatching(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Throwable) {
        Result.Error(AppError.Unknown(e.message ?: "Unknown error", e))
    }
}

/**
 * Execute a suspending block and wrap the result in Result.Success or Result.Error
 */
suspend inline fun <T> runCatchingSuspend(block: () -> T): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Throwable) {
        Result.Error(AppError.Unknown(e.message ?: "Unknown error", e))
    }
}
