import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.examples.postgres.Sqlx4k
import io.github.smyrgeorge.sqlx4k.examples.postgres.Sqlx4kRepositoryImpl
import io.github.smyrgeorge.sqlx4k.examples.postgres.Sqlx4kRowMapper
import io.github.smyrgeorge.sqlx4k.examples.postgres.insert
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.errorOrNull
import io.github.smyrgeorge.sqlx4k.postgres.Notification
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.time.measureTime

fun main() {
    runBlocking {
        suspend fun <A> Iterable<A>.forEachParallel(
            context: CoroutineContext = Dispatchers.IO,
            f: suspend (A) -> Unit
        ): Unit = withContext(context) { map { async { f(it) } }.awaitAll() }

        val options = QueryExecutor.Pool.Options.builder()
            .maxConnections(20)
            .build()

        val db = PostgreSQL(
            url = "postgresql://localhost:15432/test",
            username = "postgres",
            password = "postgres",
            options = options
        )

        runCatching {
            val path = "./db/migrations"
            val res = db.migrate(path)
            println("Migrations completed. $res")
        }

        db.execute("drop table if exists sqlx4k;").getOrThrow()
        val error = db.execute("select * from sqlx4kk").errorOrNull()
        println(error)

        db.execute("create table if not exists sqlx4k(id integer, test text);").getOrThrow()
        db.execute("insert into sqlx4k (id, test) values (65, 'test');").getOrThrow()
        val insert = Sqlx4k(id = 66, test = "test").insert()
        val affected = db.execute(insert).getOrThrow()
        println("AFFECTED: $affected")

        suspend fun doBusinessLogic() {
            val c = TransactionContext.current()
            println("Transaction(${c.status}): $c")
        }

        suspend fun doMoreBusinessLogic(): Unit = TransactionContext.withCurrent {
            println("Transaction($status): $this")
            val inserted = Sqlx4kRepositoryImpl.insert(this, Sqlx4k(id = 123456, test = "test")).getOrThrow()
            println("INSERTED: $inserted")
        }

        suspend fun doExtraBusinessLogic(): Unit = TransactionContext.withCurrent(db) {
            println("Transaction($status): $this")
            val inserted = Sqlx4kRepositoryImpl.insert(this, Sqlx4k(id = 123456, test = "test")).getOrThrow()
            println("INSERTED: $inserted")
        }

        TransactionContext.new(db) {
            println("Transaction: $this")
            doBusinessLogic()
            doMoreBusinessLogic()
            doExtraBusinessLogic()
            val inserted = Sqlx4kRepositoryImpl.findOneById(this, 123456).getOrThrow()
            println("INSERTED: $inserted")
        }

        runCatching {
            val st = Statement.create("select * from sqlx4k where id = ?")
                .bind(0, 66)
                .render()
            println("Statement: $st")

            val st1 = Statement.create("? ? ?")
                .bind(0, "test")
                .bind(1, "'test'")
                .bind(2, "';select *;--")
                .render()
            println("Statement: $st1")
        }.onFailure {
            println("Statement error: ${it.message}")
        }

        val res: List<Sqlx4k> = Sqlx4kRepositoryImpl.findAll(db).getOrThrow()
        println(res)

//        You can map in also in place.
//        val res = db.fetchAll("select * from sqlx4k;").getOrThrow().map {
//            val id: ResultSet.Row.Column = it.get("id")
//            val test: ResultSet.Row.Column = it.get("test")
//            Sqlx4k(id = id.value!!.toInt(), test = test.value!!)
//        }

        val types = """
            select
                   null,
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
        val r0 = db.fetchAll(types).getOrThrow()
        println(r0)

        val r1 = Sqlx4kRepositoryImpl.findAll(db).getOrThrow()
        println(r1)

        val r2 = Sqlx4kRepositoryImpl.findAll(db).getOrThrow()
        println(r2)

        db.fetchAll("select 1;").getOrThrow().forEach { println(it) }
        db.fetchAll("select now();").getOrThrow().forEach { println(it) }
        db.fetchAll("select 'testtest', 'test1';").getOrThrow().forEach { println(it) }

        println("Connections: ${db.poolSize()}, Idle: ${db.poolIdleSize()}")
        println("\n\n\n::: LISTEN/NOTIFY :::")
        db.listen("chan0") { notification: Notification ->
            println(notification.value.asString())
        }

        (1..10).forEach {
            delay(1000)
            db.notify("chan0", "Hello $it")
        }

        println("\n\n\n::: TX :::")

        val tx1: Transaction = db.begin().getOrThrow()
        println(tx1)
        tx1.execute("delete from sqlx4k;").getOrThrow()
        tx1.fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
        db.fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
        tx1.commit().getOrThrow()
        db.fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }

        db.execute("insert into sqlx4k (id, test) values (65, 'test');").getOrThrow()
        db.execute("insert into sqlx4k (id, test) values (66, 'test');").getOrThrow()

        db.transaction {
            execute("delete from sqlx4k;").getOrThrow()
            fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
            // Execute outside the tx.
            db.fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
        }

        db.execute("insert into sqlx4k (id, test) values (65, 'test');").getOrThrow()
        db.execute("insert into sqlx4k (id, test) values (66, 'test');").getOrThrow()

        val test = db.fetchAll("select * from sqlx4k;", Sqlx4kRowMapper).getOrThrow()
        println(test)

        val t1 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        db.fetchAll("select * from sqlx4k limit 1000;", Sqlx4kRowMapper).getOrThrow()
                        db.fetchAll("select * from sqlx4k;", Sqlx4kRowMapper).getOrThrow()
                    }
                }
            }
        }
        println(t1)

        val t2 = measureTime {
            runBlocking {
                (1..20).forEachParallel {
                    repeat(1_000) {
                        val tx2 = db.begin().getOrThrow()
                        tx2.execute("insert into sqlx4k (id, test) values (65, 'test');").getOrThrow()
                        tx2.execute("insert into sqlx4k (id, test) values (66, 'test');").getOrThrow()
                        tx2.fetchAll("select * from sqlx4k;", Sqlx4kRowMapper).getOrThrow()
                        tx2.rollback().getOrThrow()
                        db.fetchAll("select * from sqlx4k;", Sqlx4kRowMapper).getOrThrow()
                    }
                }
            }
        }
        println(t2)

        println("Connections: ${db.poolSize()}, Idle: ${db.poolIdleSize()}")
        (1..10).forEach {
            db.notify("chan0", "Hello $it")
            delay(1000)
        }

        db.close().getOrThrow()
        val e = db.execute("drop table if exists sqlx4k;").errorOrNull()
        println("DB CLOSED: $e")
    }
}
