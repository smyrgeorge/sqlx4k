import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.Sqlx4kRowMapper
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.insert
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

private const val connections = 40
private const val numberOfTests = 10
private const val workers = 4
private const val repeatPerWorker = 1_000

suspend fun <A> Iterable<A>.forEachParallel(
    context: CoroutineContext = Dispatchers.IO,
    f: suspend (A) -> Unit
): Unit = withContext(context) { map { async { f(it) } }.awaitAll() }

val options = ConnectionPool.Options.builder()
    .minConnections(10)
    .maxConnections(connections)
    .maxLifetime(10.minutes)
    .build()

val db = PostgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
    options = options
)

fun main() = runBlocking {
    bench()
    db.close().getOrThrow()
}

suspend fun bench() {
    db.execute("drop table if exists sqlx4k;").getOrThrow()
    db.execute("create table sqlx4k(id integer, test text);").getOrThrow()

    val tests = 1..numberOfTests

    println("[noTx]")
    val noTx = tests.map {
        val time = measureTime {
            (1..workers).forEachParallel {
                repeat(repeatPerWorker) {
                    db.execute(Sqlx4k.random().insert()).getOrThrow()
                    db.execute(Sqlx4k.random().insert()).getOrThrow()
                    db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
                }
            }
        }
        println("[noTx] $time")
        time
    }.map { it.inWholeMilliseconds }.average()
    val noTxRows = db.fetchAll("select count(*) from sqlx4k;").getOrThrow().first().get(0).asLong()
    println("[noTx] ${noTx.milliseconds} $noTxRows")

    println("[txCommit]")
    val txCommit = tests.map {
        val time = measureTime {
            (1..workers).forEachParallel {
                repeat(repeatPerWorker) {
                    db.transaction {
                        execute(Sqlx4k.random().insert()).getOrThrow()
                        execute(Sqlx4k.random().insert()).getOrThrow()
                        fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
                    }
                    db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
                }
            }
        }
        println("[txCommit] $time")
        time
    }.map { it.inWholeMilliseconds }.average()
    val txCommitRows = db.fetchAll("select count(*) from sqlx4k;").getOrThrow().first().get(0).asLong()
    println("[txCommit] ${txCommit.milliseconds} $txCommitRows")

    println("[txRollback]")
    val txRollback = tests.map {
        val time = measureTime {
            (1..workers).forEachParallel {
                repeat(repeatPerWorker) {
                    runCatching {
                        db.transaction {
                            execute(Sqlx4k.random().insert()).getOrThrow()
                            execute(Sqlx4k.random().insert()).getOrThrow()
                            fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
                            error("Trigger rollback")
                        }
                        db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
                    }
                }
            }
        }
        println("[txRollback] $time")
        time
    }.map { it.inWholeMilliseconds }.average()
    val txRollbackRows = db.fetchAll("select count(*) from sqlx4k;").getOrThrow().first().get(0).asLong()
    println("[txRollback] ${txRollback.milliseconds} $txRollbackRows")
}