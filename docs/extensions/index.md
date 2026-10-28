# Extensions

sqlx4k is designed to be extended: a growing set of tools builds on the drivers and the code generator.

- [PostgreSQL LISTEN/NOTIFY](../databases/postgresql.md#listen-notify): subscribe to PostgreSQL notifications, built
  into `sqlx4k-postgres`.
- [PostgreSQL Message Queue (PGMQ)](pgmq.md): a client for the [PGMQ](https://github.com/pgmq/pgmq) PostgreSQL
  extension, to build asynchronous workers on top of PostgreSQL (`sqlx4k-postgres-pgmq`).
- [SQLDelight](sqldelight.md): run the type-safe code [SQLDelight](https://github.com/sqldelight/sqldelight) generates
  from your `.sq` files on the sqlx4k drivers.
- [Arrow](../codegen/repositories.md#repository-interfaces): repository interfaces for [Arrow](https://arrow-kt.io/)
  (`sqlx4k-arrow`).

With the [Gradle plugin](../getting-started/gradle-plugin.md), add `sqlx4k-postgres-pgmq` and `sqlx4k-arrow` with
`extensions(Pgmq, Arrow)`.
