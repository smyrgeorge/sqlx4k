@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite.cipher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Proves SQLCipher is actually encrypting the database file (not just accepting a key): the raw
 * file must not contain the plaintext marker nor the standard SQLite header, the correct key reads
 * the data back, and the wrong key is rejected.
 */
class JvmSQLiteCipherEncryptionTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    @Test
    fun `database file is encrypted and requires the key`() = runBlocking {
        val file = File.createTempFile("sqlx4k_cipher_enc", ".db").also { it.delete() }
        val url = "sqlite:${file.absolutePath}"
        val key = "correct horse battery staple"
        val table = "secrets"
        try {
            // 1) Create the encrypted database and write a recognizable plaintext value.
            val db1 = sqliteCipher(url, password = key, options = options)
            db1.execute("create table $table(id integer primary key, secret text)").getOrThrow()
            db1.execute("insert into $table(id, secret) values (1, 'TOP_SECRET_MARKER')").getOrThrow()
            db1.close().getOrThrow()

            // 2) The raw bytes must not reveal the plaintext nor the unencrypted SQLite header.
            val raw = file.readBytes().decodeToString()
            assertThat(raw.contains("TOP_SECRET_MARKER")).isFalse()
            assertThat(raw.startsWith("SQLite format 3")).isFalse()

            // 3) Reopening with the correct key reads the value back.
            val db2 = sqliteCipher(url, password = key, options = options)
            val row = db2.fetchAll("select secret from $table where id = 1").getOrThrow().first()
            assertThat(row.get("secret").asString()).isEqualTo("TOP_SECRET_MARKER")
            db2.close().getOrThrow()

            // 4) Reopening with the wrong key fails (at open or on first read).
            val wrong = runCatching {
                val db3 = sqliteCipher(url, password = "the-wrong-key", options = options)
                db3.fetchAll("select secret from $table where id = 1").getOrThrow()
            }
            assertThat(wrong.isFailure).isTrue()
        } finally {
            file.delete()
        }
    }
}
