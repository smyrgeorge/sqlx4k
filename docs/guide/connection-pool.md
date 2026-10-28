# Connection pool

Every driver manages a pool of connections. A query issued directly on the driver (`db.fetchAll(...)`) borrows a
connection for its duration and returns it; `db.acquire()` and `db.begin()` hold one until it is released, committed
or rolled back. The pool is configured with `ConnectionPool.Options`, passed to the driver's constructor.

## Configuration options

| Option           | Default        | What it controls                                                                                                                                                                          |
|------------------|----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `minConnections` | driver default | The number of connections the pool keeps open at all times, so they are ready during a burst instead of being opened on demand. Must not exceed `maxConnections`.                         |
| `maxConnections` | `10`           | The upper bound on open connections. Size it to what the database can serve: once reached, callers wait for a connection to be returned.                                                  |
| `acquireTimeout` | driver default | How long a caller waits for a free connection before the operation fails with `SQLError.Code.PoolTimedOut`. Set it so a saturated pool surfaces as a timely error rather than a hang.     |
| `idleTimeout`    | driver default | How long an unused connection may stay in the pool before it is closed, letting the pool shrink back after a burst. Must not exceed `maxLifetime`.                                        |
| `maxLifetime`    | driver default | The maximum age of a connection. Once reached it is closed and replaced, even if healthy, which keeps long-lived connections from accumulating server-side state or outliving a failover. |

An option left unset keeps the default of the underlying driver (sqlx on native targets, the R2DBC pool on the JVM).
Every value must be positive, `minConnections` must not exceed `maxConnections` and `idleTimeout` must not exceed
`maxLifetime`; a violation fails at construction, before any connection is opened.

```kotlin
val options = ConnectionPool.Options.builder()
    .minConnections(2)
    .maxConnections(10)
    .acquireTimeout(10.seconds)
    .idleTimeout(10.minutes)
    .maxLifetime(30.minutes)
    .build()
```

Then pass the options to the driver:

=== "PostgreSQL"

    ```kotlin
    val db = PostgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )
    ```

=== "MySQL / MariaDB"

    ```kotlin
    val db = MySQL(
        url = "mysql://localhost:13306/test",
        username = "mysql",
        password = "mysql",
        options = options
    )
    ```

=== "SQLite"

    ```kotlin
    val db = SQLite(
        url = "sqlite://test.db", // If the `test.db` file is not found, a new db will be created.
        options = options
    )
    ```

=== "SQLCipher"

    ```kotlin
    val db = SQLiteCipher(
        url = "sqlite://test.db", // If not found, a new (encrypted) db will be created.
        password = "a-strong-passphrase",
        options = options
    )
    ```

In a multiplatform setup, use the `postgreSQL(...)`, `mySQL(...)`, `sqlite(...)` and `sqliteCipher(...)` functions
instead. The URL formats and the notes of each driver are described in its page under
[Databases](../databases/postgresql.md).

## Acquiring and using connections

The driver provides two complementary ways to run queries:

- Directly through the database instance (recommended). Each call acquires a pooled connection, executes the work, and
  returns it to the pool automatically.
- Manually acquire a connection from the pool when you need to batch multiple operations on the same connection without
  starting a transaction.

!!! warning

    When you manually acquire a connection, you must release it to return it to the pool.

```kotlin
// Manual connection acquisition (remember to release)
val conn: Connection = db.acquire().getOrThrow()
try {
    conn.execute("insert into users(id, name) values (2, 'Bob');").getOrThrow()
    val rs = conn.fetchAll("select * from users;").getOrThrow()
    // ...
} finally {
    conn.close().getOrThrow() // Return to pool
}
```

### Setting the transaction isolation level

You can set the transaction isolation level on a connection to control the degree of visibility between concurrent
transactions.

```kotlin
val conn: Connection = db.acquire().getOrThrow()
// Set the isolation level before starting operations
conn.setTransactionIsolationLevel(Transaction.IsolationLevel.Serializable).getOrThrow()
```

The levels are `ReadUncommitted`, `ReadCommitted`, `RepeatableRead` and `Serializable`. A connection starts at the
database's default level: `ReadCommitted` on PostgreSQL, `RepeatableRead` on MySQL and MariaDB. The level applies to
the transactions begun on that connection only, and is reset to the default when the connection returns to the pool.
On SQLite the call has no effect: its transactions are always serializable.

## Pool state and shutdown

`poolSize()` returns the number of connections currently open, and `poolIdleSize()` how many of them are idle, e.g.
to export them as metrics:

```kotlin
println("Connections: ${db.poolSize()} open, ${db.poolIdleSize()} idle.")
```

Close the pool when the application shuts down: `close()` closes its connections, and any later call on it fails (on
PostgreSQL and MySQL, with an `SQLError` whose code is `PoolClosed`).

```kotlin
db.close().getOrThrow()
```
