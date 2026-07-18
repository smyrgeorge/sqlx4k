package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import io.github.smyrgeorge.sqlx4k.annotation.Table

/**
 * Minimal entity used to exercise the in-memory repository generator.
 */
@Table("users")
data class User(
    @Id
    val id: Long,
    val name: String,
    val email: String
)

/**
 * CrudRepository fixture (explicit context parameter style).
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

    @Query("SELECT * FROM users WHERE email IS NOT NULL")
    suspend fun findAllByEmailNotNull(context: QueryExecutor): Result<List<User>>

    // LIKE is not translatable to an in-memory predicate, so this becomes an overridable stub.
    @Query("SELECT * FROM users WHERE name LIKE :pattern")
    suspend fun findAllByNameLike(context: QueryExecutor, pattern: String): Result<List<User>>

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
 * ContextCrudRepository fixture (context parameter style).
 */
@Repository
interface UserContextCrudRepository : ContextCrudRepository<User> {
    @Query("SELECT * FROM users WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Long): Result<User?>

    @Query("SELECT * FROM users")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<User>>

    @Query("SELECT * FROM users WHERE name = :name")
    context(context: QueryExecutor)
    suspend fun findAllByName(name: String): Result<List<User>>
}

/**
 * No-op [QueryExecutor] used purely as an (ignored) context argument for the in-memory doubles, which
 * never touch a real database.
 */
class MockQueryExecutor : QueryExecutor {
    override val encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY
    override suspend fun execute(sql: String): Result<Long> = Result.success(0L)
    override suspend fun execute(statement: Statement): Result<Long> = Result.success(0L)
    override suspend fun fetchAll(sql: String): Result<ResultSet> = Result.success(emptyResultSet())
    override suspend fun fetchAll(statement: Statement): Result<ResultSet> = Result.success(emptyResultSet())

    private fun emptyResultSet(): ResultSet =
        ResultSet(rows = emptyList(), error = null, metadata = ResultSet.Metadata(emptyList()))
}

/**
 * Records hook invocations so tests can assert the in-memory doubles honor them.
 */
object HooksTracker {
    val calls = mutableListOf<String>()
    fun reset() = calls.clear()
}

/**
 * CrudRepository fixture that overrides every hook (explicit context parameter style).
 */
@Repository
interface UserRepositoryWithHooks : CrudRepository<User> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_preInsert")
    }

    override suspend fun afterInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_afterInsert")
    }

    override suspend fun preUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_preUpdate")
    }

    override suspend fun afterUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_afterUpdate")
    }

    override suspend fun preDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preDelete:${entity.name}")
        return entity
    }

    override suspend fun afterDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterDelete:${entity.name}")
        return entity
    }

    override suspend fun <R> aroundQuery(method: String, statement: Statement, block: suspend () -> R): R {
        HooksTracker.calls.add("aroundQuery:$method")
        return block()
    }

    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<User?>

    @Query("SELECT * FROM users")
    suspend fun findAll(context: QueryExecutor): Result<List<User>>
}

/**
 * ContextCrudRepository fixture that overrides every hook (context parameter style).
 */
@Repository
interface UserContextRepositoryWithHooks : ContextCrudRepository<User> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_preInsert")
    }

    override suspend fun afterInsertHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterInsert:${entity.name}")
        return entity.copy(name = "${entity.name}_afterInsert")
    }

    override suspend fun preUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_preUpdate")
    }

    override suspend fun afterUpdateHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterUpdate:${entity.name}")
        return entity.copy(name = "${entity.name}_afterUpdate")
    }

    override suspend fun preDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("preDelete:${entity.name}")
        return entity
    }

    override suspend fun afterDeleteHook(context: QueryExecutor, entity: User): User {
        HooksTracker.calls.add("afterDelete:${entity.name}")
        return entity
    }

    override suspend fun <R> aroundQuery(method: String, statement: Statement, block: suspend () -> R): R {
        HooksTracker.calls.add("aroundQuery:$method")
        return block()
    }

    @Query("SELECT * FROM users WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Long): Result<User?>

    @Query("SELECT * FROM users")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<User>>
}
