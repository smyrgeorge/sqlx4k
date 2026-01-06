package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

@Repository
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    suspend fun findAll(context: QueryExecutor): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    suspend fun countAll(context: QueryExecutor): Result<Long>
}

interface AuditableRepository<T> : CrudRepository<T> {
    override suspend fun preInsertHook(context: QueryExecutor, entity: T): T {
        return entity
    }
}

@Repository
interface Sqlx4kAuditableRepository : AuditableRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>
}

