package io.github.smyrgeorge.sqlx4k

/**
 * Represents a driver interface that extends various query execution and management capabilities.
 *
 * The `Driver` interface combines functionalities provided by the `QueryExecutor`,
 * `QueryExecutor.Pool`, `QueryExecutor.Transactional`, and `QueryExecutor.Migrate` interfaces.
 * It provides a unified contract for executing SQL statements, managing connection pools,
 * handling transactions, and executing database migrations.
 *
 * Implementing this interface allows a class to offer comprehensive database interaction
 * and management capabilities, including query execution, connection pooling, transactional
 * operations, and migration mechanisms.
 */
interface Driver : ConnectionPool, QueryExecutor, QueryExecutor.Transactional, QueryExecutor.Migrate