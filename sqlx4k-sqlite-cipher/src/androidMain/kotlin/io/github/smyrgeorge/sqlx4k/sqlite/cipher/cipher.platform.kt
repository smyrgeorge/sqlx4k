package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import android.content.Context
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import java.io.File

actual fun sqliteCipher(
    url: String,
    password: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): ISQLiteCipher {
    throw IllegalStateException(
        "On Android, you must use sqliteCipher(context, url, password, options, encoders) " +
                "which requires a Context to resolve the database file location."
    )
}

/**
 * Creates an encrypted SQLite (SQLCipher) driver on Android.
 *
 * URL forms:
 * - `sqlite::memory:` — in-memory database (not persisted).
 * - `sqlite:name.db` — a relative name, resolved into the app's private database directory via
 *   [Context.getDatabasePath].
 * - `sqlite:/absolute/path.db` — an absolute filesystem path, used as-is.
 *
 * @param context Android context used to resolve relative database filenames.
 * @param url The SQLite connection URL.
 * @param password The SQLCipher passphrase, applied as `PRAGMA key`. Pass an empty string for an
 * unencrypted database.
 * @param options Connection pool configuration.
 * @param encoders Optional registry of value encoders used when binding query parameters.
 */
fun sqliteCipher(
    context: Context,
    url: String,
    password: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLiteCipher = SQLiteCipher(resolveAndroidDatabaseUrl(context, url), password, options, encoders)

/**
 * Resolves a user-supplied URL into a concrete `sqlite:` URL the Rust core can open, mapping a
 * relative filename to the app's private database directory and ensuring its parent exists.
 */
private fun resolveAndroidDatabaseUrl(context: Context, url: String): String {
    val normalized = url.removePrefix("jdbc:")
    if (normalized.contains(":memory:")) return normalized

    val afterScheme = normalized.removePrefix("sqlite:")
    val queryIndex = afterScheme.indexOf('?')
    val rawPath = if (queryIndex >= 0) afterScheme.take(queryIndex) else afterScheme
    val query = if (queryIndex >= 0) afterScheme.substring(queryIndex) else ""

    val file = if (rawPath.startsWith("/")) File(rawPath) else context.getDatabasePath(rawPath)
    file.parentFile?.mkdirs()
    return "sqlite:${file.absolutePath}$query"
}
