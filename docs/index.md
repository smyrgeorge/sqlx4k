# sqlx4k

A coroutine-first SQL toolkit with compile-time query validations for Kotlin Multiplatform. PostgreSQL, MySQL/MariaDB,
and SQLite are supported.

![sqlx4k](assets/banner.svg)

**sqlx4k** is not an ORM. Instead, it provides a comprehensive toolkit of primitives and utilities to communicate
directly with your database. The focus is on giving you control while catching errors early through compile-time query
validation, preventing runtime surprises before they happen (see
[SQL syntax and schema validation](codegen/validation.md)).

The library is designed to be extensible, with a growing ecosystem of tools and extensions like
[PGMQ](extensions/pgmq.md) (PostgreSQL Message Queue), [SQLDelight](extensions/sqldelight.md) integration, and more.

## Quick look

```kotlin
val db = PostgreSQL(
    url = "postgresql://localhost:15432/test",
    username = "postgres",
    password = "postgres",
)

db.execute("insert into users(id, name) values (1, 'Alice');").getOrThrow()

db.transaction {
    execute("update users set name = 'Bob' where id = 1;").getOrThrow()
    fetchAll("select * from users;").getOrThrow().forEach { println(it) }
}
```

## Features

<div class="grid cards" markdown>

- :material-database: **[Supported databases](databases/postgresql.md)**

    PostgreSQL, MySQL, MariaDB, SQLite and SQLCipher (encrypted SQLite).

- :material-language-kotlin: **[Coroutines everywhere](guide/index.md)**

    Every query API is a `suspend` function returning a `Result`, on non-blocking I/O on every platform.

- :material-cog: **[Gradle plugin](getting-started/gradle-plugin.md)**

    One `sqlx4k { }` block adds the driver, the extensions and the code generator.

- :material-code-braces: **[Code generation](codegen/index.md)**

    CRUD and `@Repository` implementations, row mappers, batch operations, optimistic locking.

- :material-check-decagram: **[Compile-time validation](codegen/validation.md)**

    `@Query` SQL is checked for syntax, columns, tables and statement kind during the build.

- :material-test-tube: **[In-memory repositories](codegen/in-memory-repositories.md)**

    Generated, thread-safe in-memory implementations of your repositories for unit tests.

- :material-swap-horizontal: **[Transactions](guide/transactions.md)**

    Block-scoped transactions, savepoints and transaction propagation through the coroutine context.

- :material-database-arrow-up: **[Migrations](guide/migrations.md)**

    Run pending migrations and validate the ones already applied.

</div>

### Supported databases

- ![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white)
- ![MySQL](https://img.shields.io/badge/MySQL-00758F?logo=mysql&logoColor=white)
- ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791?logo=postgresql&logoColor=white)
- ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white)
- ![SQLCipher](https://img.shields.io/badge/SQLCipher-003B57?logo=sqlite&logoColor=white) (encrypted SQLite,
  `sqlx4k-sqlite-cipher`)

sqlx4k runs on the JVM, Android, iOS, macOS, Linux and Windows: see [Supported targets](platforms.md).

## Articles

Short deep-dive posts covering Kotlin/Native, FFI, and Rust ↔ Kotlin interop used in sqlx4k:

- *Introduction to the Kotlin Native and FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-1/),
  [[Part 2]](https://smyrgeorge.github.io/posts/sqlx4k---introduction-to-the-kotlin-native-and-ffi-part-2/)
- *Interoperability between Kotlin and Rust, using FFI:*
  [[Part 1]](https://smyrgeorge.github.io/posts/sqlx4k---interoperability-between-kotlin-and-rust-using-ffi-part-1/),
  (Part 2 soon)

## License

MIT, see [LICENSE](https://github.com/smyrgeorge/sqlx4k/blob/main/LICENSE).
