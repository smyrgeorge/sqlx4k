# SQLCipher

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-sqlite-cipher:x.y.z")
```

Encrypted SQLite via [SQLCipher](https://www.zetetic.net/sqlcipher/). Unlike [`sqlx4k-sqlite`](sqlite.md), every
target, native (FFI) and JVM/Android (JNI), is backed by the same Rust [sqlx](https://github.com/launchbadge/sqlx) +
SQLCipher core, so the behavior is the same on every platform.

## Connecting

```kotlin
val db = SQLiteCipher(
    url = "sqlite://test.db", // If not found, a new (encrypted) db will be created.
    password = "a-strong-passphrase",
    options = options // see Connection pool
)
```

- The URL forms are the same as for [SQLite](sqlite.md#url-format).
- The `password` is applied as the SQLCipher `PRAGMA key`, and the database file is created encrypted on first use.
- An empty password opens an unencrypted database, which is useful for migrating an existing plaintext file.
- A passphrase must not contain a NUL character (U+0000). If your key is made of random bytes, encode them as hex (or
  Base64) instead.

In a multiplatform setup, use the `sqliteCipher(...)` function instead. On Android it additionally takes a `Context`,
used to resolve a relative database filename into the app's private storage.

Unlike the plain SQLite driver on Android, savepoints are supported on every target.
