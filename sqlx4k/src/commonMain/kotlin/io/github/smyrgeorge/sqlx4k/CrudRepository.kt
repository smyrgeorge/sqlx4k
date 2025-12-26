package io.github.smyrgeorge.sqlx4k

/**
 * Defines a repository interface for performing CRUD operations on entities of type [T].
 *
 * This interface provides methods for inserting, updating, deleting, and saving entities
 * within a data source using an asynchronous approach.
 *
 * @param T The type of the entities handled by this repository.
 */
interface CrudRepository<T> {
    /**
     * Inserts the given entity into the data source using the specified driver context.
     *
     * This method performs an asynchronous insert operation and returns the result.
     * If the operation is successful, the result will contain the inserted entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the insert operation.
     * @param entity The entity of type [T] to be inserted into the data source.
     * @return A [Result] containing the inserted entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun insert(context: QueryExecutor, entity: T): Result<T>

    /**
     * Updates the given entity in the data source using the specified driver context.
     *
     * This method performs an asynchronous update operation and returns the result.
     * If the operation is successful, the result will contain the updated entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the update operation.
     * @param entity The entity of type [T] to be updated in the data source.
     * @return A [Result] containing the updated entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun update(context: QueryExecutor, entity: T): Result<T>

    /**
     * Deletes the given entity from the data source using the specified driver context.
     *
     * This method performs an asynchronous delete operation and returns the result.
     * If the operation is successful, the result will contain a successful unit value.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the delete operation.
     * @param entity The entity of type [T] to be deleted from the data source.
     * @return A [Result] containing a [Unit] value if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun delete(context: QueryExecutor, entity: T): Result<Unit>

    /**
     * Saves the given entity to the data source using the specified driver context.
     *
     * This method determines whether to perform an insert or update operation based on the entity's state.
     * If the operation is successful, the result will contain the saved entity.
     * In case of failure, the result contains the error details.
     *
     * @param context The database driver context used to execute the save operation.
     * @param entity The entity of type [T] to be saved in the data source.
     * @return A [Result] containing the saved entity of type [T] if the operation is successful,
     *         or an error if the operation fails.
     */
    suspend fun save(context: QueryExecutor, entity: T): Result<T>
}
