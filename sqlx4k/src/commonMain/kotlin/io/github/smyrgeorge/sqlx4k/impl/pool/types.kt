package io.github.smyrgeorge.sqlx4k.impl.pool

import io.github.smyrgeorge.sqlx4k.Connection

/**
 * Represents a factory function for creating a new database connection.
 *
 * This typealias is used to define a suspendable function that, when invoked, provides an instance
 * of a `Connection`. It encapsulates the logic for initializing a new connection, typically managed
 * by a connection pool or other connection management infrastructure.
 *
 * Common use cases include lazy initialization of connections or management of connection lifecycle
 * within a pooling mechanism, such as `ConnectionPoolImpl`.
 *
 * @see Connection
 * @see ConnectionPoolImpl
 */
typealias ConnectionFactory = suspend () -> Connection