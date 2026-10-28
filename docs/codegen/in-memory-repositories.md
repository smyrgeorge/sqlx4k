# In-memory repositories

!!! warning "Experimental"

    In-memory repository generation is currently in early development; its behavior and the generated API may change
    in future releases.

The companion `sqlx4k-codegen-test` module generates a thread-safe, **in-memory** implementation of every `@Repository`
interface, so you can unit-test the code that depends on your repositories without a real database. For a repository
named `Sqlx4kRepository`, it generates a class named `InMemorySqlx4kRepository`.

It is a separate module from `sqlx4k-codegen`, so in-memory generation is opt-in per dependency: add it only to the KSP
configuration that processes your repositories:

```kotlin
ksp {
    // The generated implementations are emitted into this package (same option used by sqlx4k-codegen).
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
}

dependencies {
    // Real @Repository implementations (production).
    add("kspMacosArm64", implementation("io.github.smyrgeorge:sqlx4k-codegen:x.y.z"))
    // In-memory implementations for unit tests.
    add("kspMacosArm64Test", "io.github.smyrgeorge:sqlx4k-codegen-test:x.y.z")
}
```

Given the `Sqlx4kRepository` from [Code generation](index.md#entities-and-repositories), use the generated
implementation directly in your tests:

```kotlin
val repo = InMemorySqlx4kRepository()

// A QueryExecutor is still accepted to match the interface, but it is never touched:
// it works entirely in memory, so any instance (even a no-op fake) will do.
val saved = repo.insert(db, Sqlx4k(id = 1, test = "test")).getOrThrow()
val all = repo.findAll(db).getOrThrow()
```

## What the generated implementation provides

- **Thread-safe storage:** entities live in a `HashMap` guarded by a `Mutex`.
- **Full CRUD:** `insert`, `update`, `delete`, `save`, `batchInsert`, `batchUpdate`. Numeric `@Id` keys with
  `insert = false` get auto-incrementing ids; application-provided ids (`@Id(insert = true)`) are stored as given.
  `@Version` entities get the same optimistic locking as the real implementation: `update`/`delete` only apply when
  the stored version matches (failing with `OptimisticLockFailed` otherwise) and `update` increments it.
- **Whole-table queries:** `findAll`, `countAll`, `deleteAll`.
- **Derived `@Query` methods:** the in-memory behavior is derived from each method's `@Query` **SQL**: the statement is
  parsed and its `WHERE` clause becomes a predicate over the stored entities (columns are mapped back to properties via
  snake_case / `@Column`). Supports `=`, `<>`, `<`, `<=`, `>`, `>=`, `IS [NOT] NULL`, `AND`/`OR`, named parameters, and
  simple literals, for `find*`/`stream*`/`count*`/`exists*` (SELECT), `delete*` (DELETE), and `execute*`
  (UPDATE `SET …`/DELETE). For example `@Query("select * from users where name = :name")` behaves like
  `filter { it.name == name }`.
- **Repository hooks:** the overridden `preInsert/afterInsert/... /aroundQuery` [hooks](hooks.md) are honored exactly
  like the real generated implementations.
- **Test helpers:** `clear()` empties the store (and resets the id sequence) and `findAllStored()` returns a snapshot.

## Methods the generator cannot translate

Anything the generator cannot translate is emitted as a stub that throws `NotImplementedError`:

- a `WHERE` it doesn't understand (e.g. `LIKE`, `IN`, subqueries, function calls),
- a query whose result also depends on a join, `DISTINCT`, `GROUP BY`/`HAVING`, a `LIMIT`/`OFFSET` or (for
  `find*`/`stream*`) an `ORDER BY`,
- an `execute*` that isn't a plain UPDATE/DELETE.

The generated class is `open`, so provide the behavior yourself by subclassing and overriding just those methods. Use
the `withStore { }` helper for thread-safe access to the backing map (the `MutableMap` is the lambda receiver):

```kotlin
class TestSqlx4kRepository : InMemorySqlx4kRepository() {
    // A method whose WHERE the generator could not translate (e.g. LIKE), so it was left as a stub.
    override suspend fun findAllByTestLike(context: QueryExecutor, pattern: String): Result<List<Sqlx4k>> =
        withStore {
            val prefix = pattern.removeSuffix("%")
            runCatching { values.filter { it.test.startsWith(prefix) } }
        }
}
```
