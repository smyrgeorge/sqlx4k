import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.examples.sqlite.Bench
import io.github.smyrgeorge.sqlx4k.examples.sqlite.CipherExamples
import io.github.smyrgeorge.sqlx4k.examples.sqlite.Examples
import io.github.smyrgeorge.sqlx4k.examples.sqlite.Sqlx4kRepositoryImpl
import io.github.smyrgeorge.sqlx4k.sqlite.cipher.sqliteCipher
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    val db = sqlite(
        url = "sqlite://test.db",
        options = options
    )

    val cipher = sqliteCipher(
        url = "sqlite://test-cipher.db",
        password = "a-secret-key",
        options = options
    )

    // Run the examples against the plain SQLite driver.
    println("\n######## SQLite examples ########")
    Examples.runAll(db, Sqlx4kRepositoryImpl)

    // Run the same examples against the encrypted SQLCipher driver.
    println("\n######## SQLCipher examples ########")
    CipherExamples.runAll(cipher, Sqlx4kRepositoryImpl)

    // Benchmark the same workload (text queries + transactions) on both drivers.
    println("\n######## Benchmark: SQLite vs SQLCipher ########")
    val sqliteJournal = db.fetchAll("PRAGMA journal_mode;").getOrThrow().first().get(0).asString()
    val cipherJournal = cipher.fetchAll("PRAGMA journal_mode;").getOrThrow().first().get(0).asString()
    val sqliteTime = Bench.run(db)
    val cipherTime = Bench.run(cipher)
    val overhead = cipherTime / sqliteTime
    println("SQLite    : $sqliteTime (journal_mode=$sqliteJournal)")
    println("SQLCipher : $cipherTime (journal_mode=$cipherJournal)")
    println("Overhead  : ${(overhead * 100).toLong() / 100.0}x (SQLCipher / SQLite)")

    // Close the connections.
    db.close().getOrThrow()
    cipher.close().getOrThrow()
}
