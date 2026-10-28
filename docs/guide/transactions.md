# Transactions

```kotlin
val tx1: Transaction = db.begin().getOrThrow()
tx1.execute("delete from sqlx4k;").getOrThrow()
tx1.fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
tx1.commit().getOrThrow()
```

You can also execute entire blocks in a transaction scope.

```kotlin
db.transaction {
    execute("delete from sqlx4k;").getOrThrow()
    fetchAll("select * from sqlx4k;").getOrThrow().forEach { println(it) }
    // At the end of the block will auto commit the transaction.
    // If any error occurs, it will automatically trigger the rollback method.
}
```

`transaction` returns the value of its block. The transaction is rolled back when the block throws, and also when it
returns a failed `Result`. Either way, the error is rethrown; a failed commit throws an `SQLError` whose code is
`TransactionCommitFailed`. If the calling coroutine is cancelled, the transaction is rolled back too, as its work did
not complete.

To get a `Result` instead of an exception, use `transactionCatching`:

```kotlin
val res: Result<Long> = db.transactionCatching {
    execute("update accounts set balance = balance - 10 where id = 1;").getOrThrow()
    execute("update accounts set balance = balance + 10 where id = 2;").getOrThrow()
}
res.onFailure { e -> println("Transfer rolled back: ${e.message}") }
```

See [Error handling](errors.md) for what a failure holds.

To change the isolation level, see
[Setting the transaction isolation level](connection-pool.md#setting-the-transaction-isolation-level).

## Savepoints

A savepoint lets you roll back part of a transaction without ending it. The block form releases the savepoint on
success and rolls back to it on failure. Either way it returns a `Result` and never propagates the error.

```kotlin
db.transaction {
    execute("insert into orders (id, status) values (1, 'new');").getOrThrow()

    // If this fails, only the audit insert is undone.
    val audit: Result<Long> = savepoint {
        execute("insert into audit_log (order_id, event) values (1, 'created');").getOrThrow()
    }
    if (audit.isFailure) println("Audit insert skipped.")
}
```

You can also call `savepoint(name)`, `rollbackToSavepoint(name)` and `releaseSavepoint(name)` directly.

!!! note

    Savepoints are not supported by the SQLite driver on Android: see [SQLite](../databases/sqlite.md#tips-and-limitations).

## TransactionContext (coroutines)

When using coroutines, you can propagate a transaction through the coroutine context using `TransactionContext`. This
allows you to write small, composable suspend functions that either:

- start a transaction at the boundary of your use case, and
- inside helper functions call `TransactionContext.current()` to participate in the same transaction without having to
  propagate `Transaction` or `Driver` parameters everywhere.

```kotlin
val db = PostgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
    options = options
)

fun main() = runBlocking {
    TransactionContext.new(db) {
        // `this` is a TransactionContext and also a Transaction (delegation),
        // so you can call query methods directly:
        execute("insert into sqlx4k (id, test) values (66, 'test');").getOrThrow()

        // In deeper code, fetch the same context and keep using the same tx
        doBusinessLogic()
        doMoreBusinessLogic()
        doExtraBusinessLogic()
    }
}

suspend fun doBusinessLogic() {
    // Get the active transaction from the coroutine context
    val tx = TransactionContext.current()
    // Continue operating within the same database transaction
    tx.execute("update sqlx4k set test = 'updated' where id = 66;").getOrThrow()
}

// Or you can use the `withCurrent` method to get the transaction and execute the block in an ongoing transaction.
suspend fun doMoreBusinessLogic(): Unit = TransactionContext.withCurrent {
    // Continue operating within the same database transaction
}

// You can also pass the db instance to `withCurrent`.
// If a transaction is already active, the block runs within it; otherwise, a new transaction is started for the block.
suspend fun doExtraBusinessLogic(): Unit = TransactionContext.withCurrent(db) {
    // Continue operating within the same database transaction
}
```
