# sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.20-blue.svg?logo=kotlin)](http://kotlinlang.org)

![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Jvm&color=blue)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)
![](https://img.shields.io/static/v1?label=&message=iOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Android&color=blue)

A high-performance, non-blocking database driver for PostgreSQL, MySQL, and SQLite, written for Kotlin Multiplatform.

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
- [Prepared statements (named and positional parameters)](#prepared-statements)
- [Row mappers](#rowmappers)
- [Transactions and coroutine TransactionContext](#transactions) · [TransactionContext (coroutines)](#transactioncontext-coroutines)
- [Code generation: CRUD and @Repository implementations](#code-generation-crud-and-repository-implementations)
  - [SQL syntax validation (compile-time)](#sql-syntax-validation-compile-time)
  - [SQL schema validation (compile-time)](#sql-schema-validation-compile-time)
- [Database migrations](#database-migrations)
- [PostgreSQL LISTEN/NOTIFY](#listennotify-only-for-postgresql)
- [SQLDelight integration](#sqldelight)
- [Supported targets](#supported-targets)

### Next Steps (contributions are welcome)

- Create and publish sqlx4k-gradle-plugin.
- Pure Kotlin implementation for `ConnectionPool`.
- Validate queries at compile time (avoid runtime errors)
    - Syntax checking is already supported (using the `@Query` annotation). ✅
    - Validate queries by accessing the DB schema ✅
    - Validate query literal types (type check query parameters)
- Add support for SQLite JVM target.
- WASM support (?).
- Pure Kotlin implementation for PostgreSQL.
- Pure Kotlin implementation for MySQL.
- Pure Kotlin implementation for SQLite.

### Supported Databases

- ![MySQL](https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white)
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

#### Acquiring and using connections

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
    conn.release().getOrThrow() // Return to pool
}
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
    override fun map(row: ResultSet.Row): Sqlx4k {
        val id: ResultSet.Row.Column = row.get(0)
        val test: ResultSet.Row.Column = row.get(1)
        // Use built-in mapping methods to map the values to the corresponding type.
        return Sqlx4k(id = id.asInt(), test = test.asString())
    }
}

val res: List<Sqlx4k> = db.fetchAll("select * from sqlx4k limit 100;", Sqlx4kRowMapper).getOrThrow()
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
    // Optional: pick SQL dialect for CRUD generation from @Table classes.
    // Currently only "mysql" is special-cased; everything else falls back to a generic ANSI-like dialect.
    // This setting affects the shape of INSERT/UPDATE/DELETE that TableProcessor emits.
    // It does NOT affect @Query validation (see notes below).
    // arg("dialect", "mysql")

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

@Repository(mapper = Sqlx4kRowMapper::class)
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

Then in your code you can use it like:

```kotlin
// Insert a new record.
val record = Sqlx4k(id = 1, test = "test")
val res: Sqlx4k = Sqlx4kRepositoryImpl.insert(db, record).getOrThrow()
// Execute a generated query.
val res: List<Sqlx4k> = Sqlx4kRepositoryImpl.selectAll(db).getOrThrow()
```

For more details take a look at the [examples](./examples).

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
@Repository(mapper = UserMapper::class)
interface UserRepository {
    @Query("select * from users where id = :id", checkSyntax = false)
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<User?>
}
```

#### SQL schema validation (compile-time)

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
@Repository(mapper = UserMapper::class)
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

### SQLDelight

Check here: https://github.com/smyrgeorge/sqlx4k-sqldelight

## Supported Targets

- jvm (only PostgreSQL and MySQL are supported at the moment)
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

- Build-time tooling
    - JSqlParser — used by the code generator to parse @Query SQL at build time for syntax validation.
        - https://github.com/JSQLParser/JSqlParser
    - Apache Calcite — used by the code generator for compile-time SQL schema validation.
        - https://calcite.apache.org/

Huge thanks to the maintainers and contributors of these projects.

## License

MIT — see [LICENSE](./LICENSE).