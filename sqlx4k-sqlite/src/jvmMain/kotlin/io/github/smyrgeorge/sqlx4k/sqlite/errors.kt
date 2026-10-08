package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.SQLError
import org.sqlite.SQLiteException

/**
 * Converts a failure raised by the JDBC driver into an [SQLError], carrying over what SQLite reported:
 * the extended result code and a portable [SQLError.Kind] derived from it. SQLite has no SQLSTATE, so
 * [SQLError.sqlState] is always `null`.
 *
 * The extended result code (e.g. `2067` SQLITE_CONSTRAINT_UNIQUE) is read from `SQLiteException.resultCode`,
 * which is what the native driver reports as well. `SQLException.errorCode` only holds the primary code (`19`).
 *
 * @param code the sqlx4k code to report; defaults to [SQLError.Code.Database].
 */
internal fun Throwable.toSQLError(code: SQLError.Code = SQLError.Code.Database): SQLError {
    val sqlite = generateSequence(this) { it.cause }.filterIsInstance<SQLiteException>().firstOrNull()
    val nativeCode = sqlite?.resultCode?.code?.takeIf { it > 0 }
    return SQLError(
        code = code,
        message = message,
        cause = this,
        sqlState = null,
        nativeCode = nativeCode,
        kind = sqliteKindOf(nativeCode),
    )
}
