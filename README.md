# sqlx4k

![Build](https://github.com/smyrgeorge/sqlx4k/actions/workflows/ci.yml/badge.svg)
![Maven Central](https://img.shields.io/maven-central/v/io.github.smyrgeorge/sqlx4k-postgres)
![GitHub License](https://img.shields.io/github/license/smyrgeorge/sqlx4k)
![GitHub commit activity](https://img.shields.io/github/commit-activity/w/smyrgeorge/sqlx4k)
![GitHub issues](https://img.shields.io/github/issues/smyrgeorge/sqlx4k)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](http://kotlinlang.org)

![](https://img.shields.io/static/v1?label=&message=Platforms&color=grey)
![](https://img.shields.io/static/v1?label=&message=Jvm&color=blue)
![](https://img.shields.io/static/v1?label=&message=Linux&color=blue)
![](https://img.shields.io/static/v1?label=&message=macOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Windows&color=blue)
![](https://img.shields.io/static/v1?label=&message=iOS&color=blue)
![](https://img.shields.io/static/v1?label=&message=Android&color=blue)

A coroutine-first SQL toolkit with compile-time query validations for Kotlin Multiplatform. PostgreSQL, MySQL/MariaDB,
and SQLite are supported.

<p align="center"><img src="docs/assets/banner.svg" alt="sqlx4k" width="100%"></p>

---

**sqlx4k** is not an ORM. Instead, it provides a comprehensive toolkit of primitives and utilities to communicate
directly with your database. The focus is on giving you control while catching errors early through compile-time query
validation—preventing runtime surprises before they happen
(see [Validation](https://smyrgeorge.github.io/sqlx4k/codegen/validation/) for more details).

The library is designed to be extensible, with a growing ecosystem of tools and extensions like PGMQ (PostgreSQL Message
Queue), SQLDelight integration, and more.

📖 [Documentation](https://smyrgeorge.github.io/sqlx4k/) · 📚 [API reference](https://smyrgeorge.github.io/sqlx4k/api/)

#### 📰 Articles

Short deep‑dive posts covering Kotlin/Native, FFI, and Rust ↔ Kotlin interop used in sqlx4k:

- *Introduction to the Kotlin Native and FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-1/),
  [[Part 2]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-2/)
- *Interoperability between Kotlin and Rust, using FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---interoperability-between-kotlin-and-rust-using-ffi-part-1/),
  (Part 2 soon)

## Usage

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres:x.y.z")
// or for MySQL
implementation("io.github.smyrgeorge:sqlx4k-mysql:x.y.z")
// or for SQLite
implementation("io.github.smyrgeorge:sqlx4k-sqlite:x.y.z")
// or for SQLite with encryption (SQLCipher)
implementation("io.github.smyrgeorge:sqlx4k-sqlite-cipher:x.y.z")
```

Or let the [Gradle plugin](https://smyrgeorge.github.io/sqlx4k/getting-started/gradle-plugin/) add the driver and set up
the code generation for you:

```kotlin
plugins {
    kotlin("multiplatform") // or kotlin("jvm")
    id("io.github.smyrgeorge.sqlx4k") version "x.y.z"
}

sqlx4k {
    driver = PostgreSQL // also: MySQL, MariaDB, SQLite, SQLiteCipher
    generatedCodePackage = "com.example.generated"
}
```

## Features

- [Supported databases](https://smyrgeorge.github.io/sqlx4k/databases/postgresql/): PostgreSQL, MySQL, MariaDB, SQLite
  and SQLCipher (encrypted SQLite)
- [Gradle plugin](https://smyrgeorge.github.io/sqlx4k/getting-started/gradle-plugin/)
- [Async I/O & coroutines](https://smyrgeorge.github.io/sqlx4k/guide/)
- [Connection pool and settings](https://smyrgeorge.github.io/sqlx4k/guide/connection-pool/)
- [Running queries](https://smyrgeorge.github.io/sqlx4k/guide/queries/): `execute`, `executeWithResult`, `fetchAll`,
  `fetch` (streaming), prepared statements
- [Row mappers and custom value converters](https://smyrgeorge.github.io/sqlx4k/guide/mapping/)
- [Transactions](https://smyrgeorge.github.io/sqlx4k/guide/transactions/), savepoints and `TransactionContext`
  (coroutines)
- [Database migrations](https://smyrgeorge.github.io/sqlx4k/guide/migrations/)
- [Code generation](https://smyrgeorge.github.io/sqlx4k/codegen/): CRUD and `@Repository` implementations
    - [Entities](https://smyrgeorge.github.io/sqlx4k/codegen/entities/): `@Column`, `@Transient`, optimistic locking
      with `@Version`, `@Converter`
    - [Batch operations](https://smyrgeorge.github.io/sqlx4k/codegen/batch-operations/)
    - [Context parameters](https://smyrgeorge.github.io/sqlx4k/codegen/context-parameters/)
    - [Repository hooks](https://smyrgeorge.github.io/sqlx4k/codegen/hooks/)
    - [Compile-time SQL validation and query optimizations](https://smyrgeorge.github.io/sqlx4k/codegen/validation/)
    - [In-memory repositories (for unit testing)](https://smyrgeorge.github.io/sqlx4k/codegen/in-memory-repositories/)
- Extensions
    - [PostgreSQL LISTEN/NOTIFY](https://smyrgeorge.github.io/sqlx4k/databases/postgresql/#listen-notify)
    - [PostgreSQL Message Queue (PGMQ)](https://smyrgeorge.github.io/sqlx4k/extensions/pgmq/)
    - [SQLDelight](https://smyrgeorge.github.io/sqlx4k/extensions/sqldelight/)
- [Supported targets](https://smyrgeorge.github.io/sqlx4k/platforms/): JVM, Android, iOS, macOS, Linux, Windows

## Examples

Full runnable applications, built with the Gradle plugin:

- PostgreSQL: [examples/postgres](./examples/postgres)
- MySQL: [examples/mysql](./examples/mysql)
- SQLite: [examples/sqlite](./examples/sqlite)

## Contributing

See [Building](https://smyrgeorge.github.io/sqlx4k/contributing/building/) for how to build the project (it needs the
Rust toolchain), and the
[Roadmap](https://smyrgeorge.github.io/sqlx4k/contributing/roadmap/) for what is coming next; contributions are welcome.

The documentation lives in [docs](./docs) and is published with [Zensical](https://zensical.org/) on every release.

## Acknowledgements

sqlx4k stands on the shoulders of excellent open-source projects, such as [sqlx](https://github.com/launchbadge/sqlx),
r2dbc, [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc), [SQLCipher](https://www.zetetic.net/sqlcipher/),
[JSqlParser](https://github.com/JSQLParser/JSqlParser) and [Apache Calcite](https://calcite.apache.org/): see the
[full list](https://smyrgeorge.github.io/sqlx4k/acknowledgements/).

## License

MIT — see [LICENSE](./LICENSE).
