package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Marks a property as representing the version or revision of a database entity.
 *
 * The annotated property can be used for implementing optimistic locking mechanisms
 * in database operations. When included in an entity class, this property helps
 * detect concurrent modifications by checking the version before applying updates.
 *
 * ## Behavior
 *
 * - The version property is incremented automatically with each update operation.
 * - During update statements, the current version value is included in the `WHERE` clause to prevent overwrites
 *   based on outdated data. If no rows are updated due to a mismatched version, it is generally interpreted as
 *   a concurrency conflict.
 *
 * ## Common Use Cases
 *
 * - Optimistic locking for concurrent updates.
 * - Tracking changes to a record for audit purposes.
 *
 * ## Additional Notes
 *
 * - This annotation is typically used alongside a primary key annotated with [@Id][Id].
 * - Ensure the property is correctly mapped to a database column designed for version tracking (e.g., an integer or timestamp field).
 *
 * @see Id For marking the primary key property of an entity.
 * @see Table For marking a class as a database table.
 * @see Column For customizing column properties.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Version
