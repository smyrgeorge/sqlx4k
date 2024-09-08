import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.errorOrNull
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
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

        val db = PostgreSQL(
            host = "localhost",
            port = 15432,
            username = "postgres",
            password = "postgres",
            database = "test",
            maxConnections = 11
        )

        db.execute("drop table if exists sqlx4k;").getOrThrow()
        val error = db.execute("select * from sqlx4kk").errorOrNull()
        println(error)

        db.execute("create table if not exists sqlx4k(id integer);").getOrThrow()
        db.execute("insert into sqlx4k (id) values (65);").getOrThrow()
        val affected = db.execute("insert into sqlx4k (id) values (66);").getOrThrow()
        println("AFFECTED: $affected")

        data class Test(val id: Int)

        val types = """
            select
                   true::bool,
                   1::int2,
                   1::int4,
                   1::int8,
                   1::float4,
                   1::float8,
                   1::numeric(10,2),
                   'a'::char,
                   'aa'::varchar,
                   'aa'::text,
                   now()::timestamp as timestamp,
                   now()::timestamptz as timestampz,
                   now()::date as date,
                   now()::time as time,
                   '22d64ef8-f6b3-43da-8869-2ee9d31be9d5'::uuid,
                   '{"a": 5}'::json,
                   '{"a": 5}'::jsonb,
                   'aa'::bytea
            ;
        """.trimIndent()
        val r0 = db.fetchAll(types) {
            columns.forEach {
                val v = it.value
                val res = v.value
                println("${it.key} :: $res")
            }
        }
        println(r0)

        val r1 = db.fetchAll("select * from sqlx4k;") {
            val id: ResultSet.Row.Column = get("id")
            Test(id = id.value.toInt())
        }
        println(r1)

        val r2 = db.fetchAll("select * from sqlx4k;") {
            val id: ResultSet.Row.Column = get(0)
            Test(id = id.value.toInt())
        }
        println(r2)
        val r3 = db.fetchAll("select * from sqlx4k;") {
            val id: ResultSet.Row.Column = get(1)
            Test(id = id.value.toInt())
        }
        println(r3)

        db.fetchAll("select * from :table;", mapOf("table" to "sqlx4k")) {
            val id: ResultSet.Row.Column = get("id")
            Test(id = id.value.toInt())
        }

        db.fetchAll("select 1;") {
            println(debug())
        }

        db.fetchAll("select now();") {
            println(debug())
        }

        db.fetchAll("select 'testtest', 'test1';") {
            println(debug())
        }

        println("Connections: ${db.poolSize()}, Idle: ${db.poolIdleSize()}")
        println("\n\n\n::: LISTEN/NOTIFY :::")
        db.listen("chan0") { notification: PostgreSQL.Notification ->
            println(notification)
        }

        (1..10).forEach {
            db.notify("chan0", "Hello $it")
            delay(1000)
        }

        println("\n\n\n::: TX :::")

        val tx1: Transaction = db.begin().getOrThrow()
        println(tx1)
        tx1.execute("delete from sqlx4k;").getOrThrow()
        tx1.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        db.fetchAll("select * from sqlx4k;") {
            println(debug())
        }
        tx1.commit().getOrThrow()
        db.fetchAll("select * from sqlx4k;") {
            println(debug())
        }

        db.execute("insert into sqlx4k (id) values (65);").getOrThrow()
        db.execute("insert into sqlx4k (id) values (66);").getOrThrow()

        val test = db.fetchAll("select * from sqlx4k;") {
            val id: ResultSet.Row.Column = get("id")
            Test(id = id.value.toInt())
        }
        println(test)

        val t1 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        db.fetchAll("select * from sqlx4k limit 1000;") {
                            val id: ResultSet.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        db.fetchAll("select * from sqlx4k;") {
                            val id: ResultSet.Row.Column = get("id")
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
                        val tx2 = db.begin().getOrThrow()
                        tx2.execute("insert into sqlx4k (id) values (65);").getOrThrow()
                        tx2.execute("insert into sqlx4k (id) values (66);").getOrThrow()
                        tx2.fetchAll("select * from sqlx4k;") {
                            val id: ResultSet.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                        tx2.rollback().getOrThrow()
                        db.fetchAll("select * from sqlx4k;") {
                            val id: ResultSet.Row.Column = get("id")
                            Test(id = id.value.toInt())
                        }
                    }
                }
            }
        }
//      9.385897375s
//      9.351138833s
        println(t2)

        println("Connections: ${db.poolSize()}, Idle: ${db.poolIdleSize()}")
        (1..10).forEach {
            println("Notify: $it")
            db.notify("chan0", "Hello $it")
            delay(1000)
        }
    }
}