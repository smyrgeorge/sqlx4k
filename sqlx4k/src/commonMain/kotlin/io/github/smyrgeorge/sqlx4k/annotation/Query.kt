package io.github.smyrgeorge.sqlx4k.annotation

import org.intellij.lang.annotations.Language

/**
 * Annotation used to define a database query for repository methods.
 *
 * @property value The SQL query string to be executed.
 * @property checkSyntax Indicates whether the syntax of the query should be validated.
 * @property checkSchema Indicates whether the schema compliance of the query should be validated.
 * @property fetchSize The number of rows fetched from the driver at a time. Only for the stream methods
 * (`streamAll` / `streamAllBy…`), which fetch their rows in chunks; the other methods load the whole result at
 * once, and setting it on them is an error. The default, `0`, means unset: the stream uses
 * [io.github.smyrgeorge.sqlx4k.QueryExecutor.DEFAULT_FETCH_SIZE]. Otherwise it must be positive.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Query(
    @Language("SQL") val value: String,
    val checkSyntax: Boolean = true,
    val checkSchema: Boolean = true,
    val fetchSize: Int = 0
)
