import io.github.smyrgeorge.sqlx4k.Sqlx4k
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.Postgres
import io.github.smyrgeorge.sqlx4k.impl.errorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.measureTime

fun main() {
    runBlocking {
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

        pg.query("drop table if exists :table;", mapOf("table" to "sqlx4k")).getOrThrow()
        pg.query("drop table if exists :table;", mapOf("table" to "sqlx4k")) { v: Any? ->
            //  Map the value here.
            "MAPPED_VALUE"
        }.getOrThrow()
        val error = pg.query("select * from sqlx4kk").errorOrNull()
        println(error)

        pg.query("create table if not exists sqlx4k(id integer);").getOrThrow()
        pg.query("insert into sqlx4k (id) values (65);").getOrThrow()
        pg.query("insert into sqlx4k (id) values (66);").getOrThrow()

        data class Test(val id: Int)

        val r1 = pg.fetchAll("select * from sqlx4k;") {
            val id: Sqlx4k.Row.Column = get("id")
            Test(id = id.value.toInt())
        }
        println(r1)

        val r2 = pg.fetchAll("select * from sqlx4k;") {
            val id: Sqlx4k.Row.Column = get(0)
            Test(id = id.value.toInt())
        }
        println(r2)
        val r3 = pg.fetchAll("select * from sqlx4k;") {
            val id: Sqlx4k.Row.Column = get(1)
            Test(id = id.value.toInt())
        }
        println(r3)

        pg.fetchAll("select * from :table;", mapOf("table" to "sqlx4k")) {
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

        println("Connections: ${pg.poolSize()}, Idle: ${pg.poolIdleSize()}")
        println("\n\n\n::: LISTEN/NOTIFY :::")
        pg.listen("chan0") { notification: Postgres.Notification ->
            println(notification)
        }

        (1..10).forEach {
            pg.notify("chan0", "Hello $it")
            delay(1000)
        }

        println("\n\n\n::: TX :::")

        val tx1: Transaction = pg.begin().getOrThrow()
        println(tx1)
        tx1.query("delete from sqlx4k;").getOrThrow()
        tx1.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        pg.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        tx1.commit().getOrThrow()
        pg.fetchAll("select * from sqlx4k;") {
            println(debug())
        }

        pg.query("insert into sqlx4k (id) values (65);").getOrThrow()
        pg.query("insert into sqlx4k (id) values (66);").getOrThrow()

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
//      4.740002541s
//      4.732109584s
        println(t1)

        val t2 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        val tx2 = pg.begin().getOrThrow()
                        tx2.query("insert into sqlx4k (id) values (65);").getOrThrow()
                        tx2.query("insert into sqlx4k (id) values (66);").getOrThrow()
                        tx2.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        tx2.rollback().getOrThrow()
                        pg.fetchAll("select * from sqlx4k;") {
                            val id: Sqlx4k.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                    }
                }
            }
        }
//      9.385897375s
//      9.351138833s
        println(t2)

        println("Connections: ${pg.poolSize()}, Idle: ${pg.poolIdleSize()}")
        (1..10).forEach {
            println("Notify: $it")
            pg.notify("chan0", "Hello $it")
            delay(1000)
        }
    }
}
