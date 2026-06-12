package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Configures how a property is mapped to a database column and how it participates
 * in generated INSERT and UPDATE statements.
 *
 * Use this annotation to override the default column-name mapping, or to control
 * database-generated or read-only columns that should be excluded from write
 * operations but retrieved after execution.
 *
 * ## Behavior
 *
 * | Property | Effect |
 * |----------|--------|
 * | `name = "..."` | Overrides the column name used in all generated SQL and the RowMapper |
 * | `name = ""` (default) | Column name is derived from the property name via snake_case conversion |
 * | `insert = false` | Excluded from INSERT's column list, included in INSERT's RETURNING clause |
 * | `update = false` | Excluded from UPDATE's SET clause, included in UPDATE's RETURNING clause |
 *
 * ## Column Naming
 *
 * By default (when `name` is empty, or when the property has no `@Column` annotation at all),
 * the column name is derived from the property name using snake_case conversion
 * (e.g., `createdAt` → `created_at`). Provide an explicit `name` to map a property to a
 * column that does not follow this convention (e.g., legacy schemas):
 *
 * ```kotlin
 * @Column(name = "USER_NAME")
 * val userName: String
 * ```
 *
 * An explicit name must be a plain (unquoted) SQL identifier matching `[A-Za-z_][A-Za-z0-9_]*`;
 * the code generator reports an error otherwise. The value is used verbatim both in generated
 * SQL and when reading result columns by name, so it must match the column name as reported by
 * the database. Note that PostgreSQL folds unquoted identifiers to lowercase, so for PostgreSQL
 * use the lowercase form unless the column was created with a quoted (case-sensitive) name.
 *
 * ## Common Use Cases
 *
 * **Explicit column name (e.g., legacy/non-conventional schema):**
 * ```kotlin
 * @Column(name = "mail_address")
 * val email: String
 * ```
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
 * @property name The database column name. When empty (default), the column name is derived
 *                from the property name via snake_case conversion. This value is used directly
 *                in generated SQL statements and the generated RowMapper, and must be a plain
 *                SQL identifier (`[A-Za-z_][A-Za-z0-9_]*`).
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
    val name: String = "",
    val insert: Boolean = true,
    val update: Boolean = true
)
