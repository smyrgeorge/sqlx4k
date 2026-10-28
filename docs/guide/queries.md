# Running queries

All database interactions go through the
[QueryExecutor](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/QueryExecutor.kt)
interface, which provides a consistent, coroutine-based API for executing SQL statements. This interface is
implemented by:

- **Database drivers** (`PostgreSQL`, `MySQL`, `SQLite`, `SQLiteCipher`): for direct query execution using pooled
  connections.
- **Connection**: for [manual connection management](connection-pool.md#acquiring-and-using-connections).
- **Transaction**: for [transactional](transactions.md) query execution.

## execute

For SQL statements that modify data. Returns the number of affected rows (INSERT, UPDATE, DELETE, DDL statements):

```kotlin
// With raw SQL string
val affected: Long = db.execute("insert into users(id, name) values (1, 'Alice');").getOrThrow()
```

## executeWithResult

For inserts that generate an id. Like `execute`, but also returns the id the database generated for an inserted row
(`AUTO_INCREMENT` on MySQL/MariaDB, the rowid on SQLite), with no extra query:

```kotlin
val res: ExecuteResult = db.executeWithResult("insert into users(name) values ('Alice');").getOrThrow()
res.rowsAffected // 1
res.lastInsertId // the generated id, or null when the statement generated none
```

`lastInsertId` is always `null` on PostgreSQL, which has no such value: use `INSERT ... RETURNING id` with `fetchAll`
there. For a multi-row insert, MySQL reports the first generated id and SQLite the last.

## fetchAll

For queries that return data. Returns a `ResultSet` containing all rows (SELECT queries):

```kotlin
// With raw SQL string
val result: ResultSet = db.fetchAll("select * from users;").getOrThrow()
```

## fetch

For streaming large results. Returns a cold `Flow` of rows (SELECT queries). Unlike `fetchAll`, the rows are not
collected in memory: the query starts when the flow is collected and the rows are emitted as the database produces
them, in chunks of `fetchSize` rows (1,000 by default). The connection serving the query is busy until the flow
completes or the collector is cancelled; a flow of a `Connection` or `Transaction` holds it for the whole collection. A
failure fails the collection with an `SQLError`.

```kotlin
db.fetch("select * from events order by id;", fetchSize = 1_000)
    .map { it.get("payload").asString() }
    .collect { println(it) }

// With a RowMapper.
db.fetch("select * from users;", UserRowMapper).collect { user -> println(user) }
```

!!! note

    On MySQL (native targets), cancelling the collector of a `Connection` or `Transaction` flow waits for the row being
    read: see [MySQL](../databases/mysql.md).

## Prepared statements

Statements support both named and positional parameters:

```kotlin
// With named parameters:
val st1 = Statement
    .create("select * from sqlx4k where id = :id")
    .bind("id", 65)

db.fetchAll(st1).getOrThrow().map {
    val id: ResultSet.Row.Column = it.get("id")
    Test(id = id.asInt())
}

// With positional parameters:
val st2 = Statement
    .create("select * from sqlx4k where id = ?")
    .bind(0, 65)

db.fetchAll(st2).getOrThrow().map {
    val id: ResultSet.Row.Column = it.get("id")
    Test(id = id.asInt())
}
```

To map the rows to your own types, see [Mapping rows](mapping.md).
