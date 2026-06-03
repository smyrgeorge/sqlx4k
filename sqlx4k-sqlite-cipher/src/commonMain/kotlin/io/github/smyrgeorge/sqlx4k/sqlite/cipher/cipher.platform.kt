package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

/**
 * Creates an encrypted SQLite ([SQLCipher]) driver.
 *
 * @param url The SQLite connection URL, e.g. `sqlite:data.db`, `sqlite://data.db`,
 * `sqlite:///abs/path.db` or `sqlite::memory:`.
 * @param password The SQLCipher passphrase, applied as `PRAGMA key`. Pass an empty string to open
 * an unencrypted database — useful for migrating an existing plaintext file.
 * @param options Connection pool configuration.
 * @param encoders Optional registry of value encoders used when binding query parameters.
 *
 * Note: on Android, use the `sqliteCipher(context, ...)` overload instead — a `Context` is
 * required to resolve a relative database filename to the app's private storage.
 */
expect fun sqliteCipher(
    url: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLiteCipher
