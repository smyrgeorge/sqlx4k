package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Annotation that specifies whether a property should be included
 * in insert and update operations in database-related operations.
 *
 * @property insert Indicates if the property should be included during insert operations.
 * @property update Indicates if the property should be included during update operations.
 */
@Suppress("unused")
@Target(AnnotationTarget.PROPERTY)
annotation class Column(val insert: Boolean = true, val update: Boolean = true)
