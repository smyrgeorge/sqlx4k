package io.github.smyrgeorge.sqlx4k

import kotlin.time.Duration

/**
 * Interface representing a connection pool for managing and reusing database connections.
 *
 * A `ConnectionPool` is responsible for managing the lifecycle of a set of database connections,
 * enabling efficient reuse and minimizing the overhead of establishing new connections. It provides
 * methods for acquiring and releasing connections, managing pool size, and configuring the pool behavior.
 */
interface ConnectionPool {
    /**
     * Retrieves the current size of the connection pool.
     *
     * @return the number of connections currently in the pool
     */
    fun poolSize(): Int

    /**
     * Retrieves the number of idle connections in the connection pool.
     *
     * @return the number of idle connections in the pool
     */
    fun poolIdleSize(): Int

    /**
     * Acquires a connection from the connection pool.
     *
     * This method suspends until a connection becomes available in the pool.
     * If the pool is closed or an error occurs during the acquisition, the
     * returned `Result` will contain the error details.
     *
     * @return A `Result` containing a `Connection` if the acquisition is successful,
     *         or an error if the operation fails.
     */
    suspend fun acquire(): Result<Connection>

    /**
     * Closes the connection pool, releasing all resources.
     *
     * @return A Result object indicating the success or failure of the operation.
     */
    suspend fun close(): Result<Unit>

    /**
     * Class representing configuration options for a connection pool.
     *
     * @property minConnections The minimum number of connections to maintain at all times.
     * @property maxConnections The maximum number of connections that this pool should maintain.
     * @property acquireTimeout The maximum amount of time to spend waiting for a connection.
     * @property idleTimeout The maximum idle duration for individual connections.
     * @property maxLifetime The maximum lifetime of individual connections.
     */
    data class Options(
        // Set the minimum number of connections to maintain at all times.
        val minConnections: Int? = null,
        // Set the maximum number of connections that this pool should maintain.
        val maxConnections: Int = 10,
        // Set the maximum amount of time to spend waiting for a connection.
        val acquireTimeout: Duration? = null,
        // Set a maximum idle duration for individual connections.
        val idleTimeout: Duration? = null,
        // Set the maximum lifetime of individual connections.
        val maxLifetime: Duration? = null,
    ) {
        init {
            require(minConnections == null || minConnections > 0) { "minConnections must be greater than 0" }
            require(maxConnections > 0) { "maxConnections must be greater than 0" }
            require(idleTimeout == null || idleTimeout.isPositive()) { "idleTimeout must be greater than 0" }
            require(maxLifetime == null || maxLifetime.isPositive()) { "maxLifetime must be greater than 0" }
            require(acquireTimeout == null || acquireTimeout.isPositive()) { "acquireTimeout must be greater than 0" }
            require(minConnections == null || maxConnections >= minConnections) { "maxConnections must be greater than or equal to minConnections" }
            require(idleTimeout == null || maxLifetime == null || idleTimeout <= maxLifetime) { "idleTimeout must be less than or equal to maxLifetime" }
        }

        class Builder {
            private var minConnections: Int? = null
            private var maxConnections: Int = 10
            private var acquireTimeout: Duration? = null
            private var idleTimeout: Duration? = null
            private var maxLifetime: Duration? = null

            fun minConnections(minConnections: Int) = apply { this.minConnections = minConnections }
            fun maxConnections(maxConnections: Int) = apply { this.maxConnections = maxConnections }
            fun acquireTimeout(acquireTimeout: Duration) = apply { this.acquireTimeout = acquireTimeout }
            fun idleTimeout(idleTimeout: Duration) = apply { this.idleTimeout = idleTimeout }
            fun maxLifetime(maxLifetime: Duration) = apply { this.maxLifetime = maxLifetime }
            fun build() = Options(minConnections, maxConnections, acquireTimeout, idleTimeout, maxLifetime)
        }

        companion object {
            fun builder(): Builder = Builder()
        }
    }
}