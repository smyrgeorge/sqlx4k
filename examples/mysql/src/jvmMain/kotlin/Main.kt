import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.examples.mysql.Examples
import io.github.smyrgeorge.sqlx4k.examples.mysql.Sqlx4kRepositoryImpl
import io.github.smyrgeorge.sqlx4k.mysql.mySQL
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val options = QueryExecutor.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = mySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )

    // Run the examples.
    Examples.runAll(db, Sqlx4kRepositoryImpl)

    // Close the connection.
    db.close().getOrThrow()
}
