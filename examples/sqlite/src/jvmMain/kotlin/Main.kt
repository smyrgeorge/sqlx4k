import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.examples.sqlite.Examples
import io.github.smyrgeorge.sqlx4k.examples.sqlite.Sqlx4kRepositoryImpl
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    val db = sqlite(
        url = "test.db",
        options = options
    )

    // Run the examples.
    Examples.runAll(db, Sqlx4kRepositoryImpl)

    // Close the connection.
    db.close().getOrThrow()
}
