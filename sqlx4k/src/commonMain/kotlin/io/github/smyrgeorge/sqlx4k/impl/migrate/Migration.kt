@file:Suppress("SqlNoDataSourceInspection")
@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.impl.migrate

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.types.NoQuotingString
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import io.github.smyrgeorge.sqlx4k.RowMapper as Sqlx4kRowMapper

/**
 * Represents a migration in a database system with associated metadata.
 *
 * @property version The version number of the migration.
 * @property name The name or description of the migration.
 * @property installedOn The timestamp indicating when the migration was installed.
 * @property checksum The checksum of the migration script, used for integrity verification.
 * @property executionTime The time taken to execute the migration, in milliseconds.
 */
data class Migration(
    val version: Long,
    val name: String,
    val installedOn: Instant,
    val checksum: String,
    val executionTime: Long
) {
    /**
     * Prepares an SQL `INSERT` statement for the specified table.
     *
     * The generated statement expects values for the following columns:
     * `version`, `name`, `installed_on`, `checksum`, and `execution_time`.
     *
     * @param table The name of the database table into which the record will be inserted.
     * @return A configured `Statement` instance for executing the `INSERT` operation.
     */
    internal fun insert(table: String): Statement {
        // language=SQL
        val sql = """
            INSERT INTO ? (version, name, installed_on, checksum, execution_time)
            VALUES (?, ?, ?, ?, ?)
        """.trimIndent()
        return Statement.create(sql)
            .bind(0, NoQuotingString(table))
            .bind(1, version)
            .bind(2, name)
            .bind(3, installedOn)
            .bind(4, checksum)
            .bind(5, executionTime)
    }

    /**
     * Object responsible for mapping a database row to a `Migration` instance.
     *
     * Implements the `Sqlx4kRowMapper` interface to provide a mapping from
     * a `ResultSet.Row` to the `Migration` data class.
     *
     * This mapper is designed to extract the following fields from a row:
     * - `version`: Maps to the `version` property of `Migration` as a `Long`.
     * - `name`: Maps to the `name` property of `Migration` as a `String`.
     * - `installedOn`: Maps to the `installedOn` property of `Migration` as an `Instant`.
     * - `checksum`: Maps to the `checksum` property of `Migration` as a `String`.
     * - `executionTime`: Maps to the `executionTime` property of `Migration` as a `Long`.
     *
     * The primary purpose of this mapper is to facilitate the conversion
     * of database query results into domain-specific objects.
     */
    internal object RowMapper : Sqlx4kRowMapper<Migration> {
        override fun map(row: ResultSet.Row): Migration {
            return Migration(
                version = row.get(0).asLong(),
                name = row.get(1).asString(),
                installedOn = row.get(2).asInstant(),
                checksum = row.get(3).asString(),
                executionTime = row.get(4).asLong()
            )
        }
    }

    companion object {
        /**
         * Creates a SQL `CREATE TABLE IF NOT EXISTS` statement specific to the provided database dialect.
         * The generated SQL defines a schema with columns for `version`, `name`, `installed_on`, `checksum`,
         * and `execution_time`, ensuring that the `version` column is primary and unique.
         *
         * @param table The name of the table to be created if it does not exist.
         * @param dialect The database dialect, which determines the SQL syntax for table creation.
         * @return A `Statement` object containing the generated SQL statement configured for binding.
         */
        internal fun createTableIfNotExists(table: String, dialect: Dialect): Statement {
            val sql = when (dialect) {
                Dialect.MySQL ->
                    // language=SQL
                    """
                        CREATE TABLE IF NOT EXISTS ?
                        (
                            version        BIGINT      NOT NULL PRIMARY KEY,
                            name           TEXT        NOT NULL,
                            installed_on   DATETIME(6) NOT NULL DEFAULT now(6),
                            checksum       TEXT        NOT NULL,
                            execution_time BIGINT      NOT NULL,
                            UNIQUE (version)
                        );
                    """.trimIndent()

                Dialect.PostgreSQL ->
                    // language=SQL
                    """
                        CREATE TABLE IF NOT EXISTS ?
                        (
                            version        BIGINT    NOT NULL PRIMARY KEY,
                            name           TEXT      NOT NULL,
                            installed_on   TIMESTAMP NOT NULL DEFAULT now(),
                            checksum       TEXT      NOT NULL,
                            execution_time BIGINT    NOT NULL,
                            UNIQUE (version)
                        );
                    """.trimIndent()

                Dialect.SQLite ->
                    // language=SQL
                    """
                        CREATE TABLE IF NOT EXISTS ?
                        (
                            version        INTEGER  NOT NULL PRIMARY KEY,
                            name           TEXT     NOT NULL,
                            installed_on   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            checksum       TEXT     NOT NULL,
                            execution_time INTEGER  NOT NULL,
                            UNIQUE (version)
                        );
                    """.trimIndent()
            }
            return Statement.create(sql).bind(0, NoQuotingString(table))
        }

        /**
         * Prepares an SQL `SELECT` statement to retrieve all rows from the specified table.
         *
         * @param table The name of the database table from which to select all rows.
         * @return A `Statement` instance configured to execute the `SELECT` operation with an `ORDER BY version` clause.
         */
        internal fun selectAll(table: String): Statement {
            // language=SQL
            val sql = "SELECT * FROM ? ORDER BY version;"
            return Statement.create(sql).bind(0, NoQuotingString(table))
        }
    }
}