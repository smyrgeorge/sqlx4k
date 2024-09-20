import app.cash.sqldelight.async.coroutines.awaitAsList
import db.entities.Customer
import db.entities.Database
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.github.smyrgeorge.sqlx4k.sqldelight.Sqlx4kSqldelightDriver
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        val postgres = PostgreSQL(
            host = "localhost",
            port = 15432,
            username = "postgres",
            password = "postgres",
            database = "test",
            maxConnections = 10
        )

        val sqldelightDriver = Sqlx4kSqldelightDriver(postgres)
        val db = Database(sqldelightDriver)

        val sql = """
            CREATE TABLE IF NOT EXISTS customer (
              id SERIAL PRIMARY KEY NOT NULL,
              name VARCHAR(255) NOT NULL
            );
        """.trimIndent()
        postgres.execute(sql).getOrThrow()

        db.customerQueries.insert(1, "John 1")
        db.customerQueries.insert(2, "John 2")
        val customers: List<Customer> = db.customerQueries.getAllCustomers().awaitAsList()
        println(customers)
    }
}
