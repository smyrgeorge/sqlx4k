# Code generation

sqlx4k ships a KSP code generator (`sqlx4k-codegen`) that writes, at build time:

- the CRUD operations (`insert`, `update`, `delete`, `save`, [batch operations](batch-operations.md)) of every
  `@Table` entity,
- the implementation of every `@Repository` interface, including its `@Query` methods,
- a [row mapper](#auto-generated-rowmapper) for every entity,

and [validates](validation.md) the SQL of every `@Query` method while doing so.

## Setup

!!! tip

    The [Gradle plugin](../getting-started/gradle-plugin.md) does the whole setup below for you from a single
    `sqlx4k { }` block. The manual setup is shown here for reference, and for the code-generator options.

### Manual setup

Include the `KSP` plugin in your project, and configure the processor:

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

// Then you need to configure the processor (it will generate the necessary code files).
ksp {
    // Optional: pick the SQL dialect for CRUD generation from @Table classes.
    // Supported dialects:
    // arg("dialect", "mysql")
    // arg("dialect", "mariadb")
    // arg("dialect", "postgresql")
    // arg("dialect", "sqlite")

    // Required: where to place the generated sources.
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")

    // Compile-time SQL syntax checking for @Query methods (default = true).
    // Set to "false" to turn it off if you use vendor-specific syntax not understood by the parser.
    // arg("validate-sql-syntax", "false")
}

dependencies {
    // Will generate code for macosArm64. Add more targets if you want.
    add("kspMacosArm64", implementation("io.github.smyrgeorge:sqlx4k-codegen:x.y.z"))
}
```

The validation and optimization options are listed in [Validation](validation.md).

## Entities and repositories

Create a data class that will be mapped to a table, and a repository for it:

```kotlin
@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true) // Will be included in the insert query.
    val id: Int,
    val test: String
)

@Repository
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    // The processor will validate the SQL syntax in the @Query methods.
    // If you want to disable this validation, you can set the "validate-sql-syntax" arg to "false".
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>

    @Query("SELECT * FROM sqlx4k")
    suspend fun findAll(context: QueryExecutor): Result<List<Sqlx4k>>

    @Query("SELECT count(*) FROM sqlx4k")
    suspend fun countAll(context: QueryExecutor): Result<Long>

    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun existsById(context: QueryExecutor, id: Int): Result<Boolean>
}
```

Then in your code you can use it like:

```kotlin
// Insert a new record.
val record = Sqlx4k(id = 1, test = "test")
val res: Sqlx4k = Sqlx4kRepositoryImpl.insert(db, record).getOrThrow()
// Execute a generated query.
val res: List<Sqlx4k> = Sqlx4kRepositoryImpl.findAll(db).getOrThrow()
```

Because the interface extends `CrudRepository<T>`, the generator also adds the CRUD helper methods automatically:
`insert`, `update`, `delete`, and `save`. To customize how properties map to columns, see [Entities](entities.md).

### Query methods

A `@Query` method's name prefix determines its shape and expected return type:

| Prefix                                | Return type          | Notes                                       |
|---------------------------------------|----------------------|---------------------------------------------|
| `findAll` / `findAllBy…`              | `Result<List<T>>`    |                                             |
| `findOneBy…`                          | `Result<T?>`         |                                             |
| `countAll` / `countBy…`               | `Result<Long>`       |                                             |
| `existsBy…`                           | `Result<Boolean>`    |                                             |
| `deleteAll` / `deleteBy…`, `execute…` | `Result<Long>`       | The number of affected rows.                |
| `streamAll` / `streamAllBy…`          | `Flow<T>`            | Declared as non-`suspend` functions.        |

`stream*` methods stream the rows instead of loading them all: the query runs when the flow is collected, and a failure
fails the collection. They fetch rows in chunks of `@Query(fetchSize = …)` rows (the driver's default when unset).

For more details, take a look at the [examples](../getting-started/examples.md).

## Auto-generated RowMapper

When you annotate a class with `@Table`, the code generator automatically creates a `RowMapper` implementation for
mapping database rows to your entity. The mapper is named `{ClassName}AutoRowMapper` and is generated in the same file
as your CRUD queries.

For example, for a class named `Sqlx4k`, the generator creates `Sqlx4kAutoRowMapper`:

```kotlin
object Sqlx4kAutoRowMapper : RowMapper<Sqlx4k> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Sqlx4k {
        val id = row.get("id").asInt()
        val test = row.get("test").asString()
        return Sqlx4k(id = id, test = test)
    }
}
```

By default, the generated repository uses the auto-generated `RowMapper` of its entity. You can override this by
providing a custom mapper explicitly: `@Repository(mapper = CustomRowMapper::class)`.

## Dialects

The `dialect` option adjusts the generated SQL to the database. Set it to `postgresql` to also enable the
PostgreSQL-specific decoders (array types):

```kotlin
ksp {
    arg("dialect", "postgresql")  // Enables PostgreSQL array decoders
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
}
```

| Dialect                  | Effect                                                                     |
|--------------------------|----------------------------------------------------------------------------|
| `mysql`                  | Adjusts CRUD query generation for MySQL compatibility.                     |
| `mariadb`                | Like `mysql`, but uses `INSERT ... RETURNING` (MariaDB 10.5+) for `insert()`. |
| `postgresql`             | Enables PostgreSQL-specific extensions (array types).                      |
| `sqlite`                 | Adjusts CRUD query generation for SQLite compatibility.                    |
| default (or `generic`)   | Uses standard SQL with builtin decoders.                                   |

The [Gradle plugin](../getting-started/gradle-plugin.md) sets the dialect from the chosen `driver`.
