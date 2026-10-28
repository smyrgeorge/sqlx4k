# Code generation

sqlx4k ships a KSP code generator (`sqlx4k-codegen`) that writes, at build time:

- the CRUD operations (`insert`, `update`, `delete`, `save`, [batch operations](batch-operations.md)) of every
  `@Table` entity,
- the implementation of every [`@Repository`](repositories.md) interface, including its `@Query` methods,
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

Map a data class to a table with `@Table`, and declare a `@Repository` for it:

```kotlin
@Table("sqlx4k")
data class Sqlx4k(
    @Id(insert = true) // Will be included in the insert query.
    val id: Int,
    val test: String
)

@Repository
interface Sqlx4kRepository : CrudRepository<Sqlx4k> {
    @Query("SELECT * FROM sqlx4k WHERE id = :id")
    suspend fun findOneById(context: QueryExecutor, id: Int): Result<Sqlx4k?>
}
```

The generator writes `Sqlx4kRepositoryImpl`, with the CRUD operations of the entity and your `@Query` methods:

```kotlin
val res: Sqlx4k = Sqlx4kRepositoryImpl.insert(db, Sqlx4k(id = 1, test = "test")).getOrThrow()
val one: Sqlx4k? = Sqlx4kRepositoryImpl.findOneById(db, 1).getOrThrow()
```

- [Repositories](repositories.md): the generated CRUD operations, the `@Query` methods and the repository interfaces.
- [Entities](entities.md): how the properties map to columns (`@Column`, `@Transient`, `@Version`, `@Converter`).

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

| Dialect                | Effect                                                                        |
|------------------------|-------------------------------------------------------------------------------|
| `mysql`                | Adjusts CRUD query generation for MySQL compatibility.                        |
| `mariadb`              | Like `mysql`, but uses `INSERT ... RETURNING` (MariaDB 10.5+) for `insert()`. |
| `postgresql`           | Enables PostgreSQL-specific extensions (array types).                         |
| `sqlite`               | Adjusts CRUD query generation for SQLite compatibility.                       |
| default (or `generic`) | Uses standard SQL with builtin decoders.                                      |

The [Gradle plugin](../getting-started/gradle-plugin.md) sets the dialect from the chosen `driver`.
