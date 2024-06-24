import io.github.smyrgeorge.sqlx4k.orThrow
import io.github.smyrgeorge.sqlx4k.toStr
import io.github.smyrgeorge.sqlx4k.use
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.cstr
import librust_lib.sqlx4k_fetch_all
import librust_lib.sqlx4k_hello
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_tx_begin
import librust_lib.sqlx4k_tx_commit
import librust_lib.sqlx4k_tx_fetch_all
import librust_lib.sqlx4k_tx_query
import librust_lib.sqlx4k_tx_rollback

@OptIn(ExperimentalForeignApi::class)
fun main() {
    sqlx4k_hello("Hello from kotlin.".cstr).orThrow()

    sqlx4k_of(
        host = "localhost",
        port = cValuesOf(15432),
        username = "postgres",
        password = "postgres",
        database = "test",
        max_connections = cValuesOf(10)
    ).orThrow()

    sqlx4k_query("drop table if exists sqlx4k;").orThrow()
    sqlx4k_query("create table if not exists sqlx4k(id integer);").orThrow()
    sqlx4k_query("insert into sqlx4k (id) values (65);").orThrow()
    sqlx4k_query("insert into sqlx4k (id) values (66);").orThrow()

    sqlx4k_fetch_all("select * from sqlx4k;").use {
        println(it.toStr())
    }

    sqlx4k_fetch_all("select * from sqlx4kk;").use {
        println(it.toStr())
    }

    sqlx4k_fetch_all("select 1;").use {
        println(it.toStr())
    }

    sqlx4k_fetch_all("select now();").use {
        println(it.toStr())
    }

    sqlx4k_fetch_all("select 'testtest', 'test1';").use {
        println(it.toStr())
    }

    println("\n\n\n::: TX :::")

    val tx1 = sqlx4k_tx_begin()
    sqlx4k_tx_query(tx1, "delete from sqlx4k;").orThrow()
    sqlx4k_tx_fetch_all(tx1, "select * from sqlx4k;").use {
        println(it.toStr())
    }
    sqlx4k_fetch_all("select * from sqlx4k;").use {
        println(it.toStr())
    }
    sqlx4k_tx_commit(tx1)
    sqlx4k_fetch_all("select * from sqlx4k;").use {
        println(it.toStr())
    }

    val tx2 = sqlx4k_tx_begin()
    sqlx4k_tx_query(tx2, "insert into sqlx4k (id) values (65);").orThrow()
    sqlx4k_tx_query(tx2, "insert into sqlx4k (id) values (66);").orThrow()
    sqlx4k_tx_fetch_all(tx2, "select * from sqlx4k;").use {
        println(it.toStr())
    }
    sqlx4k_fetch_all("select * from sqlx4k;").use {
        println(it.toStr())
    }
    sqlx4k_tx_rollback(tx2)
    sqlx4k_fetch_all("select * from sqlx4k;").use {
        println(it.toStr())
    }




}
