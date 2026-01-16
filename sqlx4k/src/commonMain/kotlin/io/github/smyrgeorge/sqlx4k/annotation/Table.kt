package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Maps a data class to a database table and enables code generation for CRUD operations.
 *
 * When applied to a data class, the code generator produces:
 * - `insert()` - Creates an INSERT statement with RETURNING clause
 * - `update()` - Creates an UPDATE statement with RETURNING clause (requires [@Id][Id])
 * - `delete()` - Creates a DELETE statement (requires [@Id][Id])
 * - `applyInsertResult()` - Merges DB-generated values after INSERT
 * - `applyUpdateResult()` - Merges DB-generated values after UPDATE
 * - `<ClassName>AutoRowMapper` - Maps result rows to entity instances
 *
 * ## Requirements
 *
 * - **Must be applied to a `data class`** (required for the generated `copy()` calls)
 * - Properties are mapped to columns using snake_case conversion (e.g., `createdAt` → `created_at`)
 *
 * ## Example
 *
 * ```kotlin
 * @Table("users")
 * data class User(
 *     @Id
 *     val id: Long,
 *     val name: String,
 *     val email: String,
 *     @Column(insert = false, update = false)
 *     val createdAt: LocalDateTime
 * )
 *
 * // Generated usage:
 * val user = User(id = 0, name = "Alice", email = "alice@example.com", createdAt = LocalDateTime.now())
 * val inserted = db.fetchOne(user.insert(), UserAutoRowMapper)
 * ```
 *
 * @property name The database table name. This value is used directly in generated SQL statements.
 *
 * @see Id For marking the primary key property.
 * @see Column For configuring individual column behavior.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Table(val name: String)
