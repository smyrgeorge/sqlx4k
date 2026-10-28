# Repository hooks

The repository system provides hooks to implement cross-cutting concerns like metrics, tracing, logging, and
monitoring across all database operations. All repository interfaces extend `CrudRepositoryHooks<T>`, which provides
the following hooks:

**Entity-level hooks:**

- `preInsertHook(context: QueryExecutor, entity: T): T`: called before an entity is inserted
- `preUpdateHook(context: QueryExecutor, entity: T): T`: called before an entity is updated
- `preDeleteHook(context: QueryExecutor, entity: T): T`: called before an entity is deleted
- `afterInsertHook(context: QueryExecutor, entity: T): T`: called after an entity is inserted
- `afterUpdateHook(context: QueryExecutor, entity: T): T`: called after an entity is updated
- `afterDeleteHook(context: QueryExecutor, entity: T): T`: called after an entity is deleted

**Query-level hook:**

- `aroundQuery(methodName: String, statement: Statement, block: suspend () -> R): R`: wraps all query executions

The `aroundQuery` hook wraps **all** database operations (both `@Query` methods and CRUD operations), giving you a
single interception point for implementing metrics, distributed tracing, query logging, and other observability
features.

For how the hooks run in [batch operations](batch-operations.md), see the note there.

## List of repository interfaces

- [CrudRepository<T>](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/CrudRepository.kt)
- [ContextCrudRepository<T>](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/ContextCrudRepository.kt)
  (see [Context parameters](context-parameters.md))
- [ArrowCrudRepository<T>](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k-arrow/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/arrow/ArrowCrudRepository.kt)
  (using the `sqlx4k-arrow` package)
- [ArrowContextCrudRepository<T>](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k-arrow/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/arrow/ArrowContextCrudRepository.kt)
  (using the `sqlx4k-arrow` package)
