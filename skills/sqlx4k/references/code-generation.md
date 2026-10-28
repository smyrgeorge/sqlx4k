# Code generation

Docs: <https://smyrgeorge.github.io/sqlx4k/codegen/>

The KSP processor (`sqlx4k-codegen`) writes, for every `@Table` entity, its CRUD statements and an
`{Entity}AutoRowMapper`,
and for every `@Repository` interface an `{Interface}Impl` object, all in the `generatedCodePackage` (KSP option
`output-package`).

## Entities

```kotlin
@Table("articles")
data class Article(
    @Id                                       // primary key; excluded from INSERT, read back (database-generated)
    val id: Long = 0,
    val title: String,                        // column `title`
    @Column(name = "body_text")               // explicit column name (a plain identifier, as the database reports it)
    val body: String,
    @Column(insert = false, update = false)   // written by the database (default, trigger), read back
    val createdAt: Instant? = null,
    @Column(update = false)                   // set on INSERT, never updated
    val author: String,
    @Converter(MoneyEncoder::class)           // a custom type, converted by a ValueEncoder object
    val price: Money,
    @Version                                  // optimistic locking: Int or Long, managed by the generated code
    val version: Long = 0,
    @Transient                                // not a column; a constructor parameter needs a default
    val preview: String = body.take(100),
)
```

- Column names default to the snake_case of the property name (`createdAt` → `created_at`).
- `@Id(insert = true)`: the application sets the id (e.g. `Uuid`); it is included in the INSERT.
- `@Version`: `update` sets `version = version + 1` and adds `AND version = ?`; a stale entity fails with
  `SQLError.Code.OptimisticLockFailed`. Always keep the instance the repository returns.
- `@Converter(X::class)`: `X` must be an `object` implementing `ValueEncoder<T>`
  (`encode(value: T): Any`, `decode(value: ResultSet.Row.Column): T`). No registry needed.
- PostgreSQL folds unquoted identifiers to lowercase: use lowercase column names there.

## Repository interfaces

| Interface                       | Executor                                            | Returns                               | Module         |
|---------------------------------|-----------------------------------------------------|---------------------------------------|----------------|
| `ContextCrudRepository<T>`      | context parameter `context(context: QueryExecutor)` | `Result<T>`                           | `sqlx4k`       |
| `CrudRepository<T>`             | first argument `context: QueryExecutor`             | `Result<T>`                           | `sqlx4k`       |
| `ArrowContextCrudRepository<T>` | context parameter                                   | `Either<SQLError, T>` (`DbResult<T>`) | `sqlx4k-arrow` |
| `ArrowCrudRepository<T>`        | first argument                                      | `Either<SQLError, T>`                 | `sqlx4k-arrow` |

With the Gradle plugin, add Arrow with `extensions(Arrow)`. The `@Query` methods of an Arrow repository declare
`DbResult<…>` return types (`io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.DbResult`), which the build checks.

Generated CRUD operations:

- `save(entity)`: **the default for writes.** Inserts when an `Int`/`Long` id is `0` (or `null`), updates otherwise.
- `insert(entity)`, `update(entity)`: only when `save` cannot decide, i.e. the id is not a database-generated
  `Int`/`Long` (a `Uuid`, an `@Id(insert = true)` id).
- `delete(entity)`, `batchInsert(entities)`, `batchUpdate(entities)`.

A custom row mapper: `@Repository(mapper = MyRowMapper::class)`.

### Query methods

```kotlin
@Repository
interface EventRepository : ContextCrudRepository<Event> {
    @Query("SELECT * FROM events WHERE type = :type ORDER BY id")
    context(context: QueryExecutor)
    suspend fun findAllByType(type: String): Result<List<Event>>

    @Query("SELECT * FROM events WHERE type = :type", fetchSize = 500)
    context(context: QueryExecutor)
    fun streamAllByType(type: String): Flow<Event> // not suspend; rows fetched in chunks

    @Query("SELECT count(*) FROM events WHERE created_at < :before")
    context(context: QueryExecutor)
    suspend fun countByCreatedAtBefore(before: Instant): Result<Long>

    @Query("DELETE FROM events WHERE created_at < :before")
    context(context: QueryExecutor)
    suspend fun deleteByCreatedAtBefore(before: Instant): Result<Long>

    @Query("UPDATE events SET type = :type WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun executeSetType(id: Long, type: String): Result<Long>
}
```

