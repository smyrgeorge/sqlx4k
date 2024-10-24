# sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.21-blue.svg?logo=kotlin)](http://kotlinlang.org)

![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)
![](https://img.shields.io/static/v1?label=&message=iOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Android&color=blue)

A high-performance, non-blocking database driver for PostgreSQL, MySQL, and SQLite, written for Kotlin Native.
Looking to build efficient, cross-platform applications with Kotlin Native.

> [!IMPORTANT]  
> The project is in a very early stage; thus, breaking changes should be expected.

📖 [Documentation](https://smyrgeorge.github.io/sqlx4k/)

🏠 [Homepage](https://smyrgeorge.github.io/) (under construction)

## Databases

Currently, the driver supports:

- `PostgreSQL`
- `MySQL`
- `SQLite`

## Usage

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres:x.y.z")
// or for MySQL
implementation("io.github.smyrgeorge:sqlx4k-mysql:x.y.z")
// or for SQLite
implementation("io.github.smyrgeorge:sqlx4k-sqlite:x.y.z")
```

## Supported targets

We support the following targets:

- iosArm64
- androidNativeX64
- androidNativeArm64
- macosArm64
- macosX64
- linuxArm64
- linuxX64
- mingwX64
- wasmJs (potential future candidate)
- jvm (potential future candidate)

## Features

### Async-io

The driver fully supports non-blocking io.

### Connection pool

You can set the `maxConnections` from the driver constructor:

```kotlin
val db = PostgreSQL(
    host = "localhost",
    port = 15432,
    username = "postgres",
    password = "postgres",
    database = "test",
    maxConnections = 10 // set the max-pool-size here
)

val db = MySQL(
    host = "localhost",
    port = 13306,
    username = "mysql",
    password = "mysql",
    database = "test",
    maxConnections = 10
)

val db = SQLite(
    database = "test.db",
    maxConnections = 10
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

### Transactions

```kotlin
val tx1: Transaction = db.begin().getOrThrow()
tx1.execute("delete from sqlx4k;").getOrThrow()
val res: ResultSet = tx1.fetchAll("select * from sqlx4k;").getOrThrow().forEach {
    println(debug())
}
tx1.commit().getOrThrow()
```

### Auto generate basic `insert/update/delete` queries

For this operation you will need to include the `KSP` plugin to your project.

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

// Then you need to configure the processor (will generate the necessary code files).
ksp {
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
    arg("output-filename", "GeneratedQueries")
}

dependencies {
    ksp(implementation("io.github.smyrgeorge:sqlx4k-codegen:x.y.z")) // Will generate code for all available targets.
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
```

We also need to create the function definitions for the generated code:

```kotlin
// Filename: GeneratedQueries (same as `output-filename`).
// Also the package should be the same as `output-package`.
package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.Statement

// We only need to declare the functions,
// the actual code will be auto-generated. 
expect fun Sqlx4k.insert(): Statement
expect fun Sqlx4k.update(): Statement
expect fun Sqlx4k.delete(): Statement
```

Then in your code you can use it like:

```kotlin
val insert: Statement = Sqlx4k(id = 66, test = "test").insert()
val affected = db.execute(insert).getOrThrow()
println("AFFECTED: $affected")
```

For more details take a look at the `postgres` example.

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

## Todo

- [x] PostgreSQL
- [x] MySQL
- [x] SQLite
- [x] Transactions
- [x] Listen/Notify Postgres
- [x] INSERT/UPDATE/DELETE APIs (with code generation)
- [x] Value encoders/decoders for basic data-types (in progress)
- [ ] Transaction isolation level
- [ ] Performance testing
- [ ] Testing

## Compilation

You will need the `Rust` toolchain to build this project.
Check here: https://rustup.rs/

> [!NOTE]  
> By default the project will build only for your system architecture-os (e.g. `macosArm64`, `linuxArm64`, etc.)

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

To build for all available target run:

```shell
./gradlew build -Ptargets=all
```

## Publishing

```shell
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
```

## Run

First you need to run start-up the postgres instance.

```shell
docker compose up -d
```

Then run the `main` method.

```shell
./sqlx4k-postgres-examples/build/bin/macosArm64/releaseExecutable/sqlx4k-postgres-examples.kexe
```

## Examples

See `Main.kt` file for more examples (examples modules).

```kotlin
// Initialize the connection pool.
val db = PostgreSQL(
    host = "localhost",
    port = 15432,
    username = "postgres",
    password = "postgres",
    database = "test",
    maxConnections = 10
)

db.execute("drop table if exists sqlx4k;").getOrThrow()

// Make a simple query.
data class Test(val id: Int)

// You can also use RowMappers(s) to map your objects.
object TestRowMapper : RowMapper<Test> {
    override fun map(rs: ResultSet, row: ResultSet.Row): Test {
        val id: ResultSet.Row.Column = row.get("id")
        return Test(id = id.asInt())
    }
}

val res: List<Test> = db.fetchAll("select * from sqlx4k;", TestRowMapper).getOrThrow()
println(test)
```

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
</plist>') ./examples/postgres/build/bin/macosArm64/releaseExecutable/postgres.kexe
```

Then run the tool:

```shell
leaks -atExit -- ./examples/postgres/build/bin/macosArm64/releaseExecutable/postgres.kexe
```

Sample output:

```text
Process:         postgres.kexe [54426]
Path:            /Users/USER/*/postgres.kexe
Load Address:    0x1027ec000
Identifier:      postgres.kexe
Version:         0
Code Type:       ARM64
Platform:        macOS
Parent Process:  leaks [54424]

Date/Time:       2024-10-14 19:17:58.968 +0200
Launch Time:     2024-10-14 19:17:21.968 +0200
OS Version:      macOS 15.0 (24A335)
Report Version:  7
Analysis Tool:   /Applications/Xcode.app/Contents/Developer/usr/bin/leaks
Analysis Tool Version:  Xcode 16.0 (16A242d)

Physical footprint:         37.1M
Physical footprint (peak):  38.5M
Idle exit:                  untracked
----

leaks Report Version: 4.0, multi-line stacks
Process 54426: 1847 nodes malloced for 656 KB
Process 54426: 0 leaks for 0 total leaked bytes.
```

## References

- https://kotlinlang.org/docs/multiplatform.html
- https://kotlinlang.org/docs/native-c-interop.html
- https://github.com/launchbadge/sqlx
- https://github.com/avan1235/kotlin-native-rust-interop
- https://play.rust-lang.org/?version=stable&mode=debug&edition=2018&gist=d0e44ce1f765ce89523ef89ccd864e54
- https://stackoverflow.com/questions/57616229/returning-array-from-rust-to-ffi
- https://stackoverflow.com/questions/76706784/why-stdmemforget-cannot-be-used-for-creating-static-references
- https://stackoverflow.com/questions/66412090/proper-way-of-dealing-with-blocking-code-using-kotling-coroutines
- https://github.com/square/retrofit/blob/fbf1225e28e2094bec35f587b8933748b705d167/retrofit/src/main/java/retrofit2/KotlinExtensions.kt#L31
- https://www.droidcon.com/2024/04/18/publishing-kotlin-multiplatform-libraries-with-sonatype-central/
