# Migrations

Run any pending migrations against the database, and validate previously applied migrations against the current
migration source to detect accidental changes in previously applied migrations.

```kotlin
val res = db.migrate(
    path = "./db/migrations",
    table = "_sqlx4k_migrations",
    afterFileMigration = { m, d -> println("Migration of file: $m, took $d") }
).getOrThrow()
println("Migration completed. $res")
```

This process will create a table with name `_sqlx4k_migrations`. For more information, take a look at the
[examples](../getting-started/examples.md).

The same migration files can be used to validate your `@Query` SQL against the schema at compile time: see
[SQL schema validation](../codegen/validation.md#sql-schema-validation).

!!! note "SQLite and foreign keys"

    Each migration file runs in a single transaction. On SQLite, where every target enforces foreign keys,
    `PRAGMA foreign_keys` has no effect inside a transaction, so a migration cannot turn them off: rebuilding a table
    that other tables reference (create a new table, copy the rows, drop the old one, rename the new one, as SQLite's
    documentation describes) deletes the referencing rows (`ON DELETE CASCADE`) or fails (`NO ACTION` / `RESTRICT`)
    when the old table is dropped. Drop or rebuild the referencing tables in the same migration, or alter the table in
    place (`ALTER TABLE ... ADD / RENAME / DROP COLUMN`) where SQLite allows it.
