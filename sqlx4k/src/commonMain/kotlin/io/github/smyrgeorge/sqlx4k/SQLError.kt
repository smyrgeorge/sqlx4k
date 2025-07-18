package io.github.smyrgeorge.sqlx4k

/**
 * Represents an error that occurs while interacting with a database.
 *
 * @property code The specific error code associated with this database error.
 * @param message An optional message providing more details about the error.
 */
class SQLError(
    val code: Code,
    message: String? = null,
) : RuntimeException("[$code] :: $message") {
    /**
     * Throws the current instance of [SQLError].
     *
     * This method is used to propagate the current [SQLError] instance as an
     * exception. It is typically utilized within other methods to handle or
     * signal specific error conditions associated with database operations.
     *
     * @return Nothing, since this method always throws an exception.
     * @throws SQLError This method always throws the current instance of [SQLError].
     */
    fun ex(): Nothing = throw this

    /**
     * Represents various error codes that can occur while interacting with a database or performing related operations.
     */
    enum class Code {
        // IMPORTANT: Do not change the order the errors.
        // Error from the underlying driver:
        Database,
        PoolTimedOut,
        PoolClosed,
        WorkerCrashed,
        Migrate,

        Pool,

        // Transaction
        TransactionIsClosed,

        // Decode
        CannotDecode,
        CannotDecodeEnumValue,

        // Prepared Statement:
        PositionalParameterOutOfBounds,
        NamedParameterNotFound,

        // Other errors:
        NamedParameterTypeNotSupported,
        PositionalParameterValueNotSupplied,
        NamedParameterValueNotSupplied,
    }
}
