package io.github.smyrgeorge.sqlx4k.impl.migrate

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class MigrationSqlBuilderTests {

    // ========================================================================================
    // createTableIfNotExists – exact per-dialect DDL
    // ========================================================================================

    @Test
    fun `createTableIfNotExists produces exact PostgreSQL DDL`() {
        val sql = Migration.createTableIfNotExists("_migrations", Dialect.PostgreSQL).sql
        val expected = """
            CREATE TABLE IF NOT EXISTS _migrations
            (
                version        BIGINT    NOT NULL PRIMARY KEY,
                name           TEXT      NOT NULL,
                installed_on   TIMESTAMP NOT NULL DEFAULT now(),
                checksum       TEXT      NOT NULL,
                execution_time BIGINT    NOT NULL,
                UNIQUE (version)
            );
        """.trimIndent()
        assertThat(sql).isEqualTo(expected)
    }

    @Test
    fun `createTableIfNotExists produces exact MySQL DDL`() {
        val sql = Migration.createTableIfNotExists("_migrations", Dialect.MySQL).sql
        val expected = """
            CREATE TABLE IF NOT EXISTS _migrations
            (
                version        BIGINT      NOT NULL PRIMARY KEY,
                name           TEXT        NOT NULL,
                installed_on   DATETIME(6) NOT NULL DEFAULT now(6),
                checksum       TEXT        NOT NULL,
                execution_time BIGINT      NOT NULL,
                UNIQUE (version)
            );
        """.trimIndent()
        assertThat(sql).isEqualTo(expected)
    }

    @Test
    fun `createTableIfNotExists produces exact SQLite DDL`() {
        val sql = Migration.createTableIfNotExists("_migrations", Dialect.SQLite).sql
        val expected = """
            CREATE TABLE IF NOT EXISTS _migrations
            (
                version        INTEGER  NOT NULL PRIMARY KEY,
                name           TEXT     NOT NULL,
                installed_on   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                checksum       TEXT     NOT NULL,
                execution_time INTEGER  NOT NULL,
                UNIQUE (version)
            );
        """.trimIndent()
        assertThat(sql).isEqualTo(expected)
    }

    @Test
    fun `createTableIfNotExists uses dialect-specific version column types`() {
        // PostgreSQL / MySQL -> BIGINT ; SQLite -> INTEGER
        assertThat(Migration.createTableIfNotExists("t", Dialect.PostgreSQL).sql)
            .contains("version        BIGINT    NOT NULL PRIMARY KEY")
        assertThat(Migration.createTableIfNotExists("t", Dialect.MySQL).sql)
            .contains("version        BIGINT      NOT NULL PRIMARY KEY")
        assertThat(Migration.createTableIfNotExists("t", Dialect.SQLite).sql)
            .contains("version        INTEGER  NOT NULL PRIMARY KEY")
    }

    @Test
    fun `createTableIfNotExists uses dialect-specific installed_on defaults`() {
        // PostgreSQL -> TIMESTAMP DEFAULT now()
        val pg = Migration.createTableIfNotExists("t", Dialect.PostgreSQL).sql
        assertThat(pg).contains("installed_on   TIMESTAMP NOT NULL DEFAULT now()")
        assertThat(pg).doesNotContain("now(6)")
        assertThat(pg).doesNotContain("CURRENT_TIMESTAMP")

        // MySQL -> DATETIME(6) DEFAULT now(6)
        val mysql = Migration.createTableIfNotExists("t", Dialect.MySQL).sql
        assertThat(mysql).contains("installed_on   DATETIME(6) NOT NULL DEFAULT now(6)")
        assertThat(mysql).doesNotContain("CURRENT_TIMESTAMP")

        // SQLite -> DATETIME DEFAULT CURRENT_TIMESTAMP
        val sqlite = Migration.createTableIfNotExists("t", Dialect.SQLite).sql
        assertThat(sqlite).contains("installed_on   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP")
        assertThat(sqlite).doesNotContain("now(")
    }

    @Test
    fun `createTableIfNotExists blank table throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Migration.createTableIfNotExists("   ", Dialect.PostgreSQL)
        }
        assertThat(ex.message).isEqualTo("Table name cannot be blank.")
    }

    @Test
    fun `createTableIfNotExists unsafe table throws SQLError`() {
        // The identifier is routed through IdentifierString for every dialect, including SQLite.
        val ex = assertFailsWith<SQLError> {
            Migration.createTableIfNotExists("t;", Dialect.SQLite)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    // ========================================================================================
    // createSchemaIfNotExists
    // ========================================================================================

    @Test
    fun `createSchemaIfNotExists PostgreSQL emits CREATE SCHEMA IF NOT EXISTS`() {
        val sql = Migration.createSchemaIfNotExists("app", Dialect.PostgreSQL).sql
        assertThat(sql).isEqualTo("CREATE SCHEMA IF NOT EXISTS app;")
    }

    @Test
    fun `createSchemaIfNotExists MySQL emits CREATE SCHEMA IF NOT EXISTS`() {
        val sql = Migration.createSchemaIfNotExists("app", Dialect.MySQL).sql
        assertThat(sql).isEqualTo("CREATE SCHEMA IF NOT EXISTS app;")
    }

    @Test
    fun `createSchemaIfNotExists SQLite throws IllegalStateException`() {
        // Kotlin's error(...) throws IllegalStateException.
        // NOTE: the KDoc claims @throws IllegalArgumentException for SQLite, but the code path
        // uses error(...) so the actual type is IllegalStateException. Behavior is pinned below.
        val ex = assertFailsWith<IllegalStateException> {
            Migration.createSchemaIfNotExists("app", Dialect.SQLite)
        }
        assertThat(ex.message).isEqualTo("SQLite does not support schemas.")
    }

    @Test
    fun `createSchemaIfNotExists blank schema throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Migration.createSchemaIfNotExists("", Dialect.PostgreSQL)
        }
        assertThat(ex.message).isEqualTo("Schema name cannot be blank.")
    }

    @Test
    fun `createSchemaIfNotExists unsafe schema throws SQLError`() {
        val ex = assertFailsWith<SQLError> {
            Migration.createSchemaIfNotExists("app; DROP TABLE x", Dialect.PostgreSQL)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    // ========================================================================================
    // getSearchPath
    // ========================================================================================

    @Test
    fun `getSearchPath PostgreSQL emits SHOW search_path`() {
        assertThat(Migration.getSearchPath(Dialect.PostgreSQL).sql).isEqualTo("SHOW search_path")
    }

    @Test
    fun `getSearchPath MySQL emits SELECT DATABASE`() {
        assertThat(Migration.getSearchPath(Dialect.MySQL).sql).isEqualTo("SELECT DATABASE()")
    }

    @Test
    fun `getSearchPath SQLite throws IllegalStateException`() {
        val ex = assertFailsWith<IllegalStateException> {
            Migration.getSearchPath(Dialect.SQLite)
        }
        assertThat(ex.message).isEqualTo("SQLite does not support schemas.")
    }

    // ========================================================================================
    // setSearchpath
    // ========================================================================================

    @Test
    fun `setSearchpath PostgreSQL sets search_path with public fallback`() {
        assertThat(Migration.setSearchpath("app", Dialect.PostgreSQL).sql)
            .isEqualTo("SET search_path TO app, public")
    }

    @Test
    fun `setSearchpath MySQL emits USE`() {
        assertThat(Migration.setSearchpath("app", Dialect.MySQL).sql)
            .isEqualTo("USE app")
    }

    @Test
    fun `setSearchpath SQLite throws IllegalStateException`() {
        val ex = assertFailsWith<IllegalStateException> {
            Migration.setSearchpath("app", Dialect.SQLite)
        }
        assertThat(ex.message).isEqualTo("SQLite does not support schemas.")
    }

    @Test
    fun `setSearchpath blank schema throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Migration.setSearchpath("  ", Dialect.PostgreSQL)
        }
        assertThat(ex.message).isEqualTo("Schema name cannot be blank.")
    }

    @Test
    fun `setSearchpath PostgreSQL rejects an unsafe schema`() {
        // Routed through IdentifierString like the sibling builders, so an unsafe schema is rejected.
        val ex = assertFailsWith<SQLError> {
            Migration.setSearchpath("app; DROP TABLE _migrations", Dialect.PostgreSQL)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    @Test
    fun `setSearchpath MySQL rejects an unsafe schema`() {
        val ex = assertFailsWith<SQLError> {
            Migration.setSearchpath("app; DROP TABLE _migrations", Dialect.MySQL)
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    // ========================================================================================
    // selectAll
    // ========================================================================================

    @Test
    fun `selectAll selects all rows ordered by version`() {
        assertThat(Migration.selectAll("_migrations").sql)
            .isEqualTo("SELECT * FROM _migrations ORDER BY version;")
    }

    @Test
    fun `selectAll blank table throws IllegalArgumentException`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Migration.selectAll("   ")
        }
        assertThat(ex.message).isEqualTo("Table name cannot be blank.")
    }

    @Test
    fun `selectAll unsafe table throws SQLError`() {
        val ex = assertFailsWith<SQLError> {
            Migration.selectAll("t;")
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    // ========================================================================================
    // insert
    // ========================================================================================

    private fun migration(): Migration = Migration(
        version = 42L,
        name = "init",
        installedOn = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        checksum = "deadbeef",
        executionTime = 7L
    )

    @Test
    fun `insert produces expected columns and positional placeholders`() {
        val sql = migration().insert("_migrations").sql
        val expected = """
            INSERT INTO _migrations (version, name, installed_on, checksum, execution_time)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        assertThat(sql).isEqualTo(expected)
    }

    @Test
    fun `insert renderNativeQuery PostgreSQL numbers placeholders and preserves bind order`() {
        val m = migration()
        val nq = m.insert("_migrations").renderNativeQuery(Dialect.PostgreSQL, ValueEncoderRegistry.EMPTY)

        val expectedSql = """
            INSERT INTO _migrations (version, name, installed_on, checksum, execution_time)
            VALUES ($1, $2, $3, $4, $5)
        """.trimIndent()
        assertThat(nq.sql).isEqualTo(expectedSql)
        assertThat(nq.dialect).isEqualTo(Dialect.PostgreSQL)

        // Bind order must map: version, name, installed_on, checksum, execution_time.
        assertThat(nq.values).isEqualTo(
            listOf<Any?>(m.version, m.name, m.installedOn, m.checksum, m.executionTime)
        )
    }

    @Test
    fun `insert renderNativeQuery MySQL keeps question-mark placeholders`() {
        val m = migration()
        val nq = m.insert("_migrations").renderNativeQuery(Dialect.MySQL, ValueEncoderRegistry.EMPTY)
        assertThat(nq.sql).contains("VALUES (?, ?, ?, ?, ?)")
        assertThat(nq.dialect).isEqualTo(Dialect.MySQL)
        assertThat(nq.values).isEqualTo(
            listOf<Any?>(m.version, m.name, m.installedOn, m.checksum, m.executionTime)
        )
    }

    @Test
    fun `insert unsafe table throws SQLError`() {
        val ex = assertFailsWith<SQLError> {
            migration().insert("t;")
        }
        assertThat(ex.code).isEqualTo(SQLError.Code.UnsafeStringContent)
    }

    @Test
    fun `insert guards against a blank table name`() {
        // insert() now validates the table name like selectAll / createTableIfNotExists.
        val ex = assertFailsWith<IllegalArgumentException> {
            migration().insert("")
        }
        assertThat(ex.message).isEqualTo("Table name cannot be blank.")
    }

    // ========================================================================================
    // RowMapper
    // ========================================================================================

    @Test
    fun `RowMapper maps columns by ordinal into a Migration`() {
        val row = ResultSet.Row(
            listOf(
                ResultSet.Row.Column(ordinal = 0, name = "version", type = "INT8", value = "42"),
                ResultSet.Row.Column(ordinal = 1, name = "name", type = "TEXT", value = "init"),
                ResultSet.Row.Column(
                    ordinal = 2, name = "installed_on", type = "TIMESTAMP",
                    value = "2023-01-01 12:34:56.123456"
                ),
                ResultSet.Row.Column(ordinal = 3, name = "checksum", type = "TEXT", value = "deadbeef"),
                ResultSet.Row.Column(ordinal = 4, name = "execution_time", type = "INT8", value = "7"),
            )
        )

        val mapped = Migration.RowMapper.map(row, ValueEncoderRegistry.EMPTY)

        assertThat(mapped).isEqualTo(
            Migration(
                version = 42L,
                name = "init",
                installedOn = Instant.parse("2023-01-01T12:34:56.123456Z"),
                checksum = "deadbeef",
                executionTime = 7L
            )
        )
    }
}
