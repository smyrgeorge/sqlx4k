package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
actual abstract class SQLiteCipherTestBase actual constructor()

private val context: Context get() = ApplicationProvider.getApplicationContext()

internal actual val testDirectory: String get() = context.cacheDir.absolutePath

internal actual fun openSQLiteCipher(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
    password: String
): ISQLiteCipher = sqliteCipher(
    context = context,
    url = url,
    password = password,
    options = options,
    encoders = encoders
)
