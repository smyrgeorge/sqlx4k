import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.examples.postgres.Examples
import io.github.smyrgeorge.sqlx4k.examples.postgres.Sqlx4kRepositoryImpl
import io.github.smyrgeorge.sqlx4k.postgres.postgreSQL
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val options = QueryExecutor.Pool.Options.builder()
        .maxConnections(10)
        .build()

    val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    // Run the examples.
    Examples.runAll(db, Sqlx4kRepositoryImpl)

    // Close the connection.
    db.close().getOrThrow()
}
