package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Annotates a repository function with an inline SQL query to execute.
 *
 * This is consumed by the KSP code generator to produce the implementation
 * and (optionally) validate the SQL syntax at compile time.
 *
 * Properties:
 * - value: the SQL string to execute. Named parameters are supported using :name.
 * - checkSyntax: when true (default), the processor will validate the SQL syntax
 *   at compile time using JSqlParser, subject to the module-wide setting
 *   `ksp { arg("validate-sql-syntax", "true|false") }`. Set to false to skip
 *   validation for this specific query while leaving global validation enabled.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Query(
    val value: String,
    val checkSyntax: Boolean = true,
)
