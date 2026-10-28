# MySQL / MariaDB

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-mysql:x.y.z")
```

The same driver serves both MySQL and MariaDB. On native targets it is backed by
[sqlx](https://github.com/launchbadge/sqlx) (Rust), on the JVM by [r2dbc-mysql](https://github.com/asyncer-io/r2dbc-mysql).

## Connecting

```kotlin
val db = MySQL(
    url = "mysql://localhost:13306/test",
    username = "mysql",
    password = "mysql",
    options = options // see Connection pool
)
```

In a multiplatform setup, use the `mySQL(...)` function instead, which takes the same parameters.

The connection URL follows the pattern described by
[MySQL](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-jdbc-url-format.html):

```text
mysql://[host][:port][/database][?properties]
```

The port defaults to 3306. The supported properties are the ones the native driver (sqlx) reads: `ssl-mode`
(`disabled`, `preferred`, `required`, `verify_ca`, `verify_identity`), `ssl-ca`, `ssl-cert`, `ssl-key`, `socket` and
`timezone` (the session time zone, UTC by default), as well as r2dbc's `ssl=true|false`. `charset`, `collation` and
`statement-cache-capacity` are accepted and have no effect on the JVM. Any other property is rejected.

See [Connection pool](../guide/connection-pool.md) for the pool `options`.

## Notes

- `executeWithResult` reports the `AUTO_INCREMENT` id of an inserted row; for a multi-row insert, the first generated
  id (see [Running queries](../guide/queries.md#executewithresult)).
- MySQL and MariaDB have no `UPDATE ... RETURNING`, so the generated `update()` re-selects the row afterwards (see
  [Optimistic locking](../codegen/entities.md#optimistic-locking-with-version)).
- Pick the `mysql` or `mariadb` dialect for the code generator: `mariadb` uses `INSERT ... RETURNING` (MariaDB 10.5+)
  for `insert()` (see [Code generation](../codegen/index.md#dialects)).
- On MySQL and MariaDB the drivers report the *matched* rows as affected, so a `batchUpdate` entity whose values did
  not change still counts as updated.

!!! note "Cancelling a streamed query (native targets)"

    A `fetch` flow of a `Connection` or `Transaction` whose collector is cancelled stops between rows: the row being
    read is read to its end first, as a read stopped midway would leave the connection out of step with the server. So
    the cancellation waits for that row, e.g. until a lock it waits for is released (up to `innodb_lock_wait_timeout`).
    The rest of the result is read and discarded on the connection's next use. A flow of the pool closes its connection
    instead, and stops at once.
