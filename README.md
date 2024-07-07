# Sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.0-blue.svg?logo=kotlin)](http://kotlinlang.org)

A small non-blocking database driver written in Kotlin for the Native platform.
Under the hood, it uses the sqlx library from the Rust ecosystem.
_In the future, we may provide a pure Kotlin driver implementation._

The project is in a very early stage; thus, breaking changes and bugs should be expected.

Currently, the driver only supports the `PostgreSQL` database.

## Usage

You can found the latest published version [here](https://central.sonatype.com/artifact/io.github.smyrgeorge/sqlx4k).

```kotlin
implementation("io.github.smyrgeorge:sqlx4k:x.y.z")
```

## Why not a pure kotlin implementation?

First of all, I wanted to experiment with the Kotlin FFI.
Additionally, I really like the Rust programming language,
so I also wanted to experiment with the Rust FFI.

I think it's a quite nice solution (at least for now).
The Kotlin Native ecosystem is in a very early stage,
so I believe it’s a great opportunity to make use of other libraries using the FFI layer.
It makes it easier (at least for now) to create some wrappers
around well-tested libraries to provide the necessary functionality to the ecosystem.

## Features

### Async-io

The driver fully supports non-blocking io.
Bridges the kotlin-async (coroutines) with the rust-async (tokio) without blocking. 

All the "magic" happens thanks to the build in kotlin function `suspendCoroutine`, take a
look [here](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/suspend-coroutine.html).

### Connection pool

You can set the `maxConnections` from the driver constructor:

```kotlin
val pg = Postgres(
    host = "localhost",
    port = 15432,
    username = "postgres",
    password = "postgres",
    database = "test",
    maxConnections = 10 // set the max-pool-size here
)
```

### Named parameters

```kotlin
pg.query("drop table if exists :table;", mapOf("table" to "sqlx4k")).getOrThrow()

pg.fetchAll("select * from :table;", mapOf("table" to "sqlx4k")) {
    val id: Sqlx4k.Row.Column = get("id")
    Test(id = id.value.toInt())
}
```

You can also pass your own parameter mapper (in case that you want to use non built in types)

```kotlin
pg.query("drop table if exists :table;", mapOf("table" to "sqlx4k")) { v: Any? ->
    //  Map the value here.
    "sqlx4k"
}.getOrThrow()
```

### Transactions

```kotlin
val tx1: Transaction = pg.begin().getOrThrow()
tx1.query("delete from sqlx4k;").getOrThrow()
tx1.fetchAll("select * from sqlx4k;") {
    println(debug())
}
pg.fetchAll("select * from sqlx4k;") {
    println(debug())
}
tx1.commit().getOrThrow()
```

## Todo

- [x] PostgresSQL
- [x] Try to "bridge" the 2 async worlds (kotlin-rust)
- [x] Use non-blocking io end to end, using the `suspendCoroutine` function
- [x] Transactions
- [x] Named parameters
- [ ] Transaction isolation level
- [x] Publish to maven central
- [x] Better error handling
- [x] Check for memory leaks
- [ ] Testing
- [ ] Documentation
- [ ] Benchmark
- [ ] MySql
- [ ] SQLite
- [ ] Windows support

## Compilation

You will need the rust toolchain to build this project.
Check here: https://rustup.rs/

Also, make sure that you have installed all the necessary targets:

```text
rustup target add aarch64-apple-darwin
rustup target add x86_64-apple-darwin
rustup target add aarch64-unknown-linux-gnu
rustup target add x86_64-unknown-linux-gnu
```

Then, run the build.

```shell
./gradlew build
```

## Run

First you need to run start-up the postgres instance.

```shell
docker compose up -d
```

Then run the `main` method.

```shell
./examples/build/bin/macosArm64/releaseExecutable/examples.kexe
```

## Examples

See `Main.kt` file for more examples (examples module).

```kotlin
// Initialize the connection pool.
val pg = Postgres(
    host = "localhost",
    port = 15432,
    username = "postgres",
    password = "postgres",
    database = "test",
    maxConnections = 10 // set the max-pool-size here
)

pg.query("drop table if exists sqlx4k;")

// Make a simple query.
data class Test(val id: Int)
pg.fetchAll("select * from sqlx4k;") {
    val id: Sqlx4k.Row.Column = get("id")
    val test = Test(id = id.value.toInt())
    println(test)
    test
}
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
</plist>') ./examples/build/bin/macosArm64/releaseExecutable/examples.kexe
```

Then run the tool:

```shell
leaks -atExit -- ./examples/build/bin/macosArm64/releaseExecutable/examples.kexe
```

Sample output:

```text
Process:         examples.kexe [32353]
Path:            /Users/USER/*/examples.kexe
Load Address:    0x102904000
Identifier:      examples.kexe
Version:         0
Code Type:       ARM64
Platform:        macOS
Parent Process:  leaks [32351]

Date/Time:       2024-07-05 16:14:03.515 +0200
Launch Time:     2024-07-05 16:13:45.848 +0200
OS Version:      macOS 14.5 (23F79)
Report Version:  7
Analysis Tool:   /Applications/Xcode.app/Contents/Developer/usr/bin/leaks
Analysis Tool Version:  Xcode 15.4 (15F31d)

Physical footprint:         213.8M
Physical footprint (peak):  213.8M
Idle exit:                  untracked
----

leaks Report Version: 4.0, multi-line stacks
Process 32353: 125349 nodes malloced for 8520 KB
Process 32353: 0 leaks for 0 total leaked bytes.
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
