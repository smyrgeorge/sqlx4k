package io.github.smyrgeorge.sqlx4k

/**
 * Represents an error that occurs while interacting with a database.
 *
 * Every failure carries a sqlx4k [Code]. When the failure was reported by the database itself
 * ([Code.Database]), the driver also carries over what the database said about it:
 *
 * - [sqlState]: the portable SQLSTATE, for databases that have one (PostgreSQL, MySQL).
 * - [nativeCode]: the database's own numeric error code, for databases that have one (MySQL, SQLite).
 * - [kind]: a portable classification of the failure (unique violation, foreign key violation, ...)
 *   that works the same on every database.
 *
 * @property code The sqlx4k error code: what went wrong from the library's point of view ([Code.Database],
 *   [Code.PoolTimedOut], [Code.ConnectionIsClosed], ...). Always set.
 * @property sqlState The five-character SQLSTATE reported by the database, as defined by the SQL standard.
 *   Set on PostgreSQL (e.g. `23505` for a unique violation) and MySQL (e.g. `23000` for any integrity constraint
 *   violation). Always `null` on SQLite, which has no SQLSTATE, and for errors that did not come from the database.
 * @property nativeCode The database's own numeric error code. Set on MySQL (the server error number, e.g. `1062`
 *   for `ER_DUP_ENTRY`) and SQLite (the extended result code, e.g. `2067` for `SQLITE_CONSTRAINT_UNIQUE`).
 *   Always `null` on PostgreSQL, which has no numeric code (its SQLSTATE is the code), and for errors that did not
 *   come from the database. On the Android platform SQLite driver it is recovered from the exception message and
 *   is therefore best-effort.
 * @property kind The portable classification of the error, derived from [sqlState] or [nativeCode] with the same
 *   mapping on every database and platform. [Kind.Other] when the error is not mapped or did not come from the
 *   database.
 * @param message An optional message providing more details about the error.
 * @param cause An optional underlying cause (on the JVM, the original driver exception).
 */
class SQLError(
    val code: Code,
    message: String? = null,
    cause: Throwable? = null,
    val sqlState: String? = null,
    val nativeCode: Int? = null,
    val kind: Kind = Kind.Other,
) : RuntimeException(if (message != null) "[$code] :: $message" else "[$code]", cause) {
    /**
     * Throws the current instance of [SQLError].
     *
     * This method is used to propagate the current [SQLError] instance as an
     * exception. It is typically used within other methods to handle or
     * signal specific error conditions associated with database operations.
     *
     * @return Nothing, since this method always throws an exception.
     * @throws SQLError This method always throws the current instance of [SQLError].
     */
    fun raise(): Nothing = throw this

    /**
     * Represents various error codes that can occur while interacting with a database or performing related operations.
     */
    enum class Code {
        // IMPORTANT: Do not change the order of the errors.
        // Error from the underlying driver:
        Database,
        PoolTimedOut,
        PoolClosed,
        WorkerCrashed,
        Migrate,

        Pool,

        // Connection
        ConnectionIsClosed,

        // Transaction
        TransactionIsClosed,
        TransactionCommitFailed,
        TransactionRollbackFailed,

        // Decode
        CannotDecode,
        CannotDecodeEnumValue,

        // Prepared Statement:
        PositionalParameterOutOfBounds,
        NamedParameterNotFound,

        // Other errors:
        EmptyResultSet,
        MultipleRowsReturned,
        RowMismatch,
        MissingValueConverter,
        PositionalParameterValueNotSupplied,
        NamedParameterValueNotSupplied,
        InvalidIdentifier,
        UnsafeStringContent,
        EmptyCollection,
        OptimisticLockFailed,
        UnknownError,
    }

    /**
     * Portable classification of a database error, independent of the underlying driver.
     *
     * Covers what application code branches on: integrity constraint violations (report to the user) and
     * concurrency failures (retry the transaction). Everything else is [Other]; use [SQLError.sqlState] and
     * [SQLError.nativeCode] for it. Constraint violations are classified by the driver itself; concurrency
     * failures are derived from the SQLSTATE on PostgreSQL, the error number on MySQL and the result code
     * on SQLite, with the same mapping on the native and the JVM drivers.
     */
    enum class Kind {
        // IMPORTANT: Do not change the order of the kinds.
        // The underlying FFI layer maps kinds by their ordinal.

        // Integrity constraint violations (SQLSTATE class 23).
        /** A unique or primary key constraint was violated. */
        UniqueViolation,
        /** A foreign key constraint was violated. */
        ForeignKeyViolation,
        /** A not-null constraint was violated. */
        NotNullViolation,
        /** A check constraint was violated. */
        CheckViolation,
        /** An exclusion constraint was violated (PostgreSQL only). */
        ExclusionViolation,

        // Concurrency failures: the transaction can usually be retried.
        /** The transaction was chosen as a deadlock victim (PostgreSQL `40P01`, MySQL `1213`). */
        Deadlock,
        /** The transaction could not be serialized with concurrent ones (PostgreSQL `40001`). */
        SerializationFailure,
        /** A lock could not be acquired in time (PostgreSQL `55P03`, MySQL `1205`, SQLite busy or locked). */
        LockTimeout,

        /** Not a database error, or a database error that is not mapped. */
        Other,
    }
}