`fetchSize` is only allowed on `stream*` methods.

## Batch operations

`batchInsert(entities)` and `batchUpdate(entities)` write all the entities in one statement and return the number of
rows (`Result<Long>`), not the entities: database-generated values are not read back. A row count that differs from
the number of entities fails with `RowMismatch` (`OptimisticLockFailed` for a stale versioned `batchUpdate`); the rows
that matched are already written, so run it in a transaction for all-or-nothing.

## Hooks

Every repository interface extends `CrudRepositoryHooks<T>`. Override in the interface:

- `preInsertHook`, `preUpdateHook`, `preDeleteHook`, `afterInsertHook`, `afterUpdateHook`, `afterDeleteHook`:
  `(context: QueryExecutor, entity: T): T`, to transform or observe the entity;
- `aroundQuery(methodName: String, statement: Statement, block: suspend () -> R): R`: wraps every query (CRUD and
  `@Query`), for logging, metrics and tracing.

## Validation and optimization options

Set them in the plugin's `args` (`args = mapOf("validate-sql-schema" to "true")`) or with `ksp { arg(...) }`. All are
on by default unless noted.

| Option                       | Effect                                                                                                                                                   |
|------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `validate-sql-syntax`        | Parses every `@Query` (JSqlParser). Per query: `@Query(checkSyntax = false)`.                                                                            |
| `validate-sql-schema`        | **Off by default.** Checks tables/columns against the schema built from migrations (`schema-migrations-path`). Per query: `@Query(checkSchema = false)`. |
| `reject-stacked-statements`  | One statement per `@Query`.                                                                                                                              |
| `validate-sql-columns`       | Columns must exist on the entity (single-table queries).                                                                                                 |
| `validate-sql-table`         | A single-table query must target the entity's table.                                                                                                     |
| `validate-count-projection`  | `count*` selects a single `count(...)`.                                                                                                                  |
| `validate-statement-kind`    | The method prefix matches the statement kind.                                                                                                            |
| `validate-projection`        | `find*` methods select every column (`SELECT *`).                                                                                                        |
| `validate-returning-columns` | `RETURNING` columns exist on the entity.                                                                                                                 |
| `reject-grouping-in-scalar`  | No `GROUP BY`/`HAVING` in `count*`, `findOne*`, `exists*`.                                                                                               |
| `validate-single-id`         | At most one `@Id`.                                                                                                                                       |
| `expand-select-star`         | Rewrites `SELECT *` into the entity's columns.                                                                                                           |
| `findone-limit`              | Appends `LIMIT 2` to `findOne*` queries.                                                                                                                 |
| `expand-returning-star`      | Rewrites `RETURNING *` into the entity's columns.                                                                                                        |
| `drop-redundant-order-by`    | Drops `ORDER BY` from `exists*`/`count*` queries.                                                                                                        |

## Setup without the Gradle plugin

Only for a project that cannot use the plugin (the plugin is the default; see `SKILL.md`):

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

ksp {
    arg("dialect", "postgresql") // mysql, mariadb, postgresql, sqlite
    arg("output-package", "com.example.db")
}

dependencies {
    // One KSP configuration per target that processes the code, e.g. kspCommonMainMetadata, kspJvm, kspMacosArm64.
    add("kspJvm", "io.github.smyrgeorge:sqlx4k-codegen:1.17.0")
}
```

## In-memory repositories for tests (experimental)

`sqlx4k-codegen-test` generates `InMemory{Interface}` classes, thread-safe in-memory implementations of each
repository, for unit tests without a database. Add it to the test KSP configuration only:

```kotlin
add("kspJvmTest", "io.github.smyrgeorge:sqlx4k-codegen-test:1.17.0")
```

```kotlin
val repo = InMemoryUserRepository()
// A CrudRepository takes the executor as an argument; any instance does, it is never used.
val saved = repo.save(db, User(name = "Alice", email = "alice@example.com")).getOrThrow()
// A ContextCrudRepository takes it from the scope: with(db) { repo.save(user) }.
```
