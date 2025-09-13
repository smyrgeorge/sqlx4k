package io.github.smyrgeorge.sqlx4k

/**
 * Represents a database connection that supports executing queries and transactional operations.
 *
 * The `Connection` interface extends the `QueryExecutor` and `QueryExecutor.Transactional` interfaces,
 * providing functionality for executing queries and managing transactions. Additionally, it manages
 * the lifecycle state of the connection through its `status` property and provides methods for
 * validating and releasing the connection.
 */
interface Connection : QueryExecutor, QueryExecutor.Transactional {
    val status: Status

    /**
     * Validates whether the connection is in an acquired state or throws an error.
     *
     * This method ensures that the connection is not in the released state. If the connection
     * has already been released, an [SQLError] with the error code [SQLError.Code.ConnectionIsReleased]
     * is thrown to signal that the operation cannot proceed with a released connection.
     *
     * @throws SQLError If the connection has already been released.
     */
    fun assertIsAcquired() {
        if (status == Status.Released) {
            SQLError(SQLError.Code.ConnectionIsReleased, "Connection has already been released.").ex()
        }
    }

    /**
     * Releases the current connection, making it available for reuse or closing it if appropriate.
     *
     * This method transitions the connection into a released state, marking it as no longer in use.
     * Releasing a connection ensures proper resource management and avoids potential resource leaks.
     * The operation may fail if the connection is already in a released state or if there is an issue
     * during the release process.
     *
     * @return A [Result] indicating the success or failure of the release operation.
     *         On success, the result contains [Unit]. On failure, it contains an error describing
     *         the issue encountered while releasing the connection.
     */
    suspend fun release(): Result<Unit>

    /**
     * Represents the status of a database connection.
     *
     * The `Status` enum indicates the current lifecycle state of a database connection.
     *
     * - `Acquired`: The connection is currently in use or reserved.
     * - `Released`: The connection has been released and is no longer in use.
     *
     * This enum is typically used to track and enforce proper connection management within
     * the system, preventing operations on released connections.
     */
    enum class Status {
        Acquired,
        Released,
    }
}
