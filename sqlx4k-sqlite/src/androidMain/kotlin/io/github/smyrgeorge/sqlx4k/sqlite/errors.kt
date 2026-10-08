package io.github.smyrgeorge.sqlx4k.sqlite

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * Converts a failure raised by the Android platform SQLite driver into an [SQLError].
 *
 * `android.database.sqlite` exceptions carry no result code as a field: the framework only formats it into the
 * message, as `... (code 2067 SQLITE_CONSTRAINT_UNIQUE)`. The extended result code is recovered from there when
 * present, so [SQLError.nativeCode] is best-effort on Android. The portable [SQLError.Kind] falls back to the
 * constraint named in the message when the code is missing. SQLite has no SQLSTATE, so [SQLError.sqlState] is
 * always `null`.
 *
 * @param code the sqlx4k code to report; defaults to [SQLError.Code.Database].
 */
internal fun Throwable.toSQLError(code: SQLError.Code = SQLError.Code.Database): SQLError {
    val sqlite = generateSequence(this) { it.cause }.filterIsInstance<SQLiteException>().firstOrNull()
    val nativeCode = sqlite?.message?.let { RESULT_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    return SQLError(
        code = code,
        message = message,
        cause = this,
        sqlState = null,
        nativeCode = nativeCode,
        kind = kindOf(nativeCode, sqlite),
    )
}

private val RESULT_CODE = Regex("""\(code (\d+)""")

private fun kindOf(nativeCode: Int?, e: SQLiteException?): SQLError.Kind {
    val kind = sqliteKindOf(nativeCode)
    if (kind != SQLError.Kind.Other || e !is SQLiteConstraintException) return kind
    // The framework did not include the result code: classify by the constraint named in the message.
    val message = e.message ?: return SQLError.Kind.Other
    return when {
        message.startsWith("UNIQUE constraint failed") -> SQLError.Kind.UniqueViolation
        message.startsWith("PRIMARY KEY constraint failed") -> SQLError.Kind.UniqueViolation
        message.startsWith("FOREIGN KEY constraint failed") -> SQLError.Kind.ForeignKeyViolation
        message.startsWith("NOT NULL constraint failed") -> SQLError.Kind.NotNullViolation
        message.startsWith("CHECK constraint failed") -> SQLError.Kind.CheckViolation
        else -> SQLError.Kind.Other
    }
}
