# Sqlx4k

![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)

A small sql library written in kotlin for the native platform.

## Main goal

Provide a sql driver for the kotlin native platform.
Under the hood uses the `sqlx` library from the `rust` ecosystem.

The project is a very early stage, thus braking changes, bugs should be expected.

The driver currently only supports the `PostgreSQL` database.

## Todo

- [ ] PostgresSQL (in progress)
- [x] Transactions
- [ ] Better error handling (in progress)
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
sqlx4k_of(
    host = "localhost",
    port = cValuesOf(15432),
    username = "postgres",
    password = "postgres",
    database = "test",
    max_connections = cValuesOf(10)
).checkExitCode()

// Make a simple query.
sqlx4k_fetch_all("select * from sqlx4k;").use {
    println(it.toStr())
}
```

Also, we do make support transactions

```kotlin
val tx1 = sqlx4k_tx_begin()
sqlx4k_tx_query(tx1, "delete from sqlx4k;").checkExitCode()
sqlx4k_tx_fetch_all(tx1, "select * from sqlx4k;").use {
    println(it.toStr())
}
sqlx4k_fetch_all("select * from sqlx4k;").use {
    println(it.toStr())
}
sqlx4k_tx_commit(tx1)
sqlx4k_fetch_all("select * from sqlx4k;").use {
    println(it.toStr())
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

## References
- https://kotlinlang.org/docs/multiplatform.html
- https://kotlinlang.org/docs/native-c-interop.html
- https://github.com/launchbadge/sqlx
- https://github.com/avan1235/kotlin-native-rust-interop
