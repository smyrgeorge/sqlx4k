# The driver API: plain SQL

Docs: <https://smyrgeorge.github.io/sqlx4k/guide/>

Every database (`PostgreSQL`, `MySQL`, `SQLite`, `SQLiteCipher`), `Connection` and `Transaction` is a
`QueryExecutor`. Its calls are `suspend` functions that return a `Result`. The repositories' `@Query` validations and
optimizations do not apply here: the SQL runs as written.

Prefer repositories (see `SKILL.md`). Use this API for what a repository cannot express: DDL, dynamic SQL, a result that
does not map to an entity. Transactions, the connection pool and the error handling below apply to repositories too.

## Running queries

```kotlin
// Rows affected (INSERT, UPDATE, DELETE, DDL).
val affected: Long = db.execute("delete from users where name = 'Bob';").getOrThrow()

// Rows affected and the generated id (AUTO_INCREMENT on MySQL, the rowid on SQLite; always null on PostgreSQL,
// where you use INSERT ... RETURNING id with fetchAll instead).
val res: ExecuteResult = db.executeWithResult("insert into users(name) values ('Alice');").getOrThrow()
res.rowsAffected
res.lastInsertId

// All the rows, in memory.
val rows: ResultSet = db.fetchAll("select * from users;").getOrThrow()

// A cold Flow, streamed in chunks of fetchSize rows: for large results.
db.fetch("select * from events order by id;", fetchSize = 1_000).collect { row -> println(row) }
```

`fetchAll` and `fetch` also take a `RowMapper<T>` as their second argument, and return `List<T>` / `Flow<T>`.

## Statements and binding

Bind values instead of concatenating them into the SQL (prepared statements, no SQL injection):

```kotlin
val byName = Statement
    .create("select * from users where name = :name")   // named parameters
    .bind("name", "Alice")

val byId = Statement
    .create("select * from users where id = ?")         // positional parameters, from 0
    .bind(0, 65)

val inList = Statement
    .create("select * from users where id in :ids")     // a collection expands to ($1, $2, $3)
    .bind("ids", listOf(1, 2, 3))

val nullUuid = Statement
    .create("update users set external_id = :externalId where id = :id")
    .bindNull("externalId", Uuid::class)                // a typed NULL, for uuid/timestamptz/array columns
    .bind("id", 1)

db.fetchAll(byName, UserRowMapper).getOrThrow()
```

- An empty collection fails with `SQLError.Code.EmptyCollection` (`in ()` is not valid SQL).
- `NoWrappingTuple(list)` expands without the parentheses, e.g. for `select array[?]` on PostgreSQL.

### Types you can bind

`String`, `Char`, `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`, `ByteArray`, `kotlin.time.Instant`,
`kotlinx.datetime.LocalDate` / `LocalTime` / `LocalDateTime`, `kotlin.uuid.Uuid`, enums (by name), collections and
arrays (expanded), `null`. Anything else (e.g. `BigDecimal`) needs a `ValueEncoder` registered on the database, or the
call fails with `MissingValueConverter`:

```kotlin
object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
}

val db = PostgreSQL(
    url = "postgresql://localhost:5432/app", username = "app", password = "secret",
    encoders = ValueEncoderRegistry().register<Money>(MoneyEncoder),
)
```

## Reading rows

```kotlin
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull

object OrderRowMapper : RowMapper<Order> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Order = Order(
        id = row.get("id").asLong(),                      // by name, or row.get(0) by position
        createdAt = row.get("created_at").asInstant(),
        externalId = row.get("external_id").asUuidOrNull(),
        note = row.get("note").asStringOrNull(),          // asString()/asStringOrNull()/isNull() are members
    )
}
```

The readers (each with an `…OrNull()` variant for nullable columns; the plain one fails with `CannotDecode` on `NULL`):
`asChar`, `asShort`, `asInt`, `asLong`, `asUShort`, `asUInt`, `asULong`, `asFloat`, `asDouble`, `asBoolean`
(`true`/`false`, `t`/`f`, `1`/`0`), `asUuid`, `asEnum<T>()`, `asLocalDate`, `asLocalTime`, `asLocalDateTime`,
`asInstant`, `asByteArray`. PostgreSQL arrays (`io.github.smyrgeorge.sqlx4k.postgres.extensions`): `asIntArray`,
`asLongArray`, … `asStringList`, `asNullableStringList`, `asUuidList`, `asLocalDateTimeList`, `asInstantList`,
`asByteArrayList`.

For `@Table` entities, use the generated `{Entity}AutoRowMapper` instead of writing one.

## Transactions

```kotlin
// Block form: committed at the end, rolled back if the block throws or returns a failed Result. Returns the block's
// value, and rethrows the error.
val total: Long = db.transaction {
    execute("update accounts set balance = balance - 10 where id = 1;").getOrThrow()
    execute("update accounts set balance = balance + 10 where id = 2;").getOrThrow()
}

// The same, returning a Result instead of throwing.
val res: Result<Long> = db.transactionCatching { /* ... */ 0L }

// Manual form.
val tx: Transaction = db.begin().getOrThrow()
tx.execute("delete from users;").getOrThrow()
tx.commit().getOrThrow() // or tx.rollback()

// Savepoints, inside a transaction: rolls back only the block on failure, and returns a Result.
db.transaction {
    execute("insert into orders (id, status) values (1, 'new');").getOrThrow()
    val audit: Result<Long> = savepoint {
        execute("insert into audit_log (order_id, event) values (1, 'created');").getOrThrow()
    }
}
```

Savepoints are not supported by the SQLite driver on Android.

### TransactionContext (a transaction through the coroutine context)

```kotlin
TransactionContext.new(db) {                     // starts a transaction; `this` is a Transaction
    execute("insert into users(name) values ('Alice');").getOrThrow()
    rename()                                     // runs in the same transaction
}

suspend fun rename(): Unit = TransactionContext.withCurrent {   // joins the current transaction (fails if none)
    execute("update users set name = 'Bob' where name = 'Alice';").getOrThrow()
}
// TransactionContext.withCurrent(db) { } joins the current transaction, or starts one.
```

## The connection pool

```kotlin
val options = ConnectionPool.Options.builder()
    .minConnections(2)
    .maxConnections(10)                 // default 10
    .acquireTimeout(5.seconds)          // fails with PoolTimedOut when no connection is free in time
    .idleTimeout(10.minutes)
    .maxLifetime(30.minutes)
    .build()

val db = PostgreSQL(url = "...", username = "...", password = "...", options = options)
```

- Calls on `db` borrow a connection per call. To run several statements on one connection without a transaction:
  `val conn = db.acquire().getOrThrow()`, and `conn.close()` in a `finally` to return it.
- `conn.setTransactionIsolationLevel(Transaction.IsolationLevel.Serializable)` (`ReadUncommitted`, `ReadCommitted`,
  `RepeatableRead`, `Serializable`) applies to that connection until it returns to the pool. Defaults: `ReadCommitted`
  on PostgreSQL, `RepeatableRead` on MySQL; no effect on SQLite.
- `db.poolSize()`, `db.poolIdleSize()`: the open and idle connections. `db.close()` shuts the pool down.
