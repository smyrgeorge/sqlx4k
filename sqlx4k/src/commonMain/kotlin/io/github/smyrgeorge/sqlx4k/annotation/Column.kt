package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Annotation that specifies whether a property should be included
 * in insert and update operations in database-related operations.
 *
 * @property insert Indicates if the property should be included during insert operations.
 * @property update Indicates if the property should be included during update operations.
 * @property generated Indicates if the property value is generated or updated by the database
 *                     (e.g., auto-increment, default values, triggers). When true, this column
 *                     will be included in the RETURNING clause of INSERT/UPDATE statements.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    val insert: Boolean = true,
    val update: Boolean = true,
    val generated: Boolean = false
)
