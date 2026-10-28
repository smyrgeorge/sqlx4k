# Error handling

The query APIs do not throw: they return a `Result`, and a failure holds an `SQLError`. Call `getOrThrow()` to throw
it (as the examples do), or inspect it with `onFailure`, `fold`, `exceptionOrNull()`, … With the
[Arrow repositories](../codegen/repositories.md#repository-interfaces), the generated methods return an
`Either<SQLError, T>` instead.

```kotlin
val res: Result<Long> = db.execute("delete from users where id = 1;")
res.onFailure { e -> println("Delete failed: ${e.message}") }
```

The cancellation of the calling coroutine is never turned into a failed `Result`: it is rethrown, as usual with
coroutines.

## SQLError

Every `SQLError` carries a `code`, the failure from the library's point of view. When the database itself rejected
the statement (code `Database`), it also carries what the database said about it:

| Property     | Set on                    | What it holds                                                                                                                                                     |
|--------------|---------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `code`       | every error               | The sqlx4k error code: `Database`, `PoolTimedOut`, `OptimisticLockFailed`, … (see [Error codes](#error-codes)).                                                   |
| `kind`       | every error               | A portable classification of the database error, the same on every database and platform (see [Error kinds](#error-kinds)). `Other` when not mapped.              |
| `sqlState`   | PostgreSQL, MySQL         | The five-character SQLSTATE of the SQL standard, e.g. `23505` (unique violation) on PostgreSQL. `null` on SQLite, which has none.                                 |
| `nativeCode` | MySQL, SQLite             | The database's own error number: the server error on MySQL (e.g. `1062`, `ER_DUP_ENTRY`), the extended result code on SQLite (e.g. `2067`). `null` on PostgreSQL. |
| `cause`      | when a lower layer failed | The original exception, e.g. the driver's exception on the JVM.                                                                                                   |

## Error kinds

`kind` covers what application code usually branches on, without parsing database-specific codes: the integrity
constraint violations (report them to the user) and the concurrency failures (retry the transaction).

| Kind                   | Meaning                                                                                         |
|------------------------|-------------------------------------------------------------------------------------------------|
| `UniqueViolation`      | A unique or primary key constraint was violated.                                                |
| `ForeignKeyViolation`  | A foreign key constraint was violated.                                                          |
| `NotNullViolation`     | A not-null constraint was violated.                                                             |
| `CheckViolation`       | A check constraint was violated.                                                                |
| `ExclusionViolation`   | An exclusion constraint was violated (PostgreSQL only).                                         |
| `Deadlock`             | The transaction was chosen as a deadlock victim (PostgreSQL `40P01`, MySQL `1213`).             |
| `SerializationFailure` | The transaction could not be serialized with concurrent ones (PostgreSQL `40001`).              |
| `LockTimeout`          | A lock could not be acquired in time (PostgreSQL `55P03`, MySQL `1205`, SQLite busy or locked). |
| `Other`                | Not a database error, or one that is not mapped: use `sqlState` and `nativeCode`.               |

For instance, to report a duplicate email:

```kotlin
val statement = Statement
    .create("insert into users(name, email) values (:name, :email)")
    .bind("name", "Alice")
    .bind("email", "alice@example.com")

db.execute(statement).onFailure { e ->
    when ((e as? SQLError)?.kind) {
        SQLError.Kind.UniqueViolation -> println("The email is already taken.")
        else -> throw e
    }
}
```

Or to retry a transaction that lost to a concurrent one:

```kotlin
suspend fun <T> retrying(attempts: Int = 3, block: suspend () -> T): T {
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: SQLError) {
            if (e.kind != SQLError.Kind.Deadlock && e.kind != SQLError.Kind.SerializationFailure) throw e
        }
    }
    return block()
}

val total: Long = retrying {
    db.transaction {
        execute("update accounts set balance = balance - 10 where id = 1;").getOrThrow()
        execute("update accounts set balance = balance + 10 where id = 2;").getOrThrow()
    }
}
```

!!! note

    Constraint violations are classified by the driver itself. The concurrency failures are derived from the SQLSTATE
    on PostgreSQL, the error number on MySQL and the result code on SQLite, with the same mapping on the native and
    the JVM drivers. On the SQLite driver for Android, `nativeCode` is recovered from the exception message, and is
    therefore best-effort.

## Error codes

| Code                                                                    | When                                                                                                                                             |
|-------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------|
| `Database`                                                              | The database rejected the statement: see `kind`, `sqlState` and `nativeCode`.                                                                    |
| `PoolTimedOut`                                                          | No connection became available within the pool's `acquireTimeout` (see [Connection pool](connection-pool.md)).                                   |
| `PoolClosed`                                                            | The pool was closed.                                                                                                                             |
| `Pool`                                                                  | A connection could not be opened, e.g. the server refused it or the TLS handshake failed.                                                        |
| `WorkerCrashed`                                                         | The driver's background worker failed.                                                                                                           |
| `ConnectionIsClosed`                                                    | The connection was used after it was closed (returned to the pool).                                                                              |
| `TransactionIsClosed`                                                   | The transaction was used after its commit or rollback.                                                                                           |
| `TransactionCommitFailed`, `TransactionRollbackFailed`                  | The commit or the rollback of a [transaction](transactions.md) failed.                                                                           |
| `CannotDecode`                                                          | A `NULL` column was read with a function that does not allow it, e.g. `asInt()` (see [Reading values](mapping.md#reading-values)).               |
| `CannotDecodeEnumValue`                                                 | `asEnum<T>()` read a value that is not the name of a constant of `T`.                                                                            |
| `PositionalParameterOutOfBounds`, `NamedParameterNotFound`              | A value was bound to a parameter the statement does not have.                                                                                    |
| `PositionalParameterValueNotSupplied`, `NamedParameterValueNotSupplied` | A parameter of the statement was left without a value.                                                                                           |
| `MissingValueConverter`                                                 | A bound value has a type the driver cannot send: register a [value converter](mapping.md#custom-value-converters).                               |
| `EmptyCollection`                                                       | An empty collection was bound (see [Binding collections](queries.md#binding-collections)).                                                       |
| `InvalidIdentifier`, `UnsafeStringContent`                              | A name or a string the library writes into SQL itself (e.g. a savepoint or a migration table) was rejected.                                      |
| `Migrate`                                                               | A [migration](migrations.md) failed: a duplicate or missing version, a changed or empty file, …                                                  |
| `EmptyResultSet`                                                        | A generated repository method expected a row and got none, e.g. `delete` of an entity that does not exist.                                       |
| `MultipleRowsReturned`                                                  | A generated `findOneBy…` method matched more than one row.                                                                                       |
| `RowMismatch`                                                           | A generated `batchInsert` or `batchUpdate` affected a different number of rows than the number of entities.                                      |
| `OptimisticLockFailed`                                                  | A versioned entity was modified or deleted since it was read (see [Optimistic locking](../codegen/entities.md#optimistic-locking-with-version)). |
| `UnknownError`                                                          | Any other failure.                                                                                                                               |
