package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import java.io.File

actual abstract class SQLiteCipherTestBase actual constructor()

internal actual val testDirectory: String = File("build/tmp/sqlite-cipher-tests").absoluteFile.apply { mkdirs() }.path

internal actual fun openSQLiteCipher(url: String, options: ConnectionPool.Options, encoders: ValueEncoderRegistry): ISQLiteCipher =
    sqliteCipher(url = url, password = TEST_PASSPHRASE, options = options, encoders = encoders)
