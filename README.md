# Sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)

A small sql library written in kotlin for the native platform.

## Main goal

Provide a sql driver for the kotlin native platform.
Under the hood uses the `sqlx` library from the `rust` ecosystem.
Maybe in the future we will provide a pure kotlin driver implementation.

The project is a very early stage, thus braking changes, bugs should be expected.

The driver currently only supports the `PostgreSQL` database.

## Todo

- [ ] PostgresSQL (in progress)
- [x] Try to "bridge" the 2 async worlds (kotlin-rust)
- [x] Use non-blocking io end to end, using the `suspendCoroutine` function
- [x] Transactions
- [ ] Transaction isolation level
- [ ] Better error handling (in progress)
- [x] Check for memory leaks
- [ ] Benchmark
- [ ] Publish to maven central
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
    maxConnections = 10
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

Also, we do make support transactions

```kotlin
val tx1: Transaction = pg.begin()
tx1.query("delete from sqlx4k;")
tx1.fetchAll("select * from sqlx4k;") {
    println(debug())
}
pg.fetchAll("select * from sqlx4k;") {
    println(debug())
}
tx1.commit()
pg.fetchAll("select * from sqlx4k;") {
    println(debug())
}
println(test)
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
