package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator

/**
 * Driver contract for the encrypted (SQLCipher) SQLite backend.
 *
 * Identical surface to the plain SQLite driver, but every target — native (FFI/cinterop) and
 * JVM/Android (JNI) — is backed by the same Rust `sqlx` + SQLCipher core, so behavior is uniform
 * across platforms. The encryption key is supplied as a `password` to the [sqliteCipher] factory
 * and applied as `PRAGMA key` when the connection pool is opened.
 */
interface ISQLiteCipher : Driver, Migrator.Driver
