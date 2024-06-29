import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.driver.Transaction
import io.github.smyrgeorge.sqlx4k.driver.impl.Postgres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.measureTime

@Suppress("unused")
fun main() {
    runBlocking {
        suspend fun <A, B> Iterable<A>.mapParallel(
            context: CoroutineContext = Dispatchers.IO,
            f: suspend (A) -> B
        ): List<B> = withContext(context) { map { async { f(it) } }.awaitAll() }

        suspend fun <A> Iterable<A>.forEachParallel(
            context: CoroutineContext = Dispatchers.IO,
            f: suspend (A) -> Unit
        ): Unit = withContext(context) { map { async { f(it) } }.awaitAll() }

        val pg = Postgres(
            host = "localhost",
            port = 15432,
            username = "postgres",
            password = "postgres",
            database = "test",
            maxConnections = 10
        )

        pg.query("drop table if exists sqlx4k;")
        pg.query("create table if not exists sqlx4k(id integer);")
        pg.query("insert into sqlx4k (id) values (65);")
        pg.query("insert into sqlx4k (id) values (66);")

        data class Test(val id: Int)

        val r1 = pg.fetchAll("select * from sqlx4k;") {
            val id: Sqlx4k.Row.Column = get("id")
            Test(id = id.value.toInt())
        }

        println(r1)

        pg.fetchAll("select * from sqlx4kk;") {
            val id: Sqlx4k.Row.Column = get("id")
            Test(id = id.value.toInt())
        }

        pg.fetchAll("select 1;") {
            println(debug())
        }

        pg.fetchAll("select now();") {
            println(debug())
        }

        pg.fetchAll("select 'testtest', 'test1';") {
            println(debug())
        }

        println("\n\n\n::: TX :::")

        val tx1: Transaction = pg.begin().getOrThrow()
        tx1.query("delete from sqlx4k;")
        tx1.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        pg.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        tx1.commit()
        pg.fetchAll("select * from sqlx4k;") {
            println(debug())
        }

        pg.query("insert into sqlx4k (id) values (65);")
        pg.query("insert into sqlx4k (id) values (66);")

        val test = pg.fetchAll("select * from sqlx4k;") {
            val id: Sqlx4k.Row.Column = get("id")
            Test(id = id.value.toInt())
        }
        println(test)

        val t1 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        pg.fetchAll("select * from sqlx4k limit 1000;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        pg.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                    }
                }
            }
        }
        println(t1)

        val t2 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        val tx2 = pg.begin().getOrThrow()
                        tx2.query("insert into sqlx4k (id) values (65);")
                        tx2.query("insert into sqlx4k (id) values (66);")
                        tx2.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        pg.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        tx2.rollback()
                        pg.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                    }
                }
            }
        }
        println(t2)
    }
}
