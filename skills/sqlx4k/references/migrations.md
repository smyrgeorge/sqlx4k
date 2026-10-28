# Migrations

Docs: <https://smyrgeorge.github.io/sqlx4k/guide/migrations/>

```kotlin
val res: Migrator.Results = db.migrate(
    path = "./db/migrations",          // default
    table = "_sqlx4k_migrations",      // default; created when missing
    afterFileMigration = { m, d -> println("Applied ${m.name} in $d") },
).getOrThrow()
// res.total, res.applied, res.validated, res.executionTime
```

Run it at startup, before the application uses the database.

## Files

- Named `<version>_<description>.sql`: `1_create_users.sql`, `2_add_email.sql`. A file may hold several statements.
- Versions must be unique and consecutive (`1`, `2`, `3`, …): a gap or a duplicate fails the migration.
- Each file runs in one transaction (on MySQL/MariaDB, DDL commits implicitly and is not rolled back).
- **Never edit an applied file:** its checksum is validated on every run, and a change fails the migration
  (`SQLError.Code.Migrate`). Add a new file instead. An empty file fails too.

## Options

`schema` (the schema of the migrations table, in which the files run too; not on SQLite), `createSchema` (create it
when missing), `afterStatementExecution: suspend (Statement, Duration) -> Unit`,
`afterFileMigration: suspend (Migration, Duration) -> Unit`.

## Files from code or resources

```kotlin
// From code, e.g. on mobile, where there is no migrations directory:
db.migrate(
    files = listOf(
        MigrationFile(name = "1_create_users.sql", content = "create table users(id bigint primary key, name text);"),
        MigrationFile(name = "2_add_email.sql", content = "alter table users add column email text;"),
    )
).getOrThrow()

// From the classpath (JVM), also inside a JAR:
db.migrate(supplier = { readMigrationFilesFromResources("db/migrations") }).getOrThrow()
```

Imports: `io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile`, `io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator`,
`io.github.smyrgeorge.sqlx4k.impl.migrate.utils.readMigrationFilesFromResources`.

## SQLite and foreign keys

`PRAGMA foreign_keys` has no effect inside a transaction, so a migration cannot turn foreign keys off. Rebuilding a
referenced table (create new, copy, drop old, rename) cascades or fails on the drop: rebuild the referencing tables in
the same migration, or use `ALTER TABLE ... ADD / RENAME / DROP COLUMN` where possible.

## Compile-time schema validation

The same files can validate `@Query` SQL against the schema at build time (experimental): code generator options
`validate-sql-schema = "true"` and `schema-migrations-path = "./db/migrations"`.
