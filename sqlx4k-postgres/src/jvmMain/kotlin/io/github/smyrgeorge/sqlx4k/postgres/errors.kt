package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.SQLError
import io.r2dbc.spi.R2dbcException

/**
 * Converts a failure raised by the R2DBC driver into an [SQLError], carrying over what the PostgreSQL server
 * reported: the SQLSTATE and a portable [SQLError.Kind] derived from it. PostgreSQL has no numeric error code,
 * so [SQLError.nativeCode] is always `null`: the SQLSTATE is the code.
 *
 * The [SQLError.Kind] mapping mirrors sqlx's `PgDatabaseError::kind`, so the JVM and native drivers agree.
 *
 * @param code the sqlx4k code to report; defaults to [SQLError.Code.Database].
 */
internal fun Throwable.toSQLError(code: SQLError.Code = SQLError.Code.Database): SQLError {
    val r2dbc = generateSequence(this) { it.cause }.filterIsInstance<R2dbcException>().firstOrNull()
    val sqlState = r2dbc?.sqlState
    return SQLError(
        code = code,
        message = message,
        cause = this,
        sqlState = sqlState,
        nativeCode = null,
        kind = postgresKindOf(sqlState),
    )
}

/**
 * Classifies a PostgreSQL error as a portable [SQLError.Kind] from its SQLSTATE. Mirrors the Rust mapping of the
 * native driver (whose constraint violations come from sqlx), so both platforms agree.
 *
 * https://www.postgresql.org/docs/current/errcodes-appendix.html
 */
internal fun postgresKindOf(sqlState: String?): SQLError.Kind = when (sqlState) {
    // unique_violation, foreign_key_violation, not_null_violation, check_violation, exclusion_violation
    "23505" -> SQLError.Kind.UniqueViolation
    "23503" -> SQLError.Kind.ForeignKeyViolation
    "23502" -> SQLError.Kind.NotNullViolation
    "23514" -> SQLError.Kind.CheckViolation
    "23P01" -> SQLError.Kind.ExclusionViolation
    // deadlock_detected, serialization_failure, lock_not_available
    "40P01" -> SQLError.Kind.Deadlock
    "40001" -> SQLError.Kind.SerializationFailure
    "55P03" -> SQLError.Kind.LockTimeout
    else -> SQLError.Kind.Other
}
