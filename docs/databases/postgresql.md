# PostgreSQL

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres:x.y.z")
```

On native targets the driver is backed by [sqlx](https://github.com/launchbadge/sqlx) (Rust), on the JVM by
[r2dbc-postgresql](https://github.com/pgjdbc/r2dbc-postgresql).

## Connecting

```kotlin
val db = PostgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
    options = options // see Connection pool
)
```

In a multiplatform setup, use the `postgreSQL(...)` function instead, which takes the same parameters.

The URL scheme can be either `postgresql://` or `postgres://`, and each of the URL parts is optional:

```text
postgresql://
postgresql://localhost
postgresql://localhost:5433
postgresql://localhost/mydb
```

See [Connection pool](../guide/connection-pool.md) for the pool `options`.

## LISTEN / NOTIFY

```kotlin
// listen suspends while it listens: run it in a coroutine of its own, and cancel that to stop listening.
val listener = launch {
    db.listen("chan0") { notification: Postgres.Notification ->
        println(notification)
    }
}

(1..10).forEach {
    db.notify("chan0", "Hello $it")
    delay(1000)
}

listener.cancel() // Stops listening and closes the listener's connection.
```

`listen` also accepts a list of channels.

## Notes

- `executeWithResult` always reports a `null` `lastInsertId` on PostgreSQL: use `INSERT ... RETURNING id` with
  `fetchAll` instead (see [Running queries](../guide/queries.md#executewithresult)).
- Set the code generator's dialect to `postgresql` to enable the PostgreSQL array decoders (see
  [Code generation](../codegen/index.md#dialects)). The [Gradle plugin](../getting-started/gradle-plugin.md) does this
  for you.
- For message queues on top of PostgreSQL, see [PGMQ](../extensions/pgmq.md).
