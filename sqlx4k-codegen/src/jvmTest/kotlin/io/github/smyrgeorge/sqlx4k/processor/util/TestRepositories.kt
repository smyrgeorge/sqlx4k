package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

/**
 * CrudRepository test interface for User entity.
 * Uses explicit context parameter style.
 */
@Repository
interface UserCrudRepository : CrudRepository<User> {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<User?>

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun findOneByEmail(context: QueryExecutor, email: String): Result<User?>

    @Query("SELECT * FROM users")
    suspend fun findAll(context: QueryExecutor): Result<List<User>>

    @Query("SELECT * FROM users WHERE name = :name")
    suspend fun findAllByName(context: QueryExecutor, name: String): Result<List<User>>

    @Query("SELECT count(*) FROM users")
    suspend fun countAll(context: QueryExecutor): Result<Long>

    @Query("SELECT count(*) FROM users WHERE name = :name")
    suspend fun countByName(context: QueryExecutor, name: String): Result<Long>

    @Query("DELETE FROM users WHERE email = :email")
    suspend fun deleteByEmail(context: QueryExecutor, email: String): Result<Long>

    @Query("UPDATE users SET name = :newName WHERE name = :oldName")
    suspend fun executeUpdateName(context: QueryExecutor, oldName: String, newName: String): Result<Long>
}

/**
 * ContextCrudRepository test interface for User entity.
 * Uses context parameter style.
 */
@Repository
interface UserContextCrudRepository : ContextCrudRepository<User> {
    @Query("SELECT * FROM users WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Long): Result<User?>

    @Query("SELECT * FROM users WHERE email = :email")
    context(context: QueryExecutor)
    suspend fun findOneByEmail(email: String): Result<User?>

    @Query("SELECT * FROM users")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<User>>

    @Query("SELECT * FROM users WHERE name = :name")
    context(context: QueryExecutor)
    suspend fun findAllByName(name: String): Result<List<User>>

    @Query("SELECT count(*) FROM users")
    context(context: QueryExecutor)
    suspend fun countAll(): Result<Long>

    @Query("SELECT count(*) FROM users WHERE name = :name")
    context(context: QueryExecutor)
    suspend fun countByName(name: String): Result<Long>

    @Query("DELETE FROM users WHERE email = :email")
    context(context: QueryExecutor)
    suspend fun deleteByEmail(email: String): Result<Long>

    @Query("UPDATE users SET name = :newName WHERE name = :oldName")
    context(context: QueryExecutor)
    suspend fun executeUpdateName(oldName: String, newName: String): Result<Long>
}
