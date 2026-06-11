package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Marks a property as transient, fully excluding it from generated database code.
 *
 * Unlike [Column] (which only controls whether a property participates in `INSERT`/`UPDATE`
 * statements while still mapping it from query results), a transient property is treated as if
 * it were not a database column at all. It is:
 *
 * - **Excluded** from `INSERT` column lists and value placeholders.
 * - **Excluded** from `UPDATE` `SET` clauses.
 * - **Excluded** from `RETURNING` clauses of both `INSERT` and `UPDATE`.
 * - **Not mapped** by the generated `RowMapper` (no column is read for it).
 *
 * Use this for derived, computed, cached, or otherwise Kotlin-only properties that have no
 * corresponding database column.
 *
 * ## Constructor parameters
 *
 * If the annotated property is a primary-constructor parameter, it **must declare a default
 * value** — the generated `RowMapper` omits it from the constructor call and relies on the
 * default. The code generator reports an error otherwise.
 *
 * ## Examples
 *
 * **Computed/derived constructor parameter (with a default):**
 * ```kotlin
 * @Table("users")
 * data class User(
 *     @Id val id: Long,
 *     val email: String,
 *     @Transient val displayName: String = email.substringBefore('@'),
 * )
 * ```
 *
 * **Lazily-derived body property (no default required — not a constructor parameter):**
 * ```kotlin
 * @Table("users")
 * data class User(
 *     @Id val id: Long,
 *     val email: String,
 * ) {
 *     @Transient
 *     val domain: String by lazy { email.substringAfter('@') }
 * }
 * ```
 *
 * @see Column For configuring how a real column participates in INSERT/UPDATE statements.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Transient
