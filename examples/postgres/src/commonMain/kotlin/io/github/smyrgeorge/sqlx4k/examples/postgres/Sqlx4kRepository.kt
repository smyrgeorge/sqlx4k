package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository(mapper = Sqlx4kRowMapper::class)
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    suspend fun findAll(context: QueryExecutor): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    suspend fun countAll(context: QueryExecutor): Result<Long>
}

@OptIn(ExperimentalContextParameters::class)
@Repository(mapper = Sqlx4kRowMapper::class)
interface Sqlx4kContextCrudRepository : ContextCrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    context(context: QueryExecutor)
    suspend fun countAll(): Result<Long>

    @Query.Hook(Query.Hook.Kind.PRE_INSERT)
    context(context: QueryExecutor)
    suspend fun preInsertHook(entity: Sqlx4k): Sqlx4k {
        println("Pre-insert hook called for entity: $entity")
        return entity
    }

    @Query.Hook(Query.Hook.Kind.PRE_UPDATE)
    suspend fun preUpdateHook(entity: Sqlx4k): Sqlx4k {
        println("Pre-update hook called for entity: $entity")
        return entity
    }

    @Query.Hook(Query.Hook.Kind.PRE_DELETE)
    suspend fun preDeleteHook(entity: Sqlx4k): Sqlx4k {
        println("Pre-delete hook called for entity: $entity")
        return entity
    }
}

data class TestContext(val id: Int, val name: String)

@OptIn(ExperimentalContextParameters::class)
interface AuditableRepository<T> : CrudRepository<T> {
    @Query.Hook(Query.Hook.Kind.PRE_INSERT, false)
    context(context: QueryExecutor, testContext: TestContext)
    suspend fun preInsertHook(entity: T): T {
        println("Pre-insert hook called for entity: $entity")
        return entity
    }

    context(context: QueryExecutor, testContext: TestContext)
    suspend fun insert(entity: T): Result<T> {
        val e = preInsertHook(entity)
        return insert(context, e)
    }
}

@OptIn(ExperimentalContextParameters::class)
@Repository(mapper = Sqlx4kRowMapper::class)
interface Sqlx4kAuditableRepository : AuditableRepository<Sqlx4k> {
    @Query("SELECT count(*) FROM sqlx4k")
    context(context: QueryExecutor)
    suspend fun countAll(): Result<Long>
}

context(context: QueryExecutor, testContext: TestContext)
suspend fun test() {
    val e = Sqlx4k(1, "test")
    context(context, testContext) {
        Sqlx4kAuditableRepositoryImpl.insert(e)
    }
}