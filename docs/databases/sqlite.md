# SQLite

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-sqlite:x.y.z")
```

The SQLite driver runs on every [supported target](../platforms.md), including Android:

| Platform | Backed by                                                                                                   |
|----------|-------------------------------------------------------------------------------------------------------------|
| Native   | [sqlx](https://github.com/launchbadge/sqlx) (Rust), through Kotlin/Native FFI.                              |
| JVM      | [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc), pooled with HikariCP (transitive dependencies). |
| Android  | The platform's `android.database.sqlite`, so no native libraries need to be added (minSdk 26).              |

Need encryption? See [SQLCipher](sqlcipher.md).

## Connecting

```kotlin
val db = SQLite(
    url = "sqlite://test.db", // If the `test.db` file is not found, a new db will be created.
    options = options // see Connection pool
)
```

In a multiplatform setup, use the `sqlite(url, options)` function instead. On Android, use the
`sqlite(context, url, options)` overload (or `SQLite(context, url, options)`): the `Context` resolves a relative
database filename into the app's databases directory.

## URL format

Every target accepts the same sqlx-style URL, so a single string works everywhere:

| URL                              | Opens                                         |
|----------------------------------|-----------------------------------------------|
| `sqlite::memory:`                | An in-memory database.                        |
| `sqlite:data.db`                 | The file `data.db` in the current directory.  |
| `sqlite://data.db`               | Same as above.                                |
| `sqlite:///abs/path/data.db`     | The file at an absolute path.                 |
| `sqlite://data.db?mode=ro`       | `data.db` for read-only access.               |
| `sqlite:///abs/data.db?mode=rwc` | Read-write, creating the file if missing.     |
| `sqlite::memory:?cache=shared`   | An in-memory database with a shared cache.    |

The query parameters are the ones SQLite defines for URI filenames: `mode` (`ro`, `rw`, `rwc`, `memory`), `cache`
(`shared`, `private`), `immutable` and `vfs`. Any other parameter is rejected, so a bad URL fails the same way on every
target.

- **JVM:** a `jdbc:sqlite:` URL is accepted as well and skips that check, so JDBC-style URLs with driver-specific
  parameters (e.g. xerial pragmas such as `?journal_mode=WAL`) keep working. HikariCP raises timeouts below its
  minimums: `acquireTimeout` to 250 ms, `idleTimeout` to 10 seconds and `maxLifetime` to 30 seconds.
- **Android:** a relative filename lives in the app's databases directory (`Context.getDatabasePath`). `cache` is
  accepted but ignored, and `immutable` and `vfs` cannot be honoured, so they are rejected. A URL without the
  `sqlite:` prefix is prefixed automatically.

## In-memory databases

An in-memory SQLite database is private to the connection that opened it, so an in-memory database must use a pool of
a single connection; on the JVM and Android a larger pool is rejected:

```kotlin
val db = SQLite(
    url = "sqlite::memory:",
    options = ConnectionPool.Options(minConnections = 1, maxConnections = 1)
)
```

## WAL mode

File-based databases are opened in [Write-Ahead Logging (WAL)](https://www.sqlite.org/wal.html) mode, which lets
readers run concurrently with a writer. In-memory databases do not support WAL.

## Tips and limitations

- **Write concurrency:** SQLite locks the whole database for a write, so writes are serialized whatever the pool size.
  A larger pool helps read-heavy workloads; a write-heavy workload gains little from more connections.
- **Batch writes in transactions:** many small writes are much faster inside a single
  [transaction](../guide/transactions.md).
- **`executeWithResult`** reports the rowid of an inserted row; for a multi-row insert, the last one.
- **Foreign keys in migrations:** see the note in [Migrations](../guide/migrations.md).
- **Android:** savepoints (`savepoint`/`releaseSavepoint`/`rollbackToSavepoint`) are not supported, because
  `android.database.sqlite` intercepts `ROLLBACK TO SAVEPOINT` and ends the whole transaction. Use
  [SQLCipher](sqlcipher.md) (`sqlx4k-sqlite-cipher`) if you need savepoints on Android.

For complete working examples, see [examples/sqlite](https://github.com/smyrgeorge/sqlx4k/tree/main/examples/sqlite).
