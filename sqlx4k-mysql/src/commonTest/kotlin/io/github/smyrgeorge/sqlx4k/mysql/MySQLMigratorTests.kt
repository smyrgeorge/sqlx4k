@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.mysql

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
import kotlin.test.Ignore
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class MySQLMigratorTests {

    private val db = testMySQL()

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
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-happy")
            write(
                dir, "1_create_table.sql", """
            CREATE TABLE IF NOT EXISTS $users (
                id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
        val dir = newTempDir("mysql-dup")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "1_b.sql", "select 1;")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    @Test
    fun `non-monotonic versions should fail`(): Unit = runBlocking {
        val dir = newTempDir("mysql-gap")
        write(dir, "1_a.sql", "select 1;")
        write(dir, "3_c.sql", "select 1;")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    @Test
    fun `empty migration file should fail`(): Unit = runBlocking {
        val dir = newTempDir("mysql-empty")
        write(dir, "1_empty.sql", "\n   \n\t\n")
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        val res = db.migrate(path = dir.toString(), table = table)
        runCatching { db.execute("DROP TABLE IF EXISTS $table CASCADE;").getOrThrow() }
        assertThat(res).isFailure()
        val ex = res.exceptionOrNull() as SQLError
        assertThat(ex.code).isEqualTo(SQLError.Code.Migrate)
    }

    @Test
    fun `checksum mismatch should fail on re-run`(): Unit = runBlocking {
        // A table of its own: the JVM and native suites run in parallel against the same database.
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-chk")
            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists $users (id BIGINT primary key auto_increment, name text not null);"
            )
            db.migrate(path = dir.toString(), table = table).getOrThrow()

            write(
                dir = dir,
                name = "1_create.sql",
                content = "create table if not exists $users (id BIGINT primary key auto_increment, name text not null); -- changed\n"
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

    // Additional small helper to check schema existence for schema-related tests.
    private fun schemaExists(schema: String): Boolean = runBlocking {
        val sql = "SELECT 1 FROM information_schema.schemata WHERE schema_name = '$schema' LIMIT 1;"
        db.fetchAll(sql).getOrThrow().size > 0
    }

    // Additional small helper to check if a table exists in MySQL catalog (optionally qualified by schema)
    private fun tableExists(qualified: String): Boolean = runBlocking {
        val parts = qualified.split('.')
        val (sch, tbl) = if (parts.size == 2) parts[0] to parts[1] else null to parts[0]
        val sql = if (sch != null) {
            """
            SELECT 1 FROM information_schema.tables WHERE table_schema = '$sch' AND table_name = '$tbl' LIMIT 1;
            """.trimIndent()
        } else {
            // current database
            """
            SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = '$tbl' LIMIT 1;
            """.trimIndent()
        }
        db.fetchAll(sql).getOrThrow().size > 0
    }

    @Test
    fun `file level transaction rollback on failure`(): Unit = runBlocking {
        // A table of its own: the JVM and native suites run in parallel against the same database.
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val table = "_sqlx4k_migr_mysql_${Random.nextInt(100000)}"
        // Pre-clean
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-rollback")
            write(
                dir, "1_create_and_seed.sql", """
                CREATE TABLE IF NOT EXISTS $users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name TEXT NOT NULL
                );
                INSERT INTO $users(name) VALUES ('alice');
            """.trimIndent()
            )
            // Second file: first statement is valid, second one fails -> entire file must roll back
            write(
                dir, "2_bad.sql", """
                INSERT INTO $users(name) VALUES ('bob');
                -- Force an error (unknown column)
                INSERT INTO $users(non_existing_column) VALUES ('oops');
            """.trimIndent()
            )

            val res = db.migrate(path = dir.toString(), table = table)
            assertThat(res).isFailure()

            // Ensure only v1 recorded and that 'bob' insert from file 2 was rolled back
            assertAll {
                assertThat(listApplied(table)).containsExactly(1L)
                assertThat(countUsers(users)).isEqualTo(1L)
            }
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
            runCatching { db.execute("DROP TABLE IF EXISTS $table;").getOrThrow() }
        }
    }

    // Disabled: the test user only has privileges on the `test` database, so it cannot create a schema.
    @Ignore
    @Test
    fun `migrate with explicit schema and createSchema`(): Unit = runBlocking {
        // A table of its own: the JVM and native suites run in parallel against the same database.
        val users = "t_users_${Random.nextInt(1_000_000)}"
        val rnd = Random.nextInt(100000)
        val schema = "s_migr_$rnd"
        val tableName = "_sqlx4k_migr_mysql_$rnd"
        val qualified = "$schema.$tableName"

        // Pre-clean in case previous run left schema behind
        runCatching { db.execute("DROP SCHEMA IF EXISTS $schema;").getOrThrow() }
        runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
        try {
            val dir = newTempDir("mysql-schema")
            write(
                dir, "1_create_table.sql", """
                CREATE TABLE IF NOT EXISTS $users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name TEXT NOT NULL
                );
            """.trimIndent()
            )
            write(
                dir, "2_insert.sql", """
                INSERT INTO $users(name) VALUES ('alice');
            """.trimIndent()
            )

            val r = db.migrate(path = dir.toString(), table = tableName, schema = schema, createSchema = true)
            assertThat(r).isSuccess()

            // Verify schema created and migrations table created under that schema
            assertAll {
                assertThat(schemaExists(schema)).isEqualTo(true)
                assertThat(tableExists(qualified)).isEqualTo(true)
                assertThat(listApplied(qualified)).containsExactly(1L, 2L)
                assertThat(countUsers(users)).isEqualTo(1L)
            }

            // Re-run idempotent with fully qualified table
            assertThat(db.migrate(path = dir.toString(), table = tableName, schema = schema)).isSuccess()
            assertAll {
                assertThat(listApplied(qualified)).containsExactly(1L, 2L)
                assertThat(countUsers(users)).isEqualTo(1L)
            }
        } finally {
            runCatching { db.execute("DROP TABLE IF EXISTS $users;").getOrThrow() }
            runCatching { db.execute("DROP SCHEMA IF EXISTS $schema;").getOrThrow() }
        }
    }
}
