package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
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

/**
 * Global tracker for hook invocations in tests.
 */
object HooksTracker {
    val hooksCalled = mutableListOf<String>()

    fun reset() {
        hooksCalled.clear()
    }
}

/**
 * Test repository with custom hooks for testing hook functionality.
 * Uses explicit context parameter style.
 */
@Repository
interface UserRepositoryWithHooks : CrudRepository<User> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_preInsert")
    }

    override suspend fun afterInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_afterInsert")
    }

    override suspend fun preUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_preUpdate")
    }

    override suspend fun afterUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_afterUpdate")
    }

    override suspend fun preDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preDelete:${entity.name}")
        return entity
    }

    override suspend fun afterDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterDelete:${entity.name}")
        return entity
    }

    override suspend fun <R> aroundQuery(method: String, statement: Statement, block: suspend () -> R): R {
        HooksTracker.hooksCalled.add("aroundQuery:$method")
        return block()
    }

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<User?>

    @Query("SELECT * FROM users")
    suspend fun findAll(context: QueryExecutor): Result<List<User>>
}

/**
 * Test repository with custom hooks for testing hook functionality.
 * Uses context parameter style.
 */
@Repository
interface UserContextRepositoryWithHooks : ContextCrudRepository<User> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_preInsert")
    }

    override suspend fun afterInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_afterInsert")
    }

    override suspend fun preUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_preUpdate")
    }

    override suspend fun afterUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_afterUpdate")
    }

    override suspend fun preDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("preDelete:${entity.name}")
        return entity
    }

    override suspend fun afterDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.hooksCalled.add("afterDelete:${entity.name}")
        return entity
    }

    override suspend fun <R> aroundQuery(method: String, statement: Statement, block: suspend () -> R): R {
        HooksTracker.hooksCalled.add("aroundQuery:$method")
        return block()
    }

    @Query("SELECT * FROM users WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Long): Result<User?>

    @Query("SELECT * FROM users")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<User>>
}
