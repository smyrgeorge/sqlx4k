import ReportedError.Companion.checkExitCode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import librust_lib.Sqlx4kPgColumn
import librust_lib.Sqlx4kPgResult
import librust_lib.Sqlx4kPgRow
import librust_lib.sqlx4k_free
import librust_lib.sqlx4k_hello
import librust_lib.sqlx4k_of
import librust_lib.sqlx4k_query
import librust_lib.sqlx4k_query_fetch_all

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kPgResult.toStr(): String = buildString {
    append("\n[Sqlx4kPgResult]")
    append("\nerror: $error")
    append("\nerror_message: ${error_message?.toKString()}")
    append("\nsize: $size")
    rows?.let {
        repeat(size) { index ->
            append(it[index].toStr())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kPgRow.toStr(): String {
    return buildString {
        append("\n    [Sqlx4kPgRow]")
        append("\n    size: $size")
        columns?.let {
            repeat(size) { index ->
                append(it[index].toStr())
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun Sqlx4kPgColumn.toStr(): String {
    return buildString {
        append("\n        [Sqlx4kPgColumn]")
        append("\n        ordinal: $ordinal")
        append("\n        name: ${name?.toKString()}")
        append("\n        kind: $kind")
        append("\n        size: $size")
        append("\n        value: ${value?.readBytes(size)?.toKString()}")
    }
}

@OptIn(ExperimentalForeignApi::class)
fun CPointer<Sqlx4kPgResult>?.use(f: (it: Sqlx4kPgResult) -> Unit) {
    val res = this?.pointed ?: error("Could not extract the value from the raw pointer (null).")
    f(res)
    sqlx4k_free(this)
}

@OptIn(ExperimentalForeignApi::class)
fun main() {
    sqlx4k_hello("Hello from kotlin.".cstr).checkExitCode()
    sqlx4k_of(
        host = "localhost",
        port = cValuesOf(5432),
        username = "test",
        password = "test",
        database = "test_db",
        max_connections = cValuesOf(10)
    ).checkExitCode()

    sqlx4k_query("drop table if exists data_collector.sqlx4k;").checkExitCode()
    sqlx4k_query("create table if not exists data_collector.sqlx4k(id integer);").checkExitCode()
    sqlx4k_query("insert into data_collector.sqlx4k (id) values (65);").checkExitCode()
    sqlx4k_query("insert into data_collector.sqlx4k (id) values (66);").checkExitCode()

    sqlx4k_query_fetch_all("select * from data_collector.sqlx4k;").use {
        println(it.toStr())
    }

    sqlx4k_query_fetch_all("select * from data_collector.sqlx4kk;").use {
        println(it.toStr())
    }

    sqlx4k_query_fetch_all("select 1;").use {
        println(it.toStr())
    }

    sqlx4k_query_fetch_all("select now();").use {
        println(it.toStr())
    }

    sqlx4k_query_fetch_all("select 'testtest', 'test1';").use {
        println(it.toStr())
    }

//    while (true) {
//        sqlx4k_query_fetch_all("select repeat('SQL', 1000);").use {
//        }
//    }
}
