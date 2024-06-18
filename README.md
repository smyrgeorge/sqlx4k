# Sqlx4k

A small sql library written in kotlin for the native platform.

## Main goal

Provide a sql driver for the kotlin native platform.
Under the hood uses the `sqlx` library from the `rust` ecosystem.

The project is a very early stage, thus braking changes, bugs should be expected.

The driver currently only supports the `PostgreSQL` database.

## Supported databases

- [ ] PostgresSQL (in progress)
- [ ] MySql
- [ ] SQLite

## Kotlin/Native & Rust interoperability

Based on the
template: [https://github.com/avan1235/kotlin-native-rust-interop](https://github.com/avan1235/kotlin-native-rust-interop)

## Compilation

You will need the rust toolchain in order to lib.

```shell
./gradlew binaries
```

## Examples

See `Main.kt` file for more examples.

```kotlin
// Initialize the connection pool.
sqlx4k_of(
    host = "localhost",
    port = cValuesOf(5432),
    username = "test",
    password = "test",
    database = "test_db",
    max_connections = cValuesOf(10)
).checkExitCode()

// Make a simple query.
sqlx4k_query_fetch_all("select * from data_collector.sqlx4k;").use {
    println(it.toStr())
}
```

## Run

```shell
./build/bin/macosArm64/releaseExecutable/sqlx4k
```
