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
interface ArrowCrudRepository<T> : CrudRepositoryHooks<T> {
    /**
     * Inserts the given entity into the data source using the specified driver context.
     *
     * This method performs an asynchronous insert operation and returns the result.
     * If the operation is successful, the result will contain the inserted entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the insert operation.
     * @param entity The entity of type [T] to be inserted into the data source.
     * @return A [DbResult] containing the inserted entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun insert(context: QueryExecutor, entity: T): DbResult<T>

    /**
     * Updates the given entity in the data source using the specified driver context.
     *
     * This method performs an asynchronous update operation and returns the result.
     * If the operation is successful, the result will contain the updated entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the update operation.
     * @param entity The entity of type [T] to be updated in the data source.
     * @return A [DbResult] containing the updated entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun update(context: QueryExecutor, entity: T): DbResult<T>

    /**
     * Deletes the given entity from the data source using the specified driver context.
     *
     * This method performs an asynchronous delete operation and returns the result.
     * If the operation is successful, the result will contain a successful unit value.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the delete operation.
     * @param entity The entity of type [T] to be deleted from the data source.
     * @return A [DbResult] containing a [Unit] value if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun delete(context: QueryExecutor, entity: T): DbResult<Unit>

    /**
     * Saves the given entity to the data source using the specified driver context.
     *
     * This method determines whether to perform an insert or update operation based on the entity's state.
     * If the operation is successful, the result will contain the saved entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the save operation.
     * @param entity The entity of type [T] to be saved in the data source.
     * @return A [DbResult] containing the saved entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun save(context: QueryExecutor, entity: T): DbResult<T>

    /**
     * Inserts multiple entities into the data source using the specified driver context.
     *
     * This method performs an asynchronous batch insert operation and returns the result.
     * If the operation is successful, the result will contain the list of inserted entities.
     * In case of failure, the result contains the error details.
     *
     * Note: Batch insert is not supported for MySQL dialect (no multi-row INSERT with RETURNING).
     *
     * @param context The database driver context used to execute the batch insert operation.
     * @param entities The collection of entities of type [T] to be inserted into the data source.
     * @return A [DbResult] containing the list of inserted entities of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun batchInsert(context: QueryExecutor, entities: Iterable<T>): DbResult<List<T>>

    /**
     * Updates multiple entities in the data source using the specified driver context.
     *
     * This method performs an asynchronous batch update operation and returns the result.
     * If the operation is successful, the result will contain the list of updated entities.
     * In case of failure, the result contains the error details.
     *
     * Note: Batch update is not supported for SQLite dialect (no FROM VALUES / ON DUPLICATE KEY support).
     *
     * @param context The database driver context used to execute the batch update operation.
     * @param entities The collection of entities of type [T] to be updated in the data source.
     * @return A [DbResult] containing the list of updated entities of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun batchUpdate(context: QueryExecutor, entities: Iterable<T>): DbResult<List<T>>
}
