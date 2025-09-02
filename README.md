# sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.2.10-blue.svg?logo=kotlin)](http://kotlinlang.org)

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

## Supported Databases

- `PostgreSQL`
- `MySQL`
- `SQLite`

## Supported Targets

- jvm (only PostgreSQL and MySQL are supported at the moment)
- iosArm64
- androidNativeX64
- androidNativeArm64
- macosArm64
- macosX64
- linuxArm64
- linuxX64
- mingwX64
- wasmWasi (potential future candidate)

## Features

- [Async I/O](#async-io)
- [Connection pool and settings](#connection-pool)
- [Prepared statements (named and positional parameters)](#prepared-statements)
- [Row mappers](#rowmappers)
- [Transactions and coroutine TransactionContext](#transactions) · [TransactionContext (coroutines)](#transactioncontext-coroutines)
- [Code generation: CRUD and @Repository implementations](#code-generation-crud-and-repository-implementations)
- [Database migrations](#database-migrations)
- [PostgreSQL LISTEN/NOTIFY](#listennotify-only-for-postgresql)
- [SQLDelight integration](#sqldelight)

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

## Next Steps (contributions are welcome)

- Enhance code-generation module.
- Add support for SQLite JVM target.

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
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val db = // create your Driver (e.g. PostgreSQL(...))

        TransactionContext.new(db) {
            // `this` is a TransactionContext and also a Transaction (delegation),
            // so you can call query methods directly:
            execute("insert into sqlx4k (id, test) values (66, 'test');").getOrThrow()

            // In deeper code, fetch the same context and keep using the same tx
            doBusinessLogic()
            doMoreBusinessLogic()
        }
}

suspend fun doBusinessLogic() {
    // Get the active transaction from the coroutine context
    val tx = TransactionContext.current()
    // Continue operating within the same database transaction
    tx.execute("update sqlx4k set test = 'updated' where id = 66;").getOrThrow()
}

// Or you can use the `withCurrent` method to get the transaction and execute the block in ongoid transaction.
suspend fun doMoreBusinessLogic(): Unit = TransactionContext.withCurrent {
    // Continue operating within the same database transaction
    execute("update sqlx4k set test = 'updated' where id = 66;").getOrThrow()
}
```

### Code-Generation, `CRUD` and `@Repository` Implementations

For this operation you will need to include the `KSP` plugin to your project.

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

// Then you need to configure the processor (will generate the necessary code files).
ksp {
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
}

dependencies {
    // Will generate code for macosArm64. Add more targets if you want.
    add("kspMacosArm64", implementation("io.github.smyrgeorge:sqlx4k-codegen:x.y.z"))
}
```

Then create your data class that will be mapped to a table:

```kotlin
// package io.github.smyrgeorge.sqlx4k.examples.postgres

@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true) // Will be included in the insert query.
    val id: Int,
    val test: String
)

@Repository(Sqlx4k::class, Sqlx4kRowMapper::class)
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun selectById(context: QueryExecutor, id: Int): Result<List<Sqlx4k>>

    @Query("SELECT * FROM sqlx4k")
    suspend fun selectAll(context: QueryExecutor): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    suspend fun countAll(context: QueryExecutor): Result<Long>
}
```

Then in your code you can use it like:

```kotlin
// Insert a new record.
val record = Sqlx4k(id = 1, test = "test")
val res: Sqlx4k = Sqlx4kRepositoryImpl.insert(db, record).getOrThrow()
// Execute a generated query.
val res: List<Sqlx4k> = Sqlx4kRepositoryImpl.selectAll(db).getOrThrow()
```

For more details take a look at the `postgres` example.

### Database Migrations

Run any pending migrations against the database; and validate previously applied migrations against the current
migration source to detect accidental changes in previously applied migrations.

```kotlin
db.migrate(path = "./db/migrations").getOrThrow()
// You can also ovveride the default migration table name.
// db.migrate(path = "./db/migrations", table = "_sqlx4k_migrations").getOrThrow()
println("Migrations completed.")
```

This process will create a table with name `_sqlx4k_migrations`.

For more information, take a look at the examples.

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

## Compilation

You will need the `Rust` toolchain to build this project.
Check here: https://rustup.rs/

> [!NOTE]  
> By default, the project will build only for your system architecture-os (e.g. `macosArm64`, `linuxArm64`, etc.)

Also, make sure that you have installed all the necessary targets (only if you want to build for all targets):

```shell
rustup target add aarch64-apple-ios
rustup target add x86_64-linux-android
rustup target add aarch64-linux-android
rustup target add aarch64-apple-darwin
rustup target add x86_64-apple-darwin
rustup target add aarch64-unknown-linux-gnu
rustup target add x86_64-unknown-linux-gnu
rustup target add x86_64-pc-windows-gnu
```

We also need to install `cross` (tool that helps with cross-compiling)

```shell
cargo install cross --git https://github.com/cross-rs/cross
```

Then, run the build.

```shell
# will build only for macosArm64 target
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

Then run the `main` method.

```shell
./sqlx4k-postgres-examples/build/bin/macosArm64/releaseExecutable/sqlx4k-postgres-examples.kexe
```

## Examples

See `Main.kt` file for more examples (examples modules).

## Checking for memory leaks

### macOS (using leaks tool)

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
