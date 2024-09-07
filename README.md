# Sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.20-blue.svg?logo=kotlin)](http://kotlinlang.org)

A small non-blocking database driver written in Kotlin for the Native platform.
Under the hood, it uses the sqlx library from the Rust ecosystem.
_In the future, we may provide a pure Kotlin driver implementation._

The project is in a very early stage; thus, breaking changes and bugs should be expected.

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
- mingwX64 (soon)
- wasmWasi (potential future support)

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

### Named parameters

IMPORTANT: this feature is in a very early stage, thus use it with caution.
The code does not check for SQL injections.

```kotlin
db.fetchAll("select * from sqlx4k where id = :id;", mapOf("id" to "66")) {
    val id: ResultSet.Row.Column = get("id")
    Test(id = id.value.toInt())
}
```

You can also pass your own parameter mapper (in case that you want to use non built in types)

```kotlin
db.execute("drop table if exists sqlx4k where id = :id;", mapOf("id" to 66)) { v: Any? ->
    //  Map the value here.
    "$v" // mapped to 66 (no change)
}.getOrThrow()
```

### Transactions

```kotlin
val tx1: Transaction = db.begin().getOrThrow()
tx1.execute("delete from sqlx4k;").getOrThrow()
tx1.fetchAll("select * from sqlx4k;") {
    println(debug())
}
db.fetchAll("select * from sqlx4k;") {
    println(debug())
}
tx1.commit().getOrThrow()
```

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

## Todo

- [x] PostgreSQL
- [x] MySQL
- [x] SQLite
- [x] Transactions
- [x] Listen/Notify Postgres.
- [x] Named parameters (needs enhancements)
- [ ] Reduce duplicate code (in progress)
- [ ] SQLDelight (in progress)
- [ ] Transaction isolation level
- [ ] Testing
- [ ] Documentation (in progress)

## Compilation

You will need the rust toolchain to build this project.
Check here: https://rustup.rs/

Also, make sure that you have installed all the necessary targets:

```text
rustup target add aarch64-apple-ios
rustup target add aarch64-apple-darwin
rustup target add x86_64-apple-darwin
rustup target add x86_64-linux-android
rustup target add aarch64-linux-android
rustup target add aarch64-unknown-linux-gnu
rustup target add x86_64-unknown-linux-gnu
rustup target add x86_64-pc-windows-gnu
```

Then, run the build.

```shell
# will build only for macosArm64 target
./gradlew build
```

You can also build for specific targets.

```shell
./gradlew build -Ptargets=macosArm64,macosArm64
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

db.execute("drop table if exists sqlx4k;")

// Make a simple query.
data class Test(val id: Int)
db.fetchAll("select * from sqlx4k;") {
    val id: ResultSet.Row.Column = get("id")
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
</plist>') ./sqlx4k-postgres-examples/build/bin/macosArm64/releaseExecutable/sqlx4k-postgres-examples.kexe
```

Then run the tool:

```shell
leaks -atExit -- ./sqlx4k-postgres-examples/build/bin/macosArm64/releaseExecutable/sqlx4k-postgres-examples.kexe
```

Sample output:

```text
Process:         sqlx4k-postgres-examples.kexe [32353]
Path:            /Users/USER/*/sqlx4k-postgres-examples.kexe
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
