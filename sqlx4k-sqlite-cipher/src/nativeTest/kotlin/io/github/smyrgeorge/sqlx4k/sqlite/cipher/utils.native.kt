package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual abstract class SQLiteCipherTestBase actual constructor()

internal actual val testDirectory: String = Path("./build/tmp/sqlite-cipher-tests-native").let {
    SystemFileSystem.createDirectories(it, mustCreate = false)
    SystemFileSystem.resolve(it).toString()
}

internal actual fun openSQLiteCipher(
    url: String,
    options: ConnectionPool.Options,
    encoders: ValueEncoderRegistry,
    password: String
): ISQLiteCipher = sqliteCipher(url = url, password = password, options = options, encoders = encoders)
