package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

actual fun sqlite(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
): ISQLite {
    throw IllegalStateException(
        "On Android, you must use sqlite(context, url, options, encoders) function " +
                "which requires a Context parameter for database initialization."
    )
}

/**
 * Creates an SQLite instance on Android using the provided Context.
 * The Context is required to open or create the database file.
 *
 * @param context The Android Context to use for database operations.
 * @param url The URL of the SQLite database to connect to.
 * @param options Optional pool configuration.
 * @param encoders Optional registry of value encoders.
 */
fun sqlite(
    context: Context,
    url: String,
    options: ConnectionPool.Options = ConnectionPool.Options(),
    encoders: ValueEncoderRegistry = ValueEncoderRegistry(),
): ISQLite = SQLite(context, url, options, encoders)
