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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSQLiteUrlTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dir: File = File(context.cacheDir, "url-tests").apply { mkdirs() }

    private fun options(maxConnections: Int) = ConnectionPool.Options.builder()
        .maxConnections(maxConnections)
        .build()

    private val runner = CommonSQLiteUrlTests(dir.absolutePath) { url, max ->
        SQLite(context = context, url = url, options = options(max))
    }

    // ── Shared behavioural tests ────────────────────────────────────────────────────────────────

    @Test
    fun `mode=rwc with absolute path creates the file and not one named after the whole query`() {
        runner.`mode=rwc with absolute path creates the file and not one named after the whole query`()
    }

    @Test
    fun `default mode creates the file`() {
        runner.`default mode creates the file`()
    }

    @Test
    fun `mode=ro opens an existing file read-only`() {
        runner.`mode=ro opens an existing file read-only`()
    }

    @Test
    fun `mode=rw opens an existing file read-write`() {
        runner.`mode=rw opens an existing file read-write`()
    }

    @Test
    fun `mode=ro does not create a missing file`() {
        runner.`mode=ro does not create a missing file`()
    }

    @Test
    fun `mode=rw does not create a missing file`() {
        runner.`mode=rw does not create a missing file`()
    }

    @Test
    fun `mode=memory does not touch the file system`() {
        runner.`mode=memory does not touch the file system`()
    }

    @Test
    fun `in-memory database works`() {
        runner.`in-memory database works`()
    }

    @Test
    fun `in-memory database with shared cache works`() {
        runner.`in-memory database with shared cache works`()
    }

    @Test
    fun `in-memory database with pool larger than one is rejected`() {
        runner.`in-memory database with pool larger than one is rejected`()
    }

    @Test
    fun `cache parameter on a file database is accepted`() {
        runner.`cache parameter on a file database is accepted`()
    }

    @Test
    fun `writable file database uses WAL`() {
        runner.`writable file database uses WAL`()
    }

    @Test
    fun `mode=ro works on a database that is not in WAL mode`() {
        runner.`mode=ro works on a database that is not in WAL mode`()
    }

    @Test
    fun `path with a space works`() {
        runner.`path with a space works`()
    }

    @Test
    fun `invalid mode is rejected`() {
        runner.`invalid mode is rejected`()
    }

    @Test
    fun `unknown parameter is rejected`() {
        runner.`unknown parameter is rejected`()
    }

    // ── Android specific ────────────────────────────────────────────────────────────────────────

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
