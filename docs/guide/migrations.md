# Migrations

Run any pending migrations against the database, and validate previously applied migrations against the current
migration source to detect accidental changes in previously applied migrations.

```kotlin
val res: Migrator.Results = db.migrate(
    path = "./db/migrations",
    table = "_sqlx4k_migrations",
    afterFileMigration = { m, d -> println("Migration of file: $m, took $d") }
).getOrThrow()
println("Migration completed. $res")
```

The applied migrations are recorded in a table, `_sqlx4k_migrations` by default, which is created when missing. The
result reports the `total` number of files, how many were `applied` and `validated`, and the `executionTime`. For more
information, take a look at the [examples](../getting-started/examples.md).

The same migration files can be used to validate your `@Query` SQL against the schema at compile time: see
[SQL schema validation](../codegen/validation.md#sql-schema-validation).

## Migration files

A migration is a `.sql` file named `<version>_<description>.sql`, e.g. `1_create_users.sql`, `2_add_email.sql`. A file
can hold several statements.

- The files run in the order of their versions, which must be consecutive (`1`, `2`, `3`, …) and unique.
- Each file runs in a single transaction: if one of its statements fails, the whole file is rolled back. On MySQL
  and MariaDB, DDL statements (`CREATE`, `ALTER`, `DROP`, …) commit implicitly and cannot be rolled back.
- A file already applied is not run again; its checksum is compared with the one recorded when it was applied, and
  the migration fails if the file was changed since. Add a new file to change the schema further.

A failure (a gap or a duplicate in the versions, a changed or empty file, a failed statement) fails the migration
with an `SQLError` whose code is `Migrate` or `Database`.

## Options

| Parameter                 | Default                | What it does                                                                                                                         |
|---------------------------|------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `path`                    | `"./db/migrations"`    | The directory of the migration files.                                                                                                |
| `table`                   | `"_sqlx4k_migrations"` | The table that records the applied migrations.                                                                                       |
| `schema`                  | the default schema     | The schema of the migrations table, in which the files run too (PostgreSQL's `search_path`, MySQL's `USE`). Not supported on SQLite. |
| `createSchema`            | `false`                | Creates the `schema` when it does not exist.                                                                                         |
| `afterStatementExecution` | no-op                  | Called after each statement, with the `Statement` and the time it took.                                                              |
| `afterFileMigration`      | no-op                  | Called after each file, with the recorded `Migration` and the time it took.                                                          |

## Migration files from code

Instead of a directory, `migrate` also takes the migration files themselves, as a list of `MigrationFile` (or a
function that supplies it), e.g. when there is no file system to read them from, as on mobile:

```kotlin
val files = listOf(
    MigrationFile(name = "1_create_users.sql", content = "create table users(id bigint primary key, name text);"),
    MigrationFile(name = "2_add_email.sql", content = "alter table users add column email text;"),
)

db.migrate(files = files).getOrThrow()
```

On the JVM, `readMigrationFilesFromResources` reads them from the classpath, also from inside a JAR:

```kotlin
db.migrate(supplier = { readMigrationFilesFromResources("db/migrations") }).getOrThrow()
```

!!! note "SQLite and foreign keys"

    Each migration file runs in a single transaction. On SQLite, where every target enforces foreign keys,
    `PRAGMA foreign_keys` has no effect inside a transaction, so a migration cannot turn them off: rebuilding a table
    that other tables reference (create a new table, copy the rows, drop the old one, rename the new one, as SQLite's
    documentation describes) deletes the referencing rows (`ON DELETE CASCADE`) or fails (`NO ACTION` / `RESTRICT`)
    when the old table is dropped. Drop or rebuild the referencing tables in the same migration, or alter the table in
    place (`ALTER TABLE ... ADD / RENAME / DROP COLUMN`) where SQLite allows it.
