import db.entities.Customer
import db.entities.Database
import io.github.smyrgeorge.sqlx4k.postgres.PostgreSQL
import io.github.smyrgeorge.sqlx4k.sqldelight.Sqlx4kSqldelightDriver

fun main() {
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

    val customers: List<Customer> = db.customerQueries.getAllCustomers().executeAsList()
    println(customers)
}
