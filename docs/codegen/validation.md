# Validation

The code generator checks the SQL of every `@Query` method during the build, so a broken query fails the build instead
of failing at runtime:

- [SQL syntax validation](#sql-syntax-validation): the query parses.
- [SQL schema validation](#sql-schema-validation) (experimental, opt-in): the tables and columns exist in the schema
  built from your migrations.
- [Query validations](#query-validations): the query is consistent with its method and entity.

It also applies a few [optimizations](#optimizations) to the generated SQL.

## SQL syntax validation

- **What it is:** during code generation, sqlx4k parses the SQL string in each `@Query` method using `JSqlParser`. If
  the parser detects a syntax error, the build fails early with a clear error message pointing to the offending
  repository method.
- **What it checks:** only SQL syntax. It does not verify that tables/columns exist, parameter names match, or types
  are compatible.
- **When it runs:** at KSP processing time, before your code is compiled/run.
- **Dialect notes:** validation is dialect-agnostic and aims for an ANSI/portable subset. Some vendor-specific features
  (e.g., certain MySQL or PostgreSQL extensions) may not be recognized. If you hit a false positive, you can disable
  validation per module with the KSP arg `validate-sql-syntax=false`, or per query with `@Query(checkSyntax = false)`.
- **Most reliable with:** SELECT, INSERT, UPDATE, DELETE statements. DDL or very advanced constructs may not be fully
  supported.

Example of a build error you might see if your query is malformed:

```text
> Task :compileKotlin
Invalid SQL in function findAllBy: Encountered "FROMM" at line 1, column 15
```

Keep it enabled to catch typos early; if you rely heavily on vendor-specific syntax not yet supported by the parser,
turn it off either globally or just for a specific method:

=== "Module-wide"

    ```kotlin
    ksp { arg("validate-sql-syntax", "false") }
    ```

=== "Per query"

    ```kotlin
    @Repository
    interface UserRepository {
        @Query("select * from users where id = :id", checkSyntax = false)
        suspend fun findOneById(context: QueryExecutor, id: Int): Result<User?>
    }
    ```

## SQL schema validation

!!! warning "Experimental"

    SQL schema validation is currently in early development and may have limitations.

- **What it is:** during code generation, sqlx4k can also validate your `@Query` SQL against a known database schema.
  It loads your migration files, builds an in-memory schema, and uses Apache Calcite to validate that tables, columns,
  and basic types referenced by the query exist and are compatible.
- **What it checks:**
    - Existence of referenced tables and columns.
    - Basic type compatibility for literals and simple expressions.
    - It does not execute queries or connect to a database.
- **When it runs:** at KSP processing time, right after syntax validation.
- **Default:** disabled. You must enable it explicitly per module.
- **Requirements:** point the processor to your migrations directory so it can reconstruct the schema. The loader
  supports a pragmatic subset of DDL: CREATE TABLE, ALTER TABLE ADD/DROP COLUMN, and DROP TABLE, processed in
  migration order.
- **Dialect notes:** validation is based on Calcite's SQL semantics and a simplified schema model derived from your
  migrations. Some vendor-specific features and advanced DDL may not be fully supported.

Enable module-wide schema validation by adding KSP args in your `build.gradle.kts`:

```kotlin
ksp {
    arg("validate-sql-schema", "true")
    // Path to your migration .sql files (processed in ascending file version order)
    arg("schema-migrations-path", "./db/migrations")
}
```

You can also disable schema checks for a specific query:

```kotlin
@Repository
interface UserRepository {
    @Query("select * from users where id = :id", checkSchema = false)
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<User?>
}
```

## Query validations and optimizations

Beyond syntax and schema checks, the processor runs a set of extra compile-time checks and codegen rewrites over each
`@Query` (and the entity it maps to). Each is controlled by a global KSP option and enabled by default. They fall into
two groups: **validations** that fail the build when a `@Query` or entity looks wrong, and **optimizations** that
rewrite the generated SQL for efficiency without changing its result.

### Query validations

Compile-time safeguards that fail the build when a `@Query` (or the entity it maps to) is inconsistent: an unknown
column or table, the wrong statement kind, an unsupported key shape, … They never change the generated SQL.

| KSP option                   | Default | What it does                                                                                                                                                                                                                                                       |
|------------------------------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `reject-stacked-statements`  | `true`  | Fails the build if a single `@Query` contains more than one SQL statement (a stacked-query guard).                                                                                                                                                                 |
| `validate-sql-columns`       | `true`  | Fails the build if a `@Query` references a column that does not exist on the entity, across the `SELECT` list, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, and `UPDATE ... SET`. Applies to single-table queries only (joins/subselects/other tables are skipped). |
| `validate-sql-table`         | `true`  | Fails the build if a single-table `@Query` targets a table other than the entity's own (`@Table`). Skips subselect `FROM` clauses.                                                                                                                                 |
| `validate-count-projection`  | `true`  | Fails the build if a `count*` method does not select a single `count(...)` aggregate.                                                                                                                                                                              |
| `validate-statement-kind`    | `true`  | Fails the build if a `@Query`'s statement kind doesn't match its prefix: `find*`/`count*`/`exists*` must be a `SELECT`, `delete*` a `DELETE`, and `execute*` a write (`INSERT`/`UPDATE`/`DELETE`).                                                                 |
| `validate-projection`        | `true`  | Fails the build if a `find*` method bound by the generated row mapper selects only some columns (which would leave the mapper without a column at runtime). Requires `SELECT *` or every entity column. Skipped when a custom `@Repository(mapper = ...)` is used. |
| `validate-returning-columns` | `true`  | Fails the build if a `RETURNING` clause references a column that does not exist on the entity.                                                                                                                                                                     |
| `reject-grouping-in-scalar`  | `true`  | Fails the build if a `count*`, `findOne*`, or `exists*` method's `@Query` contains a `GROUP BY`/`HAVING` clause. A grouped query returns one row per group, but these methods read only the first row, so the result would be wrong.                               |
| `validate-single-id`         | `true`  | Fails the build if the repository entity declares more than one `@Id` property. Zero (a keyless entity) and one are both allowed; composite primary keys are not supported by the CRUD generator.                                                                  |

Disable any of them module-wide in your `build.gradle.kts`:

```kotlin
ksp {
    // arg("reject-stacked-statements", "false")
    // arg("validate-sql-columns", "false")
    // arg("validate-sql-table", "false")
    // arg("validate-count-projection", "false")
    // arg("validate-statement-kind", "false")
    // arg("validate-projection", "false")
    // arg("validate-returning-columns", "false")
    // arg("reject-grouping-in-scalar", "false")
    // arg("validate-single-id", "false")
}
```

### Optimizations

Codegen rewrites that make the generated SQL more efficient. They never fail the build; a query that doesn't fit the
rewrite is emitted unchanged.

| KSP option                | Default | What it does                                                                                                                                                                                                                                        |
|---------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `expand-select-star`      | `true`  | Rewrites a bare `SELECT *` over the entity's table into the entity's explicit columns in the generated statement (e.g. `select * from users` → `select id, name, email from users`). Leaves `count(*)`, joins, and explicit column lists untouched. |
| `findone-limit`           | `true`  | Appends `LIMIT 2` to `findOne*` queries so the driver fetches at most two rows: it still detects a multi-row result, but transfers far less on wide tables. Skips queries that already declare a `LIMIT`.                                           |
| `expand-returning-star`   | `true`  | Rewrites a bare `RETURNING *` on a write over the entity's table into the entity's explicit columns (mirrors `expand-select-star`). Leaves an explicit `RETURNING` list, or a write against a different table, untouched.                           |
| `drop-redundant-order-by` | `true`  | Strips a meaningless `ORDER BY` from `exists*`/`count*` queries: their result is a single scalar, so ordering only costs the database a sort. Leaves queries with no `ORDER BY` untouched.                                                          |

Disable any of them module-wide in your `build.gradle.kts`:

```kotlin
ksp {
    // arg("expand-select-star", "false")
    // arg("findone-limit", "false")
    // arg("expand-returning-star", "false")
    // arg("drop-redundant-order-by", "false")
}
```

!!! note

    Column/table validation and `SELECT *` expansion map columns back to properties using the same rules as the CRUD
    generator (snake_case, or an explicit `@Column(name = "...")`), so they always stay consistent with the generated
    SQL.

With the [Gradle plugin](../getting-started/gradle-plugin.md), pass these options through `args` instead of a `ksp { }`
block.
