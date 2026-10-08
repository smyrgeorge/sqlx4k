package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.SQLError

/**
 * Classifies a SQLite error as a portable [SQLError.Kind] from its extended result code.
 *
 * The primary result code is the low byte of the extended code, so every extended variant of a family
 * (e.g. `SQLITE_BUSY_SNAPSHOT`) is classified like the family. Mirrors the Rust mapping of the native driver,
 * so every platform driver agrees.
 *
 * https://www.sqlite.org/rescode.html
 */
internal fun sqliteKindOf(extendedCode: Int?): SQLError.Kind = when (extendedCode) {
    null -> SQLError.Kind.Other
    // SQLITE_CONSTRAINT_UNIQUE, SQLITE_CONSTRAINT_PRIMARYKEY
    2067, 1555 -> SQLError.Kind.UniqueViolation
    // SQLITE_CONSTRAINT_FOREIGNKEY
    787 -> SQLError.Kind.ForeignKeyViolation
    // SQLITE_CONSTRAINT_NOTNULL
    1299 -> SQLError.Kind.NotNullViolation
    // SQLITE_CONSTRAINT_CHECK
    275 -> SQLError.Kind.CheckViolation
    else -> when (extendedCode and 0xff) {
        // SQLITE_BUSY, SQLITE_LOCKED (and their extended variants)
        5, 6 -> SQLError.Kind.LockTimeout
        else -> SQLError.Kind.Other
    }
}
