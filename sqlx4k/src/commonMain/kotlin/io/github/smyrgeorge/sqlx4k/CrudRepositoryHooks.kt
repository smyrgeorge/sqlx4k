package io.github.smyrgeorge.sqlx4k

/**
 * Interface providing hooks for CRUD operations in a repository.
 *
 * The `CrudRepositoryHooks` interface allows the implementation of hooks that can be invoked
 * before the execution of CRUD (Create, Read, Update, Delete) operations. These hooks provide
 * a mechanism to preprocess or modify entities before they are persisted, updated, or deleted
 * in the data source.
 *
 * @param T The type of the entity that the repository manages.
 */
interface CrudRepositoryHooks<T> {
    /**
     * This method provides a hook that is invoked before an entity is inserted into the data source.
     * It allows for preprocessing or modifications to the entity prior to the insertion.
     *
     * @param entity The entity of type [T] that is about to be inserted.
     * @return The processed or unmodified entity of type [T].
     */
    suspend fun preInsertHook(entity: T): T = entity

    /**
     * Provides a hook that is invoked before an entity is updated in the data source.
     * This method allows for preprocessing or modifications to the entity prior to the update operation.
     *
     * @param entity The entity of type [T] that is about to be updated.
     * @return The processed or unmodified entity of type [T].
     */
    suspend fun preUpdateHook(entity: T): T = entity

    /**
     * This method provides a hook that is invoked before an entity is deleted from the data source.
     * It allows for preprocessing or modifications to the entity prior to the deletion operation.
     *
     * @param entity The entity of type [T] that is about to be deleted.
     * @return The processed or unmodified entity of type [T].
     */
    suspend fun preDeleteHook(entity: T): T = entity

    /**
     * This method provides an around-style hook that wraps query execution.
     * It allows for cross-cutting concerns like metrics, tracing, and logging
     * to be applied to all database operations.
     *
     * The hook receives the method name and statement (containing SQL and bound parameters)
     * and wraps the actual database execution block.
     *
     * @param method The name of the repository method being executed (e.g., "findOneById", "insert")
     * @param statement The statement containing the SQL query and bound parameters
     * @param block The actual database operation to execute
     * @return The result of executing the block
     */
    suspend fun <R> aroundQuery(method: String, statement: Statement, block: suspend () -> R): R = block()
}
