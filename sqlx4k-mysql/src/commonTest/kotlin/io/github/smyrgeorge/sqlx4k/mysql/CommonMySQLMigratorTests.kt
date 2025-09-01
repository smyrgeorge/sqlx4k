@file:Suppress("SqlNoDataSourceInspection", "SameParameterValue")

package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isSuccess
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random

class CommonMySQLMigratorTests(
    private val db: IMySQL
) {

    private val fs = SystemFileSystem

    private fun newTempDir(prefix: String): Path {
        val dir = Path(("./build/tmp/migrator-tests/$prefix-" + Random.nextLong().toString()))
        fs.createDirectories(dir, mustCreate = false)
        return dir
    }

    private fun write(dir: Path, name: String, content: String) {
        val p = Path("$dir/$name")
        val buffer = Buffer().also { it.write(content.encodeToByteArray()) }
        fs.sink(p).write(buffer, buffer.size)
    }

    private fun listApplied(table: String): List<Long> = runBlocking {
        val rows = db.fetchAll("SELECT version FROM $table ORDER BY version;").getOrThrow()
        rows.map { it.get(0).asLong() }
    }

    private fun countUsers(): Long = runBlocking {
        db.fetchAll("SELECT count(*) FROM t_users;").getOrThrow().first().get(0).asLong()
    }

    fun `migrate happy path and idempotent`() = runBlocking {
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS t_users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-happy")
            write(
                dir, "1_create_table.sql", """
            CREATE TABLE IF NOT EXISTS t_users (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                name TEXT NOT NULL
            );
        """.trimIndent()
            )
            write(
                dir, "2_insert.sql", """
            INSERT INTO t_users(name) VALUES ('alice');
            INSERT INTO t_users(name) VALUES ('bob');
        """.trimIndent()
            )

            assertThat(runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() })
                .isSuccess()

            assertAll {
                assertThat(listApplied(table)).containsExactly(1L, 2L)
                assertThat(countUsers()).isEqualTo(2L)
            }

            // Re-run should be idempotent
            assertThat(runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() })
                .isSuccess()
            assertAll {
                assertThat(listApplied(table)).containsExactly(1L, 2L)
                assertThat(countUsers()).isEqualTo(2L)
            }
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS t_users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }

    fun `duplicate version files should fail`() = runBlocking {
        val dir = newTempDir("mysql-dup")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "1_b.sql", "select 1;")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    fun `non-monotonic versions should fail`() = runBlocking {
        val dir = newTempDir("mysql-gap")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "3_c.sql", "select 1;")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    fun `empty migration file should fail`() = runBlocking {
        val dir = newTempDir("mysql-empty")
        write(dir, "1_empty.sql", "\n   \n\t\n")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    fun `checksum mismatch should fail on re-run`() = runBlocking {
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS t_users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-chk")
            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists t_users (id BIGINT primary key auto_increment, name text not null);"
            )
            db.migrate(path = dir.toString(), table = table).getOrThrow()

            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists t_users (id BIGINT primary key auto_increment, name text not null); -- changed\n"
            )

            val res = runCatching { db.migrate(path = dir.toString(), table = table).getOrThrow() }
            assertThat(res).isFailure()
            val ex = res.exceptionOrNull() as SQLError
            assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS t_users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }
}
