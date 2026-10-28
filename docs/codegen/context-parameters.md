# Context parameters

Optional: using `ContextCrudRepository` with [context parameters](https://kotlinlang.org/docs/context-parameters.html).

You can opt in to generated repositories that use Kotlin context parameters instead of passing a `QueryExecutor`
parameter to every method. This switches your repository to `ContextCrudRepository` and makes all generated CRUD and
`@Query` methods require an ambient `QueryExecutor` provided via a context parameter.

To enable this mode:

- Make your repository interface extend `ContextCrudRepository<T>` instead of `CrudRepository<T>`.
- Declare your `@Query` methods with a `context(context: QueryExecutor)` context parameter instead of an explicit
  `context` argument.

Repository interface example with context parameters:

```kotlin
@Repository
interface Sqlx4kRepository : ContextCrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    context(context: QueryExecutor)
    suspend fun findOneById(id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    context(context: QueryExecutor)
    suspend fun findAll(): Result<List<Sqlx4k>>
}
```

Usage with a context parameter (no explicit db parameter on each call):

```kotlin
val record = Sqlx4k(id = 1, test = "test")
with(db) {
    val inserted = Sqlx4kRepositoryImpl.insert(record).getOrThrow()
    val one = Sqlx4kRepositoryImpl.findOneById(1).getOrThrow()
}
```

If you prefer the explicit-parameter style, extend `CrudRepository<T>` instead. In that case, each generated method
takes a `QueryExecutor` (e.g., `db` or a transaction) as the first argument.
