package io.github.smyrgeorge.sqlx4k

/**
 * Represents a database connection capable of executing queries and managing transactions.
 *
 * The `Connection` interface defines operations for interacting with a database
 * connection, managing its lifecycle, and ensuring that it operates in a valid state.
 * This includes functionality for executing queries, transactional operations, and
 * managing the connection's open/closed state.
 */
interface Connection : QueryExecutor, QueryExecutor.Transactional {
    val status: Status

    /**
     * Ensures that the connection is currently in an open state.
     *
     * This method checks the `status` of the connection and verifies if it is set to `Status.Open`.
     * If the connection is not open, it throws an `SQLError` with the code `ConnectionIsOpen`,
     * indicating that the connection has been closed and is no longer available for use.
     *
     * @throws SQLError If the connection is not in the open state.
     */
    fun assertIsOpen() {
        if (status != Status.Open) {
            SQLError(SQLError.Code.ConnectionIsOpen, "Connection has already been closed.").ex()
        }
    }

    /**
     * Closes the connection and releases any associated resources.
     *
     * This method transitions the connection's status to `Closed` and ensures that
     * no further operations can be performed on it. If the connection is already
     * closed, invoking this method will result in an appropriate error.
     *
     * @return A [Result] indicating the success or failure of the close operation.
     */
    suspend fun close(): Result<Unit>

    /**
     * Retrieves the `ValueEncoderRegistry` associated with the current connection.
     * The `ValueEncoderRegistry` provides access to registered value encoders, which
     * are responsible for converting types into formats suitable for use in SQL statements.
     *
     * @return A `ValueEncoderRegistry` instance that manages the encoders for this connection.
     */
    fun encoders(): Statement.ValueEncoderRegistry = Statement.ValueEncoderRegistry.EMPTY

    /**
     * Represents the operational state of a connection.
     *
     * The `Status` enum provides the possible states in which a database connection
     * can reside. It is used to indicate whether a connection is currently open
     * and available for use, or if it has been closed and is no longer usable.
     *
     * This enum is typically utilized in connection management logic to enforce
     * safe and logical state transitions, such as ensuring that operations are not
     * performed on a closed connection.
     */
    enum class Status {
        Open,
        Closed,
    }
}
