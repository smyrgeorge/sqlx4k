package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual val testPlatform: TestPlatform = TestPlatform.Native

@OptIn(ExperimentalNativeApi::class)
internal actual val isWindows: Boolean = Platform.osFamily == OsFamily.WINDOWS

actual abstract class SQLiteTestBase actual constructor()

internal actual val testDirectory: String = Path("./build/tmp/sqlite-tests").let {
    SystemFileSystem.createDirectories(it, mustCreate = false)
    SystemFileSystem.resolve(it).toString()
}

internal actual fun openSQLite(url: String, options: ConnectionPool.Options, encoders: ValueEncoderRegistry): ISQLite =
    sqlite(url = url, options = options, encoders = encoders)
