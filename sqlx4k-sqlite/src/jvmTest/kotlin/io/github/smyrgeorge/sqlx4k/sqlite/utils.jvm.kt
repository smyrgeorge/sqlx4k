package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import java.io.File

internal actual val testPlatform: TestPlatform = TestPlatform.Jvm

internal actual val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

actual abstract class SQLiteTestBase actual constructor()

internal actual val testDirectory: String = File("build/tmp/sqlite-tests-jvm").absoluteFile.apply { mkdirs() }.path

internal actual fun openSQLite(url: String, options: ConnectionPool.Options, encoders: ValueEncoderRegistry): ISQLite =
    sqlite(url = url, options = options, encoders = encoders)
