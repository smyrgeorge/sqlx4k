package io.github.smyrgeorge.sqlx4k.impl.extensions

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * Type alias for database results using Arrow's Either type.
 * Left side contains SQLError for failures, right side contains the success value.
 */
typealias DbResult<T> = Either<SQLError, T>

/**
 * Converts a Kotlin Result to Either<SQLError, T>.
 *
 * If the Result contains a SQLError, it's used as-is.
 * Otherwise, the exception is wrapped in a SQLError with UknownError code.
 *
 * @return Either.Right with the success value, or Either.Left with SQLError
 */
fun <T> Result<T>.toDbResult(): DbResult<T> =
    fold(onSuccess = { it.right() }, onFailure = { it.left() })
        .mapLeft { error ->
            when (error) {
                is SQLError -> error
                else -> SQLError(SQLError.Code.UknownError, error.message)
            }
        }
