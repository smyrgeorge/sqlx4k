package io.github.smyrgeorge.sqlx4k.annotation

/**
 * This annotation is used to specify the table name in a database for a data class.
 *
 * @property name The name of the table in the database.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Table(val name: String)
