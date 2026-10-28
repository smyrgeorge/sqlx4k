# Entities

A `@Table` data class maps to a table: every property is a column, and the `@Id` property is the primary key. The
annotations below customize the mapping.

## Customizing columns with `@Column`

You can use the `@Column` annotation to override the column name a property is mapped to, and to control how the
property participates in the generated `INSERT` and `UPDATE` statements. The latter is useful for database-generated or
read-only columns that should be excluded from write operations but still retrieved afterward (via the `RETURNING`
clause).

| Property         | Effect                                                                            |
|------------------|-----------------------------------------------------------------------------------|
| `name = "..."`   | Overrides the column name used in all generated SQL and the generated `RowMapper` |
| `insert = false` | Excluded from the INSERT's column list, included in the INSERT's RETURNING clause |
| `update = false` | Excluded from the UPDATE's SET clause, included in the UPDATE's RETURNING clause  |

By default (when `name` is empty, or when the property has no `@Column` annotation at all) the column name is derived
from the property name using snake_case conversion (e.g., `createdAt` → `created_at`). Providing an explicit `name` is
useful for legacy or non-conventional schemas:

```kotlin
@Table("legacy_users")
data class LegacyUser(
    @Id
    val id: Long,
    // Maps to the legacy "USER_NAME" column instead of the derived "user_name".
    @Column(name = "USER_NAME")
    val userName: String,
    // A custom name can be combined with the insert/update flags.
    @Column(name = "mail_address", update = false)
    val email: String,
    // No @Column: maps to "is_active" (default snake_case conversion).
    val isActive: Boolean
)
```

!!! note

    An explicit `name` must be a plain (unquoted) SQL identifier (`[A-Za-z_][A-Za-z0-9_]*`) and is used verbatim in
    the generated SQL and when reading result columns by name, so it must match the column name as reported by the
    database. PostgreSQL folds unquoted identifiers to lowercase, so for PostgreSQL use the lowercase form unless the
    column was created with a quoted (case-sensitive) name.

```kotlin
@Table("articles")
data class Article(
    @Id
    val id: Long,
    val title: String,
    val content: String,
    // Set only on INSERT by a DB default, never modified afterwards.
    @Column(insert = false, update = false)
    val createdAt: LocalDateTime,
    // Auto-updated by a DB trigger on every write.
    @Column(insert = false, update = false)
    val updatedAt: LocalDateTime
)

@Table("comments")
data class Comment(
    @Id
    val id: Long,
    val content: String,
    // Set on INSERT but never updated.
    @Column(update = false)
    val createdAt: LocalDateTime
)
```

## Excluding properties with `@Transient`

Use the `@Transient` annotation for derived, computed, or cached properties that have **no corresponding database
column**. Unlike `@Column(insert = false, update = false)`, which still maps the property from query results, a
`@Transient` property is treated as if it were not a column at all. It is excluded from `INSERT`/`UPDATE` statements,
from the `RETURNING` clause, and is **not** read by the generated `RowMapper`.

If the annotated property is a primary-constructor parameter, it **must declare a default value** (the generated
`RowMapper` omits it and relies on the default). Properties declared in the class body (e.g. `by lazy` or computed
`get()`) need no default.

```kotlin
@Table("accounts")
data class Account(
    @Id
    val id: Long,
    val email: String,
    // Transient constructor parameter: requires a default value.
    @Transient
    val displayName: String = email.substringBefore('@')
) {
    // Transient body property: derived lazily, no default required.
    @Transient
    val domain: String by lazy { email.substringAfter('@') }
}
```

## Optimistic locking with `@Version`

Mark a single non-nullable `Int` or `Long` property with `@Version` to enable optimistic locking for the generated
CRUD operations. The generator manages the column for you: never set it yourself, just keep the entity instance
returned by the repository for the next write.

- `insert()` writes the entity's value as-is (start new entities at `0`) and reads the column back via `RETURNING`.
- `update()` sets `version = version + 1` and adds `AND version = ?` (the entity's current value) to the `WHERE`
  clause. The incremented value is read back via `RETURNING` and merged into the returned entity.
- `delete()` adds `AND version = ?` to the `WHERE` clause as well.
- `batchUpdate()` matches every row on both the id and the version, and increments the version. It returns only the
  number of updated rows, so the entities you passed in still carry the old version: read them again before the next
  write.

When the row has been modified (or deleted) since the entity was read, the statement matches no rows and the
repository method fails with an `SQLError` whose code is `OptimisticLockFailed`:

```kotlin
@Table("documents")
data class Document(
    @Id
    val id: Long,
    val title: String,
    @Version
    val version: Long = 0,
)

@Repository
interface DocumentRepository : CrudRepository<Document> {
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Long): Result<Document?>
}

val doc = DocumentRepositoryImpl.findOneById(db, 1).getOrThrow()!!                   // version = 3
val updated = DocumentRepositoryImpl.update(db, doc.copy(title = "v2")).getOrThrow() // version = 4

// `doc` still carries version 3, so this write is stale:
val stale = DocumentRepositoryImpl.update(db, doc.copy(title = "v3"))
val error = stale.exceptionOrNull() as SQLError
check(error.code == SQLError.Code.OptimisticLockFailed)
```

The generated `update()` statement looks like this:

```sql
update documents
set title = ?,
    version = version + 1
where id = ?
  and version = ? returning id, version;
```

!!! note "Rules enforced at compile time"

    At most one `@Version` per entity, it must be a non-nullable `Int` or `Long`, the entity must also declare an
    `@Id`, and it cannot be combined with `@Id`, `@Transient` or `@Column(update = false)`. `@Column(name = "...")` and
    `@Column(insert = false)` (to rely on a database default) are fine.

!!! warning

    A batch update is a single statement: the rows whose version matched **are** updated, the stale ones are not, and
    the call fails with `OptimisticLockFailed`. Run versioned `batchUpdate` calls inside a transaction and roll back on
    failure if you need all-or-nothing semantics.

!!! note "MySQL and MariaDB"

    MySQL and MariaDB have no `UPDATE ... RETURNING`, so `update()` re-selects the row afterwards. For versioned
    entities that `SELECT` is keyed on the expected *new* version, so a stale update still surfaces as
    `OptimisticLockFailed` instead of silently returning the unchanged row.

## Property-level converters (`@Converter`)

For custom types, you can use the `@Converter` annotation to specify a `ValueEncoder` directly on the property. This
provides compile-time type safety, avoids runtime registry lookups, and eliminates object instantiation overhead.

**Defining a custom encoder:**

```kotlin
// Define your custom type
data class Money(val amount: Double, val currency: String) {
    override fun toString(): String = "$amount:$currency"

    companion object {
        fun parse(value: String): Money {
            val parts = value.split(":")
            return Money(parts[0].toDouble(), parts[1])
        }
    }
}

// Create a ValueEncoder as an object (singleton) - NOT a class
object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
}
```

**Using `@Converter` on properties:**

```kotlin
@Table("invoices")
data class Invoice(
    @Id
    val id: Long,
    val description: String,
    @Converter(MoneyEncoder::class)
    val totalAmount: Money
)
```

!!! note

    When using `@Converter`, you don't need to register the encoder in a `ValueEncoderRegistry`. The encoder object is
    referenced directly in the generated code, avoiding any instantiation overhead.
