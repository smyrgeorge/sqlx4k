@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class SQLiteMigratorTests : SQLiteTestBase() {

    private val db = testSQLite("SQLiteMigratorTests")

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private val fs = SystemFileSystem

    private fun newTempDir(prefix: String): Path {
        val dir = Path(("./build/tmp/migrator-tests/$prefix-${Random.nextLong().absoluteValue}"))
        fs.createDirectories(dir, mustCreate = false)
        return dir
    }

    private fun write(dir: Path, name: String, content: String) {
        val p = Path("$dir/$name")
        val buffer = Buffer().also { it.write(content.encodeToByteArray()) }
        val sink = fs.sink(p)
        sink.write(buffer, buffer.size)
        sink.flush()
        sink.close()
        buffer.close()
    }

    private fun listApplied(table: String): List<Long> = runBlocking {
        val rows = db.fetchAll("SELECT version FROM $table ORDER BY version;").getOrThrow()
        rows.map { it.get(0).asLong() }
    }

    private fun countUsers(table: String): Long = runBlocking {
        db.fetchAll("SELECT count(*) FROM $table;").getOrThrow().first().get(0).asLong()
    }

    @Test
    fun `migrate happy path and idempotent`(): Unit = runBlocking {
        // A table of its own: the JVM and native suites run in parallel against the same database.
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("sqlite-happy")
            write(
                dir, "1_create_table.sql", """
            CREATE TABLE IF NOT EXISTS $users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL
            );
        """.trimIndent()
            )
            write(
                dir, "2_insert.sql", """
            INSERT INTO $users(name) VALUES ('alice');
            INSERT INTO $users(name) VALUES ('bob');
        """.trimIndent()
            )

            assertThat(db.migrate(path = dir.toString(), table = table)).isSuccess()
            assertAll {
                assertThat(listApplied(table)).containsExactly(1L, 2L)
                assertThat(countUsers(users)).isEqualTo(2L)
            }

            // Re-run should be idempotent
            assertThat(db.migrate(path = dir.toString(), table = table)).isSuccess()
            assertAll {
                assertThat(listApplied(table)).containsExactly(1L, 2L)
                assertThat(countUsers(users)).isEqualTo(2L)
            }
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }

    @Test
    fun `duplicate version files should fail`(): Unit = runBlocking {
        val dir = newTempDir("sqlite-dup")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "1_b.sql", "select 1;")
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    @Test
    fun `non-monotonic versions should fail`(): Unit = runBlocking {
        val dir = newTempDir("sqlite-gap")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "3_c.sql", "select 1;")
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    @Test
    fun `empty migration file should fail`(): Unit = runBlocking {
        val dir = newTempDir("sqlite-empty")
        write(dir, "1_empty.sql", "\n   \n\t\n")
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    // A migration retired by leaving only a comment in its file (the versions may have no gap) is still applied on a
    // new database.
    @Test
    fun `migration file of only comments is applied`(): Unit = runBlocking {
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val dir = newTempDir("sqlite-comments")
        write(dir, "1_a.sql", "CREATE TABLE $users (id INTEGER PRIMARY KEY);")
        write(dir, "2_retired.sql", "-- Retired: the table it created is no longer used.\n/* nothing; to run */\n")
        write(dir, "3_c.sql", "INSERT INTO $users(id) VALUES (1);")
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        try {
            assertThat(db.migrate(path = dir.toString(), table = table)).isSuccess()
            assertAll {
                assertThat(listApplied(table)).containsExactly(1L, 2L, 3L)
                assertThat(countUsers(users)).isEqualTo(1L)
            }
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }

    @Test
    fun `checksum mismatch should fail on re-run`(): Unit = runBlocking {
        // A table of its own: the JVM and native suites run in parallel against the same database.
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val table = "_sqlx4k_migr_sqlite_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("sqlite-chk")
            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists $users (id integer primary key autoincrement, name text not null);"
            )
            db.migrate(path = dir.toString(), table = table).getOrThrow()

            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists $users (id integer primary key autoincrement, name text not null); -- changed\n"
            )

            val res = db.migrate(path = dir.toString(), table = table)
            assertThat(res).isFailure()
            val ex = res.exceptionOrNull() as SQLError
            assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }

    // A trigger's body holds several statements separated by `;`: the migration must keep it in one statement.
    @Test
    fun `migration with a trigger body`(): Unit = runBlocking {
        val rnd = Random.nextInt(1_000_000)
        val table = "_sqlx4k_migr_sqlite_trg_$rnd"
        val dir = newTempDir("sqlite-trigger")
        write(
            dir, "1_trigger.sql", """
            CREATE TABLE t_items_$rnd (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            CREATE TABLE t_audit_$rnd (n INTEGER NOT NULL);
            INSERT INTO t_audit_$rnd (n) VALUES (0);
            CREATE TRIGGER t_count_$rnd AFTER INSERT ON t_items_$rnd
            BEGIN
                UPDATE t_audit_$rnd SET n = n + 1;
                UPDATE t_audit_$rnd SET n = n + CASE WHEN NEW.name = 'b' THEN 10 ELSE 0 END;
            END;
            -- a trailing comment;
        """.trimIndent()
        )

        assertThat(db.migrate(path = dir.toString(), table = table)).isSuccess()
        db.execute("INSERT INTO t_items_$rnd (name) VALUES ('a'), ('b');").getOrThrow()
        assertThat(db.fetchAll("SELECT n FROM t_audit_$rnd;").getOrThrow().first().get(0).asLong()).isEqualTo(12L)
    }
}
