package io.github.smyrgeorge.sqlx4k

/**
 * Interface defining a contract for basic CRUD (Create, Read, Update, Delete) operations on a data source.
 *
 * This interface abstracts common operations to be implemented for handling entities of type [T].
 * Each operation is asynchronous and returns a [Result], encapsulating either the successful result
 * or an error in case of failure.
 *
 * @param T The type of the entity managed by the repository.
 */
interface ContextCrudRepository<T> : CrudRepositoryHooks<T> {
    /**
     * Inserts the given entity into the data source using the specified driver context.
     *
     * This method performs an asynchronous insert operation and returns the result.
     * If the operation is successful, the result will contain the inserted entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be inserted into the data source.
     * @return A [Result] containing the inserted entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun insert(entity: T): Result<T>

    /**
     * Updates the given entity in the data source using the specified driver context.
     *
     * This method performs an asynchronous update operation and returns the result.
     * If the operation is successful, the result will contain the updated entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be updated in the data source.
     * @return A [Result] containing the updated entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun update(entity: T): Result<T>

    /**
     * Deletes the given entity from the data source using the specified driver context.
     *
     * This method performs an asynchronous delete operation and returns the result.
     * If the operation is successful, the result will contain a successful unit value.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be deleted from the data source.
     * @return A [Result] containing a [Unit] value if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun delete(entity: T): Result<Unit>

    /**
     * Saves the given entity to the data source using the specified driver context.
     *
     * This method determines whether to perform an insert or update operation based on the entity's state.
     * If the operation is successful, the result will contain the saved entity.
     * In case of failure, the result contains the error details.
     *
     * @param entity The entity of type [T] to be saved in the data source.
     * @return A [Result] containing the saved entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun save(entity: T): Result<T>

    /**
     * Inserts multiple entities into the data source using the specified driver context.
     *
     * This method performs an asynchronous batch insert operation and returns the result.
     * If the operation is successful, the result will contain the list of inserted entities.
     * In case of failure, the result contains the error details.
     *
     * Note: Batch insert is not supported for MySQL dialect (no multi-row INSERT with RETURNING).
     *
     * @param entities The collection of entities of type [T] to be inserted into the data source.
     * @return A [Result] containing the list of inserted entities of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun batchInsert(entities: Iterable<T>): Result<List<T>>

    /**
     * Updates multiple entities in the data source using the specified driver context.
     *
     * This method performs an asynchronous batch update operation and returns the result.
     * If the operation is successful, the result will contain the list of updated entities.
     * In case of failure, the result contains the error details.
     *
     * Note: Batch update is not supported for SQLite dialect (no FROM VALUES / ON DUPLICATE KEY support).
     *
     * @param entities The collection of entities of type [T] to be updated in the data source.
     * @return A [Result] containing the list of updated entities of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    context(context: QueryExecutor)
    suspend fun batchUpdate(entities: Iterable<T>): Result<List<T>>
}
