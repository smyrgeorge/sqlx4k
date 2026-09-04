@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite.Companion.toJdbcUrl
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class JvmSQLiteUrlTests {

    private val dir: File = File("build/tmp/url-tests").absoluteFile.apply { mkdirs() }

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private fun options(maxConnections: Int) = ConnectionPool.Options.builder()
        .maxConnections(maxConnections)
        .build()

    private val runner = CommonSQLiteUrlTests(dir.path) { url, max -> SQLite(url = url, options = options(max)) }

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
    fun `immutable opens an existing file read-only`() {
        runner.`immutable opens an existing file read-only`()
    }

    @Test
    fun `vfs parameter is honoured`() {
        if (isWindows) return // the default VFS is `win32` there
        runner.`vfs parameter is honoured`("unix")
    }

    @Test
    fun `unknown vfs is rejected`() {
        runner.`unknown vfs is rejected`()
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
    fun `relative path is resolved against the working directory`() {
        runner.`relative path is resolved against the working directory`()
    }

    @Test
    fun `invalid mode is rejected`() {
        runner.`invalid mode is rejected`()
    }

    @Test
    fun `unknown parameter is rejected`() {
        runner.`unknown parameter is rejected`()
    }

    // ── JVM specific: the sqlx-style URL -> JDBC URL translation ────────────────────────────────

    private fun jdbc(url: String): String = SQLiteUrl.parse(url.removePrefix("jdbc:")).toJdbcUrl()

    @Test
    fun `urls without parameters are translated to plain jdbc paths`() {
        assertAll {
            assertThat(jdbc("sqlite::memory:")).isEqualTo("jdbc:sqlite::memory:")
            assertThat(jdbc("sqlite:data.db")).isEqualTo("jdbc:sqlite:data.db")
            assertThat(jdbc("sqlite://data.db")).isEqualTo("jdbc:sqlite:data.db")
            assertThat(jdbc("sqlite:///abs/data.db")).isEqualTo("jdbc:sqlite:/abs/data.db")
            assertThat(jdbc("data.db")).isEqualTo("jdbc:sqlite:data.db")
            assertThat(jdbc("jdbc:sqlite:data.db")).isEqualTo("jdbc:sqlite:data.db")
            assertThat(jdbc("jdbc:sqlite::memory:")).isEqualTo("jdbc:sqlite::memory:")
        }
    }

    @Test
    fun `urls with parameters are translated to file uris`() {
        assertAll {
            assertThat(jdbc("sqlite:///home/tech/Music/video/test.db?mode=rwc"))
                .isEqualTo("jdbc:sqlite:file:/home/tech/Music/video/test.db?mode=rwc")
            assertThat(jdbc("sqlite://data.db?mode=ro")).isEqualTo("jdbc:sqlite:file:data.db?mode=ro")
            assertThat(jdbc("sqlite:data.db?mode=ro&cache=shared"))
                .isEqualTo("jdbc:sqlite:file:data.db?mode=ro&cache=shared")
            assertThat(jdbc("sqlite::memory:?cache=shared")).isEqualTo("jdbc:sqlite:file::memory:?cache=shared")
        }
    }

    @Test
    fun `explicit file uris are passed through unchanged`() {
        assertAll {
            assertThat(jdbc("jdbc:sqlite:file:/abs/data.db?mode=rwc")).isEqualTo("jdbc:sqlite:file:/abs/data.db?mode=rwc")
            assertThat(jdbc("sqlite:file:/abs/data.db?mode=rwc")).isEqualTo("jdbc:sqlite:file:/abs/data.db?mode=rwc")
            assertThat(jdbc("jdbc:sqlite:file::memory:?cache=shared")).isEqualTo("jdbc:sqlite:file::memory:?cache=shared")
        }
    }

    @Test
    fun `jdbc style url with driver specific parameters works end to end`() = runBlocking {
        // The workaround suggested in the issue must keep working, and a `jdbc:` URL may carry
        // driver-specific parameters (here a xerial pragma) that an sqlx-style URL would reject.
        val name = "jdbc-${System.nanoTime()}.db"
        val db = SQLite(url = "jdbc:sqlite:file:$dir/$name?mode=rwc&busy_timeout=1000", options = options(1))
        try {
            db.execute("create table t(x integer);").getOrThrow()
            db.execute("insert into t values (1);").getOrThrow()
            assertAll {
                assertThat(db.fetchAll("select count(*) from t;").getOrThrow().first().get(0).asLong()).isEqualTo(1L)
                assertThat(File(dir, name).exists()).isTrue()
                assertThat(File(dir, "$name?mode=rwc").exists()).isFalse()
            }
        } finally {
            db.close()
            listOf("", "-wal", "-shm").forEach { File(dir, "$name$it").delete() }
        }
    }
}
