import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.Sqlx4kRowMapper
import io.github.smyrgeorge.sqlx4k.bench.postgres.sqlx4k.insert
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

private const val connections = 40
private const val numberOfTests = 5
private const val workers = 10
private const val repeatPerWorker = 1_000

suspend fun <A> Iterable<A>.forEachParallel(
    context: CoroutineContext = Dispatchers.IO,
    f: suspend (A) -> Unit
): Unit = withContext(context) { map { async { f(it) } }.awaitAll() }

val options = Driver.Pool.Options.builder()
    .maxConnections(connections)
    .build()

val db = PostgreSQL(
    host = "localhost",
    port = 15432,
    username = "postgres",
    password = "postgres",
    database = "test",
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

    println("[txCommit]")
    val txCommit = tests.map {
        println("[txCommit] $it")
        val time = measureTime {
            (1..workers).forEachParallel {
                repeat(repeatPerWorker) {
                    db.transaction {
                        execute(Sqlx4k(65, "test").insert()).getOrThrow()
                        execute(Sqlx4k(66, "test").insert()).getOrThrow()
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
        println("[txRollback] $it")
        val time = measureTime {
            (1..workers).forEachParallel {
                repeat(repeatPerWorker) {
                    runCatching {
                        db.transaction {
                            execute(Sqlx4k(65, "test").insert()).getOrThrow()
                            execute(Sqlx4k(66, "test").insert()).getOrThrow()
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