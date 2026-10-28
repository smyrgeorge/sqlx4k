# Databases

Docs: <https://smyrgeorge.github.io/sqlx4k/databases/postgresql/>

| Database        | Module                 | Plugin `driver`     | Class (JVM / native) | Multiplatform function |
|-----------------|------------------------|---------------------|----------------------|------------------------|
| PostgreSQL      | `sqlx4k-postgres`      | `PostgreSQL`        | `PostgreSQL`         | `postgreSQL(...)`      |
| MySQL / MariaDB | `sqlx4k-mysql`         | `MySQL` / `MariaDB` | `MySQL`              | `mySQL(...)`           |
| SQLite          | `sqlx4k-sqlite`        | `SQLite`            | `SQLite`             | `sqlite(...)`          |
| SQLCipher       | `sqlx4k-sqlite-cipher` | `SQLiteCipher`      | `SQLiteCipher`       | `sqliteCipher(...)`    |

Without the plugin, add the module: `implementation("io.github.smyrgeorge:sqlx4k-postgres:1.17.0")`. Every constructor
also takes `options: ConnectionPool.Options` and `encoders: ValueEncoderRegistry`. In `commonMain` code, use the
multiplatform function.

## PostgreSQL

```kotlin
val db = PostgreSQL(url = "postgresql://localhost:5432/app", username = "app", password = "secret")
```

- URL: `postgresql://` or `postgres://`, every part optional (`postgresql://localhost:5433/mydb`).
- `executeWithResult` has no `lastInsertId`: use `INSERT ... RETURNING id` with `fetchAll`.
- LISTEN/NOTIFY:

  ```kotlin
  val listener = launch { db.listen("chan0") { n: Postgres.Notification -> println(n) } } // suspends while listening
  db.notify("chan0", "hello")
  listener.cancel() // stops listening
  ```

- Message queues: `sqlx4k-postgres-pgmq` (plugin: `extensions(Pgmq)`):

  ```kotlin
  val pgmq = PgmqClient(pg = PgmqDbAdapterImpl(db), options = PgmqClient.Options(autoInstall = true))
  pgmq.create(PgmqClient.Queue(name = "orders", enableNotifyInsert = true)).getOrThrow()
  pgmq.send("orders", """{"orderId": 1}""").getOrThrow()
  pgmq.read("orders", quantity = 10, vt = 30.seconds).getOrThrow().forEach { msg ->
      pgmq.ack("orders", msg.msgId).getOrThrow()
  }
  // Or PgmqConsumer(pgmq, PgmqConsumer.Options(queue = "orders", ...), onMessage = { }, onFailToProcess = { })
  ```

## MySQL / MariaDB

```kotlin
val db = MySQL(url = "mysql://localhost:3306/app", username = "app", password = "secret")
```

- URL: `mysql://[host][:port][/database][?properties]`; properties: `ssl-mode`, `ssl-ca`, `ssl-cert`, `ssl-key`,
  `socket`, `timezone` (UTC by default), and r2dbc's `ssl=true|false` on the JVM.
- The same driver serves MariaDB; with the plugin, pick `driver = MariaDB` so the code generator uses
  `INSERT ... RETURNING`.
- No `RETURNING` on MySQL (MariaDB: `INSERT`/`DELETE` only). `executeWithResult` reports the `AUTO_INCREMENT` id.
- DDL statements commit implicitly, also inside a transaction.
- Affected rows are the *matched* rows: an `UPDATE` that changes nothing still counts the row.

## SQLite

```kotlin
val db = SQLite(url = "sqlite://app.db") // created if missing
// Android: SQLite(context, "app.db") or sqlite(context, url, options): a relative name goes to the app's databases dir.
```

- URLs: `sqlite::memory:`, `sqlite:data.db`, `sqlite://data.db`, `sqlite:///abs/path/data.db`, with `?mode=ro|rw|rwc`,
  `cache=shared|private`. On the JVM a `jdbc:sqlite:` URL works too.
- In memory: `ConnectionPool.Options(minConnections = 1, maxConnections = 1)`, a single connection (larger pools are
  rejected on the JVM and Android).
- File databases run in WAL mode. Writes are serialized whatever the pool size: batch many writes in one transaction.
- No schemas, no isolation levels (always serializable). On Android: no savepoints.
- Backed by sqlx (native), sqlite-jdbc + HikariCP (JVM), `android.database.sqlite` (Android, minSdk 26).

## SQLCipher (encrypted SQLite)

```kotlin
val db = SQLiteCipher(url = "sqlite://secure.db", password = "a-strong-passphrase")
```

- Same URLs as SQLite; the same Rust core on every target (FFI on native, JNI on JVM/Android). Android takes a
  `Context` too.
- An empty password opens an unencrypted database. A passphrase cannot contain NUL: hex-encode random key bytes.
- Savepoints are supported everywhere.
