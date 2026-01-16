package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Configures how a property participates in generated INSERT and UPDATE statements.
 *
 * Use this annotation to control database-generated or read-only columns that should
 * be excluded from write operations but retrieved after execution.
 *
 * ## Behavior
 *
 * | Property | Effect on Statement | Effect on RETURNING |
 * |----------|---------------------|---------------------|
 * | `insert = false` | Excluded from INSERT's column list | Included in INSERT's RETURNING clause |
 * | `update = false` | Excluded from UPDATE's SET clause | Included in UPDATE's RETURNING clause |
 *
 * ## Common Use Cases
 *
 * **Database-generated timestamp on insert (e.g., `created_at`):**
 * ```kotlin
 * @Column(insert = false)
 * val createdAt: LocalDateTime
 * ```
 *
 * **Auto-updated timestamp by trigger (e.g., `updated_at`):**
 * ```kotlin
 * @Column(insert = false, update = false)
 * val updatedAt: LocalDateTime
 * ```
 *
 * **Computed/virtual column (read-only):**
 * ```kotlin
 * @Column(insert = false, update = false)
 * val fullName: String
 * ```
 *
 * @property insert Whether to include this column in INSERT statements. Set to `false` for
 *                  columns with database defaults or generated values. Default is `true`.
 * @property update Whether to include this column in UPDATE statements. Set to `false` for
 *                  columns managed by database triggers or that should never be modified. Default is `true`.
 *
 * @see Id For primary key columns with auto-increment behavior.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Column(
    val insert: Boolean = true,
    val update: Boolean = true
)
