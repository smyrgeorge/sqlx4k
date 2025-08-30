package io.github.smyrgeorge.sqlx4k.annotation

/**
 * This annotation is used to indicate that a property is an identifier for a database
 * entity. The identifier can optionally be included in insert operations.
 *
 * @property insert Specifies whether the identifier should be included in insert operations.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Id(val insert: Boolean = false)
