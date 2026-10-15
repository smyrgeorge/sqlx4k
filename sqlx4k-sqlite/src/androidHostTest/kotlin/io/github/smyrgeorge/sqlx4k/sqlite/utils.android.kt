package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

internal actual val testPlatform: TestPlatform = TestPlatform.Android

internal actual val isWindows: Boolean = false

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
actual abstract class SQLiteTestBase actual constructor()

private val context: Context get() = ApplicationProvider.getApplicationContext()

internal actual val testDirectory: String get() = context.cacheDir.absolutePath

internal actual fun openSQLite(url: String, options: ConnectionPool.Options, encoders: ValueEncoderRegistry): ISQLite =
    SQLite(context = context, url = url, options = options, encoders = encoders)
