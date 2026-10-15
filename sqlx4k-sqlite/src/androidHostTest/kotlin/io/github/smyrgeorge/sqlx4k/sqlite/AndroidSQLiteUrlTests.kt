@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class AndroidSQLiteUrlTests : SQLiteTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dir: File = File(context.cacheDir, "url-tests").apply { mkdirs() }

    private fun options(maxConnections: Int) = ConnectionPool.Options.builder()
        .maxConnections(maxConnections)
        .build()

    @Test
    fun `relative name with parameters lands in the databases directory`() = runBlocking {
        val name = "relative-${System.nanoTime()}.db"
        val db = SQLite(context = context, url = "sqlite://$name?mode=rwc", options = options(1))
        try {
            db.execute("create table t(x integer);").getOrThrow()
            db.execute("insert into t values (1);").getOrThrow()
            assertAll {
                assertThat(db.fetchAll("select count(*) from t;").getOrThrow().first().get(0).asLong()).isEqualTo(1L)
                assertThat(context.getDatabasePath(name).exists()).isTrue()
                assertThat(context.getDatabasePath("$name?mode=rwc").exists()).isFalse()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `parameters android cannot honour are rejected`() {
        listOf("vfs=unix", "immutable=1").forEach { query ->
            val e = assertFailsWith<SQLError> {
                SQLite(context = context, url = "sqlite://$dir/unsupported.db?$query", options = options(1))
            }
            assertAll {
                assertThat(e.code).isEqualTo(SQLError.Code.Pool)
                assertThat(e.message!!.contains(query.substringBefore('='))).isTrue()
                assertThat(File(dir, "unsupported.db?$query").exists()).isFalse()
                assertThat(File(dir, "unsupported.db").exists()).isFalse()
            }
        }
    }
}
