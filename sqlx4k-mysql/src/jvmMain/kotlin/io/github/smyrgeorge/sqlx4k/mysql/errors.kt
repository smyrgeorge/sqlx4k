package io.github.smyrgeorge.sqlx4k.mysql

import io.github.smyrgeorge.sqlx4k.SQLError
import io.r2dbc.spi.R2dbcException

/**
 * Converts a failure raised by the R2DBC driver into an [SQLError], carrying over what the MySQL server
 * reported: the SQLSTATE, the error number and a portable [SQLError.Kind] derived from the error number.
 *
 * The [SQLError.Kind] mapping mirrors sqlx's `MySqlDatabaseError::kind`, so the JVM and native drivers agree.
 *
 * @param code the sqlx4k code to report; defaults to [SQLError.Code.Database].
 */
internal fun Throwable.toSQLError(code: SQLError.Code = SQLError.Code.Database): SQLError {
    val r2dbc = generateSequence(this) { it.cause }.filterIsInstance<R2dbcException>().firstOrNull()
    // R2DBC reports 0 when the failure did not come from the server (I/O, protocol, ...).
    val number = r2dbc?.errorCode?.takeIf { it != 0 }
    val sqlState = r2dbc?.sqlState
    return SQLError(
        code = code,
        message = message,
        cause = this,
        sqlState = sqlState,
        nativeCode = number,
        kind = mysqlKindOf(number, sqlState),
    )
}

/**
 * Classifies a MySQL error as a portable [SQLError.Kind] from its error number. Mirrors the Rust mapping of the
 * native driver (whose constraint violations come from sqlx), so both platforms agree.
 *
 * https://dev.mysql.com/doc/mysql-errors/8.0/en/server-error-reference.html
 */
internal fun mysqlKindOf(number: Int?, sqlState: String?): SQLError.Kind = when (number) {
    // ER_DUP_KEY, ER_DUP_ENTRY, ER_DUP_UNIQUE, ER_DUP_ENTRY_WITH_KEY_NAME, ER_DUP_UNKNOWN_IN_INDEX
    1022, 1062, 1169, 1586, 1859 -> SQLError.Kind.UniqueViolation
    // ER_NO_REFERENCED_ROW, ER_ROW_IS_REFERENCED, ER_ROW_IS_REFERENCED_2, ER_NO_REFERENCED_ROW_2,
    // ER_FK_COLUMN_NOT_NULL, ER_FK_CANNOT_DELETE_PARENT
    1216, 1217, 1451, 1452, 1830, 1834 -> SQLError.Kind.ForeignKeyViolation
    // ER_BAD_NULL_ERROR, ER_NO_DEFAULT_FOR_FIELD
    1048, 1364 -> SQLError.Kind.NotNullViolation
    // ER_CHECK_CONSTRAINT_VIOLATED
    3819 -> SQLError.Kind.CheckViolation
    // MariaDB ER_CONSTRAINT_FAILED. MySQL reuses this number for an unrelated error (a different SQLSTATE).
    4025 -> if (sqlState == "23000") SQLError.Kind.CheckViolation else SQLError.Kind.Other
    // ER_LOCK_DEADLOCK
    1213 -> SQLError.Kind.Deadlock
    // ER_LOCK_WAIT_TIMEOUT
    1205 -> SQLError.Kind.LockTimeout
    else -> SQLError.Kind.Other
}
