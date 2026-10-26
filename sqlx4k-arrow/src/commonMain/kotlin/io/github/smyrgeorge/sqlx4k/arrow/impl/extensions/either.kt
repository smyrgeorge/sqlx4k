package io.github.smyrgeorge.sqlx4k.arrow.impl.extensions

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Type alias for database results using Arrow's Either type.
 * Left side contains SQLError for failures, right side contains the success value.
 */
typealias DbResult<T> = Either<SQLError, T>

/**
 * Converts a Kotlin Result to Either<SQLError, T>.
 *
 * If the Result contains a SQLError, it's used as-is.
 * Otherwise, the exception is wrapped (as the cause) in a SQLError with UnknownError code.
 *
 * A cancellation is not a database error and is rethrown, like Arrow's own `Either.catch` does: as a `Left`, the
 * caller's retry or fallback logic would carry on in a coroutine that was cancelled (or timed out).
 *
 * @return Either.Right with the success value, or Either.Left with SQLError
 * @throws CancellationException if the Result failed with one
 */
fun <T> Result<T>.toDbResult(): DbResult<T> = fold(
    onSuccess = { it.right() },
    onFailure = { error ->
        when (error) {
            is CancellationException -> throw error
            is SQLError -> error.left()
            else -> SQLError(SQLError.Code.UnknownError, error.message, error).left()
        }
    }
)
