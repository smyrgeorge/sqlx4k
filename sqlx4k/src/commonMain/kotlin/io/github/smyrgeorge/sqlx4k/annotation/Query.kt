package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Annotation used to specify a query string for a database operation.
 * This annotation is applied to functions to indicate the query
 * that should be executed when the function is called.
 *
 * @property value The query string to be executed.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Query(val value: String)
