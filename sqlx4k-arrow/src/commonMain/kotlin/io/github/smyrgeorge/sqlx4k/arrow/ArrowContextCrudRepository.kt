package io.github.smyrgeorge.sqlx4k.arrow

import io.github.smyrgeorge.sqlx4k.CrudRepositoryHooks
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.DbResult

/**
 * Interface defining a contract for basic CRUD (Create, Read, Update, Delete) operations on a data source.
 *
 * This interface abstracts common operations to be implemented for handling entities of type [T].
 * Each operation is asynchronous and returns a [DbResult], encapsulating either the successful result
 * or an error in case of failure.
 *
 * @param T The type of the entity managed by the repository.
 */
interface ArrowContextCrudRepository<T> : CrudRepositoryHooks<T> {
    /**
     * Inserts the given entity into the data source using the specified driver context.
     *
     * This method performs an asynchronous insert operation and returns the result.
     * If the operation is successful, the result will contain the inserted entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be inserted into the data source.
     * @return A [DbResult] containing the inserted entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun insert(entity: T): DbResult<T>

    /**
     * Updates the given entity in the data source using the specified driver context.
     *
     * This method performs an asynchronous update operation and returns the result.
     * If the operation is successful, the result will contain the updated entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be updated in the data source.
     * @return A [DbResult] containing the updated entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun update(entity: T): DbResult<T>

    /**
     * Deletes the given entity from the data source using the specified driver context.
     *
     * This method performs an asynchronous delete operation and returns the result.
     * If the operation is successful, the result will contain a successful unit value.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be delÏeted from the data source.
     * @return A [DbResult] containing a [Unit] value if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun delete(entity: T): DbResult<Unit>

    /**
     * Saves the given entity to the data source using the specified driver context.
     *
     * This method determines whether to perform an insert or update operation based on the entity's state.
     * If the operation is successful, the result will contain the saved entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be saved in the data source.
     * @return A [DbResult] containing the saved entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun save(entity: T): DbResult<T>
}
