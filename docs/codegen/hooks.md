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

The hooks are available on every [repository interface](repositories.md#repository-interfaces).
