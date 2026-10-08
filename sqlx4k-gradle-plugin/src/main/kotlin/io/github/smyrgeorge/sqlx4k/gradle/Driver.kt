package io.github.smyrgeorge.sqlx4k.gradle

/**
 * The sqlx4k database drivers (see [Sqlx4kExtension.driver]).
 *
 * @property dialect the SQL dialect identifier passed to the sqlx4k code generator.
 * @property artifact the sqlx4k driver artifact.
 */
public enum class Driver(internal val dialect: String, internal val artifact: String) {
    MySQL("mysql", "sqlx4k-mysql"),
    MariaDB("mariadb", "sqlx4k-mysql"),
    PostgreSQL("postgresql", "sqlx4k-postgres"),
    SQLite("sqlite", "sqlx4k-sqlite"),
    SQLiteCipher("sqlite", "sqlx4k-sqlite-cipher"),
}
