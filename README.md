# sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)

![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Jvm&color=blue)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)
![](https://img.shields.io/static/v1?label=&message=iOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Android&color=blue)

A coroutine-first SQL toolkit with compile-time query validations for Kotlin Multiplatform. PostgreSQL, MySQL/MariaDB,
and SQLite supported.

---

**sqlx4k** is not an ORM. Instead, it provides a comprehensive toolkit of primitives and utilities to communicate
directly with your database. The focus is on giving you control while catching errors early through compile-time query
validation—preventing runtime surprises before they happen
(see [SQL syntax validation (compile-time)](#sql-syntax-validation-compile-time)
and [SQL schema validation (compile-time)](#sql-schema-validation-compile-time) for more details).

The library is designed to be extensible, with a growing ecosystem of tools and extensions like PGMQ (PostgreSQL Message
Queue), SQLDelight integration, and more.

📖 [Documentation](https://smyrgeorge.github.io/sqlx4k/)

🏠 [Homepage](https://smyrgeorge.github.io/) (under construction)

#### 📰 Articles

Short deep‑dive posts covering Kotlin/Native, FFI, and Rust ↔ Kotlin interop used in sqlx4k:

- *Introduction to the Kotlin Native and FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-1/),
  [[Part 2]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-2/)
- *Interoperability between Kotlin and Rust, using FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---interoperability-between-kotlin-and-rust-using-ffi-part-1/),
  (Part 2 soon)

## Features

- [Supported databases](#supported-databases)
- [Async I/O](#async-io)
- [Connection pool and settings](#connection-pool)
- [Acquiring and using connections](#acquiring-and-using-connections)
- [Running queries](#running-queries)
- [Prepared statements (named and positional parameters)](#prepared-statements)
- [Row mappers](#rowmappers)
- [Custom Value Converters](#custom-value-converters)
- [Transactions and coroutine TransactionContext](#transactions) · [TransactionContext (coroutines)](#transactioncontext-coroutines)
- [Code generation: CRUD and @Repository implementations](#code-generation-crud-and-repository-implementations)
    - [Auto-Generated RowMapper](#auto-generated-rowmapper)
    - [Batch Operations](#batch-operations)
    - [Property-Level Converters](#property-level-converters-converter)
    - [Context-Parameters](#context-parameters)
    - [Repository hooks](#repository-hooks)
    - [List of Repository interfaces](#list-of-repository-interfaces)
    - [SQL syntax validation (compile-time)](#sql-syntax-validation-compile-time)
    - [SQL schema validation (compile-time)](#sql-schema-validation-compile-time)
- [Database migrations](#database-migrations)
- [PostgreSQL LISTEN/NOTIFY](#listennotify-only-for-postgresql)
- [Extensions](#extensions)
    - [PostgreSQL Message Queue (PGMQ)](#postgresql-message-queue-pgmq)
    - [SQLDelight](#sqldelight)
- [Supported targets](#supported-targets)

### Next Steps (contributions are welcome)

- Create and publish sqlx4k-gradle-plugin
- Support streaming large tables (e.g. with cursors)
- Validate queries at compile time (avoid runtime errors)
    - Syntax checking is already supported (using the `@Query` annotation) ✅
    - Validate queries by accessing the DB schema ✅
    - Validate query literal types (type check query parameters)
- Add support for SQLite JVM target ✅
- WASM support (?).

### Supported Databases

- ![MySQL](https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white)
- ![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white)
- ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-316192?logo=postgresql&logoColor=white)
- ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white)

### Async-io

The driver is designed with full support for non-blocking I/O, enabling seamless integration with modern,
high-performance applications. By leveraging asynchronous, non-blocking operations, it ensures efficient resource
management, reduces latency, and improves scalability.

### Connection Pool

### Connection Pool Settings

The driver allows you to configure connection pool settings directly from its constructor, giving you fine-grained
control over how database connections are managed. These settings are designed to optimize performance and resource
utilization for your specific application requirements.

#### Key Configuration Options:

- **`minConnections`**  
  Specifies the minimum number of connections to maintain in the pool at all times. This ensures that a baseline number
  of connections are always ready to serve requests, reducing the latency for acquiring connections during peak usage.

- **`maxConnections`**  
  Defines the maximum number of connections that can be maintained in the pool. This setting helps limit resource usage
  and ensures the pool does not exceed the available database or system capacity.

- **`acquireTimeout`**  
  Sets the maximum duration to wait when attempting to acquire a connection from the pool. If a connection cannot be
  acquired within this time, an exception is thrown, allowing you to handle connection timeouts gracefully.

- **`idleTimeout`**  
  Specifies the maximum duration a connection can remain idle before being closed and removed from the pool. This helps
  clean up unused connections, freeing up resources.

- **`maxLifetime`**  
  Defines the maximum lifetime for individual connections. Once a connection reaches this duration, it is closed and
  replaced, even if it is active, helping prevent issues related to stale or long-lived connections.

By adjusting these parameters, you can fine-tune the driver's behavior to match the specific needs of your application,
whether you're optimizing for low-latency responses, high-throughput workloads, or efficient resource utilization.

```kotlin
// Additionally, you can set minConnections, acquireTimeout, idleTimeout, etc. 
val options = Driver.Pool.Options.builder()
    .maxConnections(10)
    .build()

/**
 * The following urls are supported:
 *  postgresql://
 *  postgresql://localhost
 *  postgresql://localhost:5433
 *  postgresql://localhost/mydb
 *
 * Additionally, you can use the `postgreSQL` function, if you are working in a multiplatform setup.
 */
val db = PostgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
    options = options
)

/**
 *  The connection URL should follow the nex pattern,
 *  as described by [MySQL](https://dev.mysql.com/doc/connector-j/8.0/en/connector-j-reference-jdbc-url-format.html).
 *  The generic format of the connection URL:
 *  mysql://[host][/database][?properties]
 */
val db = MySQL(
    url = "mysql://localhost:13306/test",
    username = "mysql",
    password = "mysql"
)

/**
 * The following urls are supported:
 * `sqlite::memory:`            | Open an in-memory database.
 * `sqlite:data.db`             | Open the file `data.db` in the current directory.
 * `sqlite://data.db`           | Open the file `data.db` in the current directory.
 * `sqlite:///data.db`          | Open the file `data.db` from the root (`/`) directory.
 * `sqlite://data.db?mode=ro`   | Open the file `data.db` for read-only access.
 */
val db = SQLite(
    url = "sqlite://test.db", // If the `test.db` file is not found, a new db will be created.
    options = options
)
```

### Acquiring and using connections

The driver provides two complementary ways to run queries:

- Directly through the database instance (recommended). Each call acquires a pooled connection, executes the work, and
  returns it to the pool automatically.
- Manually acquire a connection from the pool when you need to batch multiple operations on the same connection without
  starting a transaction.

Notes:

- When you manually acquire a connection, you must release it to return it to the pool.

Examples (PostgreSQL shown, similar to MySQL/SQLite):

```kotlin
// Manual connection acquisition (remember to release)
val conn: Connection = db.acquire().getOrThrow()
try {
    conn.execute("insert into users(id, name) values (2, 'Bob');").getOrThrow()
    val rs = conn.fetchAll("select * from users;").getOrThrow()
    // ...
} finally {
    conn.close().getOrThrow() // Return to pool
}
```

#### Setting Transaction Isolation Level

You can set the transaction isolation level on a connection to control the degree of visibility between concurrent
transactions.

```kotlin
val conn: Connection = db.acquire().getOrThrow()
// Set the isolation level before starting operations
conn.setTransactionIsolationLevel(Transaction.IsolationLevel.Serializable).getOrThrow()
```

### Running Queries

All database interactions go through
the [QueryExecutor](sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/QueryExecutor.kt) interface, which provides
a consistent, coroutine-based API for executing SQL statements. This interface is implemented by:

- **Database drivers** (`PostgreSQL`, `MySQL`, `SQLite`) - for direct query execution using pooled connections
- **Connection** - for manual connection management
- **Transaction** - for transactional query execution

The `QueryExecutor` interface provides two primary methods for running queries:

#### execute() - For SQL statements that modify data

Returns the number of affected rows (INSERT, UPDATE, DELETE, DDL statements):

```kotlin
// With raw SQL string
val affected: Long = db.execute("insert into users(id, name) values (1, 'Alice');").getOrThrow()
```

#### fetchAll() - For queries that return data

Returns a `ResultSet` containing all rows (SELECT queries):

```kotlin
// With raw SQL string
val result: ResultSet = db.fetchAll("select * from users;").getOrThrow()
```

### Prepared Statements

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

### RowMapper(s)

```kotlin
object Sqlx4kRowMapper : RowMapper<Sqlx4k> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Sqlx4k {
        val id: ResultSet.Row.Column = row.get(0)
        val test: ResultSet.Row.Column = row.get(1)
        // Use built-in mapping methods to map the values to the corresponding type.
        return Sqlx4k(id = id.asInt(), test = test.asString())
    }
}

val res: List<Sqlx4k> = db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
```

### Custom Value Converters

For custom types that don't have builtin decoders, you can register custom `ValueEncoder` implementations. A
`ValueEncoder` provides bidirectional conversion between your custom type and the database representation.

**Creating a Custom Encoder:**

```kotlin
// Define your custom type
data class Money(val amount: BigDecimal, val currency: String) {
    override fun toString(): String = "$amount $currency"

    companion object {
        fun parse(value: String): Money {
            val parts = value.split(" ")
            return Money(BigDecimal(parts[0]), parts[1])
        }
    }
}

// Create a ValueEncoder for your type
object MoneyEncoder : ValueEncoder<Money> {
    override fun encode(value: Money): Any = value.toString()
    override fun decode(value: ResultSet.Row.Column): Money = Money.parse(value.asString())
}
```

**Registering and Using Custom Encoders:**

```kotlin
// Create a registry and register your encoder
val registry = ValueEncoderRegistry()
    .register<Money>(MoneyEncoder)

// Use the registry when mapping rows
val mapper = Sqlx4kAutoRowMapper
val entity = mapper.map(row, registry)
```

### Transactions

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

### TransactionContext (coroutines)

When using coroutines, you can propagate a transaction through the coroutine context using `TransactionContext`.
This allows you to write small, composable suspend functions that either:

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

### Code-Generation, `CRUD` and `@Repository` Implementations

For this operation you will need to include the `KSP` plugin to your project.

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

// Then you need to configure the processor (it will generate the necessary code files).
ksp {
    // Optional: pick the SQL dialect for CRUD generation from @Table classes.
    // Supported dialects:
    // arg("dialect", "mysql")
    // arg("dialect", "postgresql")

    // Required: where to place the generated sources.
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")

    // Compile-time SQL syntax checking for @Query methods (default = true).
    // Set to "false" to turn it off if you use vendor-specific syntax not understood by the parser.
    // arg("validate-sql-syntax", "false")
}

dependencies {
    // Will generate code for macosArm64. Add more targets if you want.
    add("kspMacosArm64", implementation("io.github.smyrgeorge:sqlx4k-codegen:x.y.z"))
}
```

Then create your data class that will be mapped to a table:

```kotlin
@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true) // Will be included in the insert query.
    val id: Int,
    val test: String
)

@Repository
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    // The processor will validate the SQL syntax in the @Query methods.
    // If you want to disable this validation, you can set the "validate-sql-syntax" arg to "false".
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    suspend fun findAll(context: QueryExecutor): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    suspend fun countAll(context: QueryExecutor): Result<Long>
}
```

> [!NOTE]
> Besides your @Query methods, because your interface extends `CrudRepository<T>`, the generator also adds the CRUD
> helper methods automatically: `insert`, `update`, `delete`, and `save`.

> [!NOTE]
> By default, the code generator automatically uses the auto-generated `RowMapper` for your entity (e.g.,
> `Sqlx4kAutoRowMapper`). You can override this behavior by explicitly providing a custom mapper:
> `@Repository(mapper = CustomRowMapper::class)`.

Then in your code you can use it like:

```kotlin
// Insert a new record.
val record = Sqlx4k(id = 1, test = "test")
val res: Sqlx4k = Sqlx4kRepositoryImpl.insert(db, record).getOrThrow()
// Execute a generated query.
val res: List<Sqlx4k> = Sqlx4kRepositoryImpl.selectAll(db).getOrThrow()
```

For more details, take a look at the [examples](./examples).

#### Auto-Generated RowMapper

When you annotate a class with `@Table`, the code generator automatically creates a `RowMapper` implementation for
mapping database rows to your entity. The mapper is named `{ClassName}AutoRowMapper` and is generated in the same file
as your CRUD queries.

For example, for a class named `Sqlx4k`, the generator creates `Sqlx4kAutoRowMapper`:

```kotlin
object Sqlx4kAutoRowMapper : RowMapper<Sqlx4k> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Sqlx4k {
        val id = row.get("id").asInt()
        val test = row.get("test").asString()
        return Sqlx4k(id = id, test = test)
    }
}
```

**Dialect-Specific Decoders:**

When using PostgreSQL, set the dialect in your KSP configuration to enable PostgreSQL-specific decoders:

```kotlin
ksp {
    arg("dialect", "postgresql")  // Enables PostgreSQL array decoders
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
}
```

Supported dialects:

- `"postgresql"` - Enables PostgreSQL-specific extensions (array types)
- `"mysql"` - Adjusts CRUD query generation for MySQL compatibility
- Default (or `"generic"`) - Uses standard SQL with builtin decoders

#### Batch Operations

The code generator creates batch `INSERT` and `UPDATE` operations for efficiently processing multiple entities in a
single database round-trip. These operations use multi-row SQL statements with `RETURNING` clauses to retrieve
database-generated values.

**Batch Insert:**

```kotlin
// Insert multiple entities at once
val users = listOf(
    User(name = "Alice", email = "alice@example.com"),
    User(name = "Bob", email = "bob@example.com"),
    User(name = "Charlie", email = "charlie@example.com")
)

// Returns all inserted entities with generated IDs
val insertedUsers: Result<List<User>> = userRepository.batchInsert(db, users)
```

**Batch Update:**

```kotlin
// Update multiple entities at once
val updatedUsers = users.map { it.copy(status = "active") }

// Returns all updated entities with any DB-modified values
val result: Result<List<User>> = userRepository.batchUpdate(db, updatedUsers)
```

**Database Support:**

| Operation     | PostgreSQL | SQLite | MySQL | Generic |
|---------------|:----------:|:------:|:-----:|:-------:|
| `batchInsert` |     ✅      |   ✅    |   ❌   |    ✅    |
| `batchUpdate` |     ✅      |   ✅    |   ❌   |    ✅    |

- **PostgreSQL**: Full support for both batch operations using multi-row `INSERT ... RETURNING` and
  `UPDATE ... FROM (VALUES ...) ... RETURNING` syntax.
- **SQLite**: Full support for both batch operations using multi-row `INSERT ... RETURNING` and
  `WITH ... UPDATE ... FROM ... RETURNING` syntax (CTE-based approach).
- **MySQL**: Neither batch operation is supported because MySQL lacks `RETURNING` clause support.
- **Generic**: Generates code for both operations but actual support depends on the underlying database.

> [!NOTE]
> For unsupported operations, the generated repository methods throw `UnsupportedOperationException` at runtime.
> The generated code includes documentation indicating which dialects support each operation.

#### Property-Level Converters (@Converter)

For custom types, you can use the `@Converter` annotation to specify a `ValueEncoder` directly on the property.
This provides compile-time type safety, avoids runtime registry lookups, and eliminates object instantiation overhead.

**Defining a Custom Encoder:**

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

**Using @Converter on Properties:**

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

> [!NOTE]
> When using `@Converter`, you don't need to register the encoder in a `ValueEncoderRegistry`.
> The encoder object is referenced directly in the generated code, avoiding any instantiation overhead.

#### Context-Parameters

Optional: Using `ContextCrudRepository` with [context-parameters](https://kotlinlang.org/docs/context-parameters.html).

You can opt in to generated repositories that use Kotlin context-parameters instead of passing a QueryExecutor
parameter to every method. This switches your repository to ContextCrudRepository and makes all generated CRUD and
@Query methods require an ambient QueryExecutor provided via a context-parameter.

To enable this mode:

- Make your repository interface extend ContextCrudRepository<T> instead of CrudRepository<T>.
- Declare your @Query methods with a context(context: QueryExecutor) receiver instead of an explicit context parameter.

Repository interface example with context receivers:

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

Usage with a context-parameter (no explicit db parameter on each call):

```kotlin
val record = Sqlx4k(id = 1, test = "test")
with(db) {
    val inserted = Sqlx4kRepositoryImpl.insert(record).getOrThrow()
    val one = Sqlx4kRepositoryImpl.findOneById(1).getOrThrow()
}
```

If you prefer the explicit-parameter style, keep CrudRepository<T> and do not set enable-context-parameters. In that
case, each generated method takes a QueryExecutor (e.g., db or transaction) as the first argument.

#### Repository Hooks

The repository system provides powerful hooks to implement cross-cutting concerns like metrics, tracing, logging, and
monitoring across all database operations. All repository interfaces extend `CrudRepositoryHooks<T>`, which provides the
following hooks:

**Entity-Level Hooks:**

- `preInsertHook(context: QueryExecutor, entity: T): T` - Called before an entity is inserted
- `preUpdateHook(context: QueryExecutor, entity: T): T` - Called before an entity is updated
- `preDeleteHook(context: QueryExecutor, entity: T): T` - Called before an entity is deleted
- `afterInsertHook(context: QueryExecutor, entity: T): T` - Called after an entity is inserted
- `afterUpdateHook(context: QueryExecutor, entity: T): T` - Called after an entity is updated
- `afterDeleteHook(context: QueryExecutor, entity: T): T` - Called after an entity is deleted

**Query-Level Hook:**

- `aroundQuery(methodName: String, statement: Statement, block: suspend () -> R): R` - Wraps all query executions

The `aroundQuery` hook is particularly powerful as it wraps **all** database operations (both `@Query` methods and CRUD
operations), giving you a single interception point for implementing metrics, distributed tracing, query logging, and
other observability features.

#### List of Repository interfaces

- [CrudRepository<T>](sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/CrudRepository.kt)
- [ContextCrudRepository<T>](sqlx4k/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/ContextCrudRepository.kt)
- [ArrowCrudRepository<T>](sqlx4k-arrow/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/arrow/ArrowCrudRepository.kt)
  (using the `sqlx4k-arrow` package)
- [ArrowContextCrudRepository<T>](sqlx4k-arrow/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/arrow/ArrowContextCrudRepository.kt)
  (using the `sqlx4k-arrow` package)

#### SQL syntax validation (compile-time)

- What it is: during code generation, sqlx4k parses the SQL string in each `@Query` method using `JSqlParser`. If the
  parser detects a syntax error, the build fails early with a clear error message pointing to the offending repository
  method.
- What it checks: only SQL syntax. It does not verify that tables/columns exist, parameter names match, or types are
  compatible.
- When it runs: at KSP processing time, before your code is compiled/run.
- Dialect notes: validation is dialect-agnostic and aims for an ANSI/portable subset. Some vendor-specific features
  (e.g., certain MySQL or PostgreSQL extensions) may not be recognized. If you hit a false positive, you can disable
  validation per module with ksp arg validate-sql-syntax=false, or disable it per query with
  `@Query(checkSyntax = false)`.
- Most reliable with: SELECT, INSERT, UPDATE, DELETE statements. DDL or very advanced constructs may not be fully
  supported.

Example of a build error you might see if your query is malformed:

```
> Task :compileKotlin
Invalid SQL in function findAllBy: Encountered "FROMM" at line 1, column 15
```

Tip: keep it enabled to catch typos early; if you rely heavily on vendor-specific syntax not yet supported by the
parser, turn it off either globally or just for a specific method:

- Globally (module-wide):

```kotlin
ksp { arg("validate-sql-syntax", "false") }
```

- Per query:

```kotlin
@Repository
interface UserRepository {
    @Query("select * from users where id = :id", checkSyntax = false)
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<User?>
}
```

#### SQL schema validation (compile-time)

> [!NOTE]
> **Experimental Feature**: SQL schema validation is currently in early development and may have limitations.

- What it is: during code generation, sqlx4k can also validate your `@Query` SQL against a known database schema. It
  loads your migration files, builds an in-memory schema, and uses Apache Calcite to validate that tables, columns,
  and basic types referenced by the query exist and are compatible.
- What it checks:
    - Existence of referenced tables and columns.
    - Basic type compatibility for literals and simple expressions.
    - It does not execute queries or connect to a database.
- When it runs: at KSP processing time, right after syntax validation.
- Default: disabled. You must enable it explicitly per module.
- Requirements: point the processor to your migrations directory so it can reconstruct the schema. The loader supports
  a pragmatic subset of DDL: CREATE TABLE, ALTER TABLE ADD/DROP COLUMN, and DROP TABLE, processed in migration order.
- Dialect notes: validation is based on Calcite’s SQL semantics and a simplified schema model derived from your
  migrations. Some vendor-specific features and advanced DDL may not be fully supported.

Enable module-wide schema validation by adding KSP args in your build.gradle.kts:

```kotlin
ksp {
    arg("validate-sql-schema", "true")
    // Path to your migration .sql files (processed in ascending file version order)
    arg("schema-migrations-path", "./db/migrations")
}
```

You can also disable schema checks for a specific query:

```kotlin
@Repository
interface UserRepository {
    @Query("select * from users where id = :id", checkSchema = false)
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<User?>
}
```

### Database Migrations

Run any pending migrations against the database; and validate previously applied migrations against the current
migration source to detect accidental changes in previously applied migrations.

```kotlin
val res = db.migrate(
    path = "./db/migrations",
    table = "_sqlx4k_migrations",
    afterFileMigration = { m, d -> println("Migration of file: $m, took $d") }
).getOrThrow()
println("Migration completed. $res")
```

This process will create a table with name `_sqlx4k_migrations`. For more information, take a look at
the [examples](#examples).

### Listen/Notify (only for PostgreSQL)

```kotlin
db.listen("chan0") { notification: Postgres.Notification ->
    println(notification)
}

(1..10).forEach {
    db.notify("chan0", "Hello $it")
    delay(1000)
}
```

## Extensions

`sqlx4k` provides several extensions to enhance functionality:

### PostgreSQL Message Queue (PGMQ)

A Kotlin Multiplatform client for building reliable, asynchronous message queues using PostgreSQL and
the [PGMQ](https://github.com/pgmq/pgmq) extension.

**Features:**

- Full PGMQ operations support (create, drop, list queues)
- Send and receive messages with headers and delays
- Message acknowledgment (ack/nack) with visibility timeout
- Batch operations for high throughput
- High-level consumer API with automatic retry and exponential backoff
- PostgreSQL LISTEN/NOTIFY integration for real-time notifications
- Queue metrics and monitoring

**Installation:**

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres-pgmq:x.y.z")
```

**Quick Example:**

```kotlin
// Create PGMQ client
val pgmq = PgmqClient(
    pg = PgmqDbAdapterImpl(db),
    options = PgmqClient.Options(autoInstall = true)
)

// Create a queue and send messages
pgmq.create(PgmqClient.Queue(name = "my_queue")).getOrThrow()
pgmq.send("my_queue", """{"order": 123}""").getOrThrow()

// High-level consumer with automatic retry
val consumer = PgmqConsumer(
    pgmq = pgmq,
    options = PgmqConsumer.Options(queue = "my_queue"),
    onMessage = { message -> processMessage(message) }
)
```

For complete documentation, see [sqlx4k-postgres-pgmq/README.md](./sqlx4k-postgres-pgmq/README.md)

### SQLDelight

SQLDelight integration for type-safe SQL queries with sqlx4k.

**Repository:** https://github.com/smyrgeorge/sqlx4k-sqldelight

## Supported Targets

- jvm
- iosArm64
- iosSimulatorArm64
- androidNativeX64
- androidNativeArm64
- macosArm64
- macosX64
- linuxArm64
- linuxX64
- mingwX64
- wasmWasi (potential future candidate)

## Usage

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres:x.y.z")
// or for MySQL
implementation("io.github.smyrgeorge:sqlx4k-mysql:x.y.z")
// or for SQLite
implementation("io.github.smyrgeorge:sqlx4k-sqlite:x.y.z")
```

### Windows

If you are building your project on Windows, for target mingwX64, and you encounter the following error:

```text
lld-link: error: -exclude-symbols:___chkstk_ms is not allowed in .drectve
```

Please look at this issue: [#18](https://github.com/smyrgeorge/sqlx4k/issues/18)

## Compilation

You will need the `Rust` toolchain to build this project.
Check here: https://rustup.rs/

> [!NOTE]  
> By default, the project will build only for your system architecture-os (e.g. `macosArm64`, `linuxArm64`, etc.)

Also, make sure that you have installed all the necessary targets (only if you want to build for all targets):

```shell
rustup target add aarch64-apple-ios
rustup target add aarch64-apple-ios-sim
rustup target add x86_64-linux-android
rustup target add aarch64-linux-android
rustup target add aarch64-apple-darwin
rustup target add x86_64-apple-darwin
rustup target add aarch64-unknown-linux-gnu
rustup target add x86_64-unknown-linux-gnu
rustup target add x86_64-pc-windows-gnu
```

Then, run the build.

```shell
# will build only for the current target
./gradlew build
```

You can also build for specific targets.

```shell
./gradlew build -Ptargets=macosArm64,macosX64
```

To build for all available targets, run:

```shell
./gradlew build -Ptargets=all
```

## Publishing

```shell
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
```

## Run

First, you need to run start-up the postgres instance.

```shell
docker compose up -d
```

And then run the examples.

```shell
# For macosArm64
./examples/postgres/build/bin/macosArm64/releaseExecutable/postgres.kexe
./examples/mysql/build/bin/macosArm64/releaseExecutable/mysql.kexe
./examples/sqlite/build/bin/macosArm64/releaseExecutable/sqlite.kexe
# If you run in another platform consider running the correct tartge.
```

## Examples

Here are small, self‑contained snippets for the most common tasks.
For full runnable apps, see the modules under:

- PostgreSQL: [examples/postgres](./examples/postgres)
- MySQL: [examples/mysql](./examples/mysql)
- SQLite: [examples/sqlite](./examples/sqlite)

## Checking for memory leaks

### macOS (using 'leaks' tool)

Check for memory leaks with the `leaks` tool.
First sign you binary:

```shell
codesign -s - -v -f --entitlements =(echo -n '<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd"\>
<plist version="1.0">
    <dict>
        <key>com.apple.security.get-task-allow</key>
        <true/>
    </dict>
</plist>') ./bench/postgres-sqlx4k/build/bin/macosArm64/releaseExecutable/postgres-sqlx4k.kexe
```

Then run the tool:

```shell
leaks -atExit -- ./bench/postgres-sqlx4k/build/bin/macosArm64/releaseExecutable/postgres-sqlx4k.kexe
```

## Acknowledgements

sqlx4k stands on the shoulders of excellent open-source projects:

- Data access engines
    - Native targets (Kotlin/Native): sqlx (Rust)
        - https://github.com/launchbadge/sqlx
    - JVM targets:
        - PostgreSQL: r2dbc-postgresql
            - https://github.com/pgjdbc/r2dbc-postgresql
        - MySQL: r2dbc-mysql
            - https://github.com/asyncer-io/r2dbc-mysql
        - SQLite: rsqlite-jdbc
            - https://github.com/xerial/sqlite-jdbc

- Build-time tooling
    - JSqlParser — used by the code generator to parse @Query SQL at build time for syntax validation.
        - https://github.com/JSQLParser/JSqlParser
    - Apache Calcite — used by the code generator for compile-time SQL schema validation.
        - https://calcite.apache.org/

Huge thanks to the maintainers and contributors of these projects.

## License

MIT — see [LICENSE](./LICENSE).
