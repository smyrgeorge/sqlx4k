package io.github.smyrgeorge.sqlx4k.bench.postgres.spring.boot.r2dbc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.withContext
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Query
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.await
import org.springframework.stereotype.Repository
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import kotlin.coroutines.CoroutineContext
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

@Repository
class Sqlx4kService(
    private val databaseClient: DatabaseClient,
    private val entityTemplate: R2dbcEntityTemplate,
    private val transactionalOperator: TransactionalOperator
) {
    init {
        INSTANCE = this
    }

    private val numberOfTests = 5
    private val workers = 10
    private val repeatPerWorker = 1_000
    private val selectAll = Query.empty().limit(100)

    suspend fun <A> Iterable<A>.forEachParallel(
        context: CoroutineContext = Dispatchers.IO,
        f: suspend (A) -> Unit
    ): Unit = withContext(context) { map { async { f(it) } }.awaitAll() }

    suspend fun warmup() {
        databaseClient.sql("drop table if exists sqlx4k;").await()
        databaseClient.sql("create table sqlx4k(id integer, test text);").await()

        val tests = 1..numberOfTests

        println("[txCommit]")
        val txCommit = tests.map {
            println("[txCommit] $it")
            val time = measureTime {
                (1..workers).forEachParallel {
                    repeat(repeatPerWorker) {
                        transactionalOperator.executeAndAwait {
                            entityTemplate.insert(Sqlx4k(65, "test")).awaitFirst()
                            entityTemplate.insert(Sqlx4k(66, "test")).awaitFirst()
                            entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                        }
                        entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                    }
                }
            }
            println("[txCommit] $time")
            time
        }.map { it.inWholeMilliseconds }.average()
        println("[txCommit] ${txCommit.milliseconds}")
    }

    suspend fun bench() {
        databaseClient.sql("drop table if exists sqlx4k;").await()
        databaseClient.sql("create table sqlx4k(id integer, test text);").await()

        val tests = 1..numberOfTests

        println("[txCommit]")
        val txCommit = tests.map {
            println("[txCommit] $it")
            val time = measureTime {
                (1..workers).forEachParallel {
                    repeat(repeatPerWorker) {
                        transactionalOperator.executeAndAwait {
                            entityTemplate.insert(Sqlx4k(65, "test")).awaitFirst()
                            entityTemplate.insert(Sqlx4k(66, "test")).awaitFirst()
                            entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                        }
                        entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                    }
                }
            }
            println("[txCommit] $time")
            time
        }.map { it.inWholeMilliseconds }.average()
        println("[txCommit] ${txCommit.milliseconds}")

        println("[txRollback]")
        val txRollback = tests.map {
            println("[txRollback] $it")
            val time = measureTime {
                (1..workers).forEachParallel {
                    repeat(repeatPerWorker) {
                        runCatching {
                            transactionalOperator.executeAndAwait {
                                entityTemplate.insert(Sqlx4k(65, "test")).awaitFirst()
                                entityTemplate.insert(Sqlx4k(66, "test")).awaitFirst()
                                entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                                error("Trigger rollback")
                            }
                            entityTemplate.select(selectAll, Sqlx4k::class.java).asFlow().toList()
                        }
                    }
                }
            }
            println("[txRollback] $time")
            time
        }.map { it.inWholeMilliseconds }.average()
        println("[txRollback] ${txRollback.milliseconds}")

        exitProcess(0)
    }

    companion object {
        lateinit var INSTANCE: Sqlx4kService
    }
}
