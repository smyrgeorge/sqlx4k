@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Behavioural tests for SQLite URL handling, shared by every target.
 *
 * Regression tests for https://github.com/smyrgeorge/sqlx4k/issues/171: a URL such as
 * `sqlite:///abs/path/test.db?mode=rwc` must open `test.db` and honour `mode`, instead of opening a
 * file literally named `test.db?mode=rwc`. The remaining tests pin down the rest of the URL contract
 * (in-memory databases, `cache`, `immutable`, `vfs`, WAL, relative paths, error reporting) so that the
 * three drivers keep behaving the same.
 *
 * @param dir An absolute directory the tests may create database files in.
 * @param open Opens a driver for the given URL with the given pool size (the platform decides how,
 * e.g. Android needs a Context).
 */
class CommonSQLiteUrlTests(
    private val dir: String,
    private val open: (url: String, maxConnections: Int) -> ISQLite,
) {

    private val fs = SystemFileSystem

    private fun open(url: String): ISQLite = open(url, 1)

    private fun freshName(prefix: String): String = "$prefix-${Random.nextLong(0, Long.MAX_VALUE)}.db"
    private fun exists(name: String): Boolean = fs.exists(Path("$dir/$name"))
    private fun cleanup(name: String, query: String? = null) {
        listOf("", "-wal", "-shm", "-journal").forEach { fs.delete(Path("$dir/$name$it"), mustExist = false) }
        if (query != null) fs.delete(Path("$dir/$name?$query"), mustExist = false)
    }

    /** `dir` is absolute, so this yields the `sqlite:///abs/path/name.db?...` form from the issue. */
    private fun url(name: String, query: String? = null): String =
        "sqlite://$dir/$name" + (query?.let { "?$it" } ?: "")

    private suspend fun ISQLite.count(): Long =
        fetchAll("select count(*) from t;").getOrThrow().first().get(0).asLong()

    private suspend fun ISQLite.journalMode(): String =
        fetchAll("PRAGMA journal_mode;").getOrThrow().first().get(0).asString().lowercase()

    private suspend fun ISQLite.createAndInsert() {
        execute("create table if not exists t(x integer);").getOrThrow()
        execute("insert into t values (1);").getOrThrow()
    }

    /** Opens `name` with `query`, runs [block], asserts the file was (or was not) created, cleans up. */
    private fun opened(name: String, query: String?, expectFile: Boolean, block: suspend ISQLite.() -> Unit) =
        runBlocking {
            val db = open(url(name, query))
            try {
                db.block()
                assertAll {
                    assertThat(exists(name), "file '$name' exists").isEqualTo(expectFile)
                    if (query != null) assertThat(exists("$name?$query"), "mangled file exists").isFalse()
                }
            } finally {
                db.close()
                cleanup(name, query)
            }
        }

    /**
     * Opening `name` with `query` must fail and must not create the file. Depending on the platform the
     * failure surfaces either when the driver is constructed (native opens a connection up front) or on
     * the first statement (the JVM and Android pools connect lazily), so both are accepted.
     */
    private fun openingFails(name: String, query: String) {
        val result = runCatching {
            val db = open(url(name, query))
            try {
                runBlocking { db.execute("create table t(x integer);").getOrThrow() }
            } finally {
                runBlocking { db.close() }
            }
        }
        try {
            assertAll {
                assertThat(result).isFailure()
                assertThat(exists(name)).isFalse()
                assertThat(exists("$name?$query")).isFalse()
            }
        } finally {
            cleanup(name, query)
        }
    }

    /** A bad URL fails the same way everywhere: at construction, with an [SQLError] of code `Pool`. */
    private fun urlIsRejected(name: String, query: String) {
        val e = runCatching { open(url(name, query)) }.exceptionOrNull()
        assertAll {
            assertThat(e is SQLError && e.code == SQLError.Code.Pool, "expected SQLError(Pool) but was: $e").isTrue()
            assertThat(exists(name)).isFalse()
            assertThat(exists("$name?$query")).isFalse()
        }
    }

    // ── mode ────────────────────────────────────────────────────────────────────────────────────

    fun `mode=rwc with absolute path creates the file and not one named after the whole query`() =
        opened(freshName("rwc"), "mode=rwc", expectFile = true) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    fun `default mode creates the file`() =
        opened(freshName("default"), null, expectFile = true) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    fun `mode=ro opens an existing file read-only`() = runBlocking {
        val name = freshName("ro")
        open(url(name, "mode=rwc")).also { it.createAndInsert() }.close()
        opened(name, "mode=ro", expectFile = true) {
            assertThat(count()).isEqualTo(1L)
            assertThat(execute("insert into t values (2);")).isFailure()
        }
    }

    fun `mode=rw opens an existing file read-write`() = runBlocking {
        val name = freshName("rw")
        open(url(name, "mode=rwc")).also { it.createAndInsert() }.close()
        opened(name, "mode=rw", expectFile = true) {
            execute("insert into t values (2);").getOrThrow()
            assertThat(count()).isEqualTo(2L)
        }
    }

    fun `mode=ro does not create a missing file`() = openingFails(freshName("ro-missing"), "mode=ro")

    fun `mode=rw does not create a missing file`() = openingFails(freshName("rw-missing"), "mode=rw")

    fun `mode=memory does not touch the file system`() =
        opened(freshName("memory-mode"), "mode=memory", expectFile = false) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    // ── in-memory ───────────────────────────────────────────────────────────────────────────────

    fun `in-memory database works`() = runBlocking {
        val db = open("sqlite::memory:")
        try {
            db.createAndInsert()
            assertThat(db.count()).isEqualTo(1L)
        } finally {
            db.close()
        }
    }

    fun `in-memory database with shared cache works`() = runBlocking {
        val db = open("sqlite::memory:?cache=shared")
        try {
            db.createAndInsert()
            assertThat(db.count()).isEqualTo(1L)
        } finally {
            db.close()
        }
    }

    /** JVM and Android: every connection would get its own database, so a larger pool is refused. */
    fun `in-memory database with pool larger than one is rejected`() {
        assertFailsWith<IllegalArgumentException> { open("sqlite::memory:", 2) }
    }

    /** Native: sqlx opens in-memory databases with a shared cache, so pooled connections see one database. */
    fun `in-memory database with pool larger than one shares the database`() = runBlocking {
        val db = open("sqlite::memory:", 2)
        try {
            val a = db.acquire().getOrThrow()
            val b = db.acquire().getOrThrow()
            try {
                a.execute("create table t(x integer);").getOrThrow()
                a.execute("insert into t values (1);").getOrThrow()
                assertThat(b.fetchAll("select count(*) from t;").getOrThrow().first().get(0).asLong()).isEqualTo(1L)
            } finally {
                a.close()
                b.close()
            }
        } finally {
            db.close()
        }
    }

    // ── other parameters ────────────────────────────────────────────────────────────────────────

    fun `cache parameter on a file database is accepted`() =
        opened(freshName("cache"), "cache=private", expectFile = true) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    /** JVM and native (Android rejects `immutable`). */
    fun `immutable opens an existing file read-only`() = runBlocking {
        val name = freshName("immutable")
        open(url(name, "mode=rwc")).also { it.createAndInsert() }.close()
        opened(name, "immutable=1", expectFile = true) {
            assertThat(count()).isEqualTo(1L)
            assertThat(execute("insert into t values (2);")).isFailure()
        }
    }

    /** JVM and native (Android rejects `vfs`). [vfs] must exist on the host, e.g. `unix`. */
    fun `vfs parameter is honoured`(vfs: String) =
        opened(freshName("vfs"), "vfs=$vfs&mode=rwc", expectFile = true) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    /** JVM and native: a VFS that does not exist proves the parameter reaches SQLite. */
    fun `unknown vfs is rejected`() = openingFails(freshName("bad-vfs"), "vfs=sqlx4k-no-such-vfs")

    // ── journal mode ────────────────────────────────────────────────────────────────────────────

    fun `writable file database uses WAL`() =
        opened(freshName("wal"), "mode=rwc", expectFile = true) {
            createAndInsert()
            assertThat(journalMode()).isEqualTo("wal")
        }

    /** The WAL switch must be skipped for `mode=ro`: it writes to the header, which read-only cannot. */
    fun `mode=ro works on a database that is not in WAL mode`() = runBlocking {
        val name = freshName("ro-rollback")
        open(url(name, "mode=rwc")).also {
            it.createAndInsert()
            assertThat(it.fetchAll("PRAGMA journal_mode=DELETE;").getOrThrow().first().get(0).asString().lowercase())
                .isEqualTo("delete")
        }.close()
        opened(name, "mode=ro", expectFile = true) {
            assertThat(journalMode()).isEqualTo("delete")
            assertThat(count()).isEqualTo(1L)
            assertThat(execute("insert into t values (2);")).isFailure()
        }
    }

    // ── paths ───────────────────────────────────────────────────────────────────────────────────

    fun `path with a space works`() =
        opened("with space-${Random.nextLong(0, Long.MAX_VALUE)}.db", "mode=rwc", expectFile = true) {
            createAndInsert()
            assertThat(count()).isEqualTo(1L)
        }

    /** JVM and native (Android resolves relative names to the app's databases directory). */
    fun `relative path is resolved against the working directory`() = runBlocking {
        val name = freshName("relative")
        val db = open("sqlite:$name?mode=rwc")
        try {
            db.createAndInsert()
            assertAll {
                assertThat(db.count()).isEqualTo(1L)
                assertThat(fs.exists(Path(name))).isTrue()
                assertThat(fs.exists(Path("$name?mode=rwc"))).isFalse()
            }
        } finally {
            db.close()
            listOf("", "-wal", "-shm", "-journal", "?mode=rwc").forEach { fs.delete(Path("$name$it"), mustExist = false) }
        }
    }

    // ── errors ──────────────────────────────────────────────────────────────────────────────────

    fun `invalid mode is rejected`() = urlIsRejected(freshName("bad-mode"), "mode=bogus")

    fun `unknown parameter is rejected`() = urlIsRejected(freshName("bad-param"), "foo=bar")
}
