package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Marks a property as the primary key identifier for a database entity.
 *
 * The annotated property is used in the WHERE clause of generated UPDATE and DELETE statements
 * to identify the target row. It is always included in the RETURNING clause of both INSERT
 * and UPDATE statements.
 *
 * ## Behavior
 *
 * | `insert` value | INSERT behavior | Common use case |
 * |----------------|-----------------|-----------------|
 * | `false` (default) | Excluded from INSERT, returned via RETURNING | Auto-increment / DB-generated IDs |
 * | `true` | Included in INSERT values | Application-generated IDs (e.g., UUID) |
 *
 * ## Examples
 *
 * **Auto-increment primary key (default):**
 * ```kotlin
 * @Id
 * val id: Long
 * ```
 *
 * **Application-generated UUID:**
 * ```kotlin
 * @Id(insert = true)
 * val id: Uuid
 * ```
 *
 * @property insert Whether to include the ID in INSERT statements. Set to `false` (default) for
 *                  database-generated IDs (auto-increment, sequences). Set to `true` when the
 *                  application provides the ID value (e.g., UUIDs).
 *
 * @see Column For configuring non-primary-key columns with database-generated values.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Id(val insert: Boolean = false)
