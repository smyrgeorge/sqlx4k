# Sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.0-blue.svg?logo=kotlin)](http://kotlinlang.org)

A small non-blocking database driver written in Kotlin for the Native platform.
Under the hood, it uses the sqlx library from the Rust ecosystem.
_In the future, we may provide a pure Kotlin driver implementation._

The project is in a very early stage; thus, breaking changes and bugs should be expected.

Currently, the driver only supports the `PostgreSQL` database.

## Features

### Async-io

The drivers fully supports non-blocking io.

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
    "MAPPED_VALUE"
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
- [ ] Publish to maven central
- [ ] Better error handling (in progress)
- [x] Check for memory leaks
- [ ] Testing
- [ ] Documentation
- [ ] Benchmark
- [ ] MySql
- [ ] SQLite

## Compilation

You will need the rust toolchain to build this project.
Check here: https://rustup.rs/

```shell
./gradlew binaries
```

## Examples

See `Main.kt` file for more examples.

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

## Run

First you need to run start-up the postgres instance.

```shell
docker compose up -d
```

Then run the `main` method.

```shell
./build/bin/macosArm64/releaseExecutable/sqlx4k
```

## Checking for memory leaks

### MacOs

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
</plist>') ./build/bin/macosArm64/releaseExecutable/sqlx4k
```

Then run the tool:

```shell
leaks -atExit -- ./build/bin/macosArm64/releaseExecutable/sqlx4k
```

Sample output:

```text
Process:         sqlx4k [36668]
Path:            /Users/USER/*/sqlx4k
Load Address:    0x10011c000
Identifier:      sqlx4k
Version:         0
Code Type:       ARM64
Platform:        macOS
Parent Process:  leaks [36667]

Date/Time:       2024-06-24 03:40:42.054 +0200
Launch Time:     2024-06-24 03:39:33.576 +0200
OS Version:      macOS 14.5 (23F79)
Report Version:  7
Analysis Tool:   /Applications/Xcode.app/Contents/Developer/usr/bin/leaks
Analysis Tool Version:  Xcode 15.4 (15F31d)

Physical footprint:         18.5M
Physical footprint (peak):  19.8M
Idle exit:                  untracked
----

leaks Report Version: 4.0, multi-line stacks
Process 36668: 1617 nodes malloced for 718 KB
Process 36668: 0 leaks for 0 total leaked bytes.
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
