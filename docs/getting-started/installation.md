# Installation

sqlx4k is published to Maven Central.

## With the Gradle plugin (recommended)

The suggested way to set up sqlx4k: the [Gradle plugin](gradle-plugin.md) adds the driver of your database and sets
up the [code generation](../codegen/index.md) (`@Table`, `@Repository`, compile-time validations and optimizations)
for you, from a single `sqlx4k { }` block:

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

See [Gradle plugin](gradle-plugin.md) for all the options, such as the extensions and the code-generator arguments.

## Without the plugin

Or add the driver of your database yourself:

=== "PostgreSQL"

    ```kotlin
    implementation("io.github.smyrgeorge:sqlx4k-postgres:x.y.z")
    ```

=== "MySQL / MariaDB"

    ```kotlin
    implementation("io.github.smyrgeorge:sqlx4k-mysql:x.y.z")
    ```

=== "SQLite"

    ```kotlin
    implementation("io.github.smyrgeorge:sqlx4k-sqlite:x.y.z")
    ```

=== "SQLCipher"

    ```kotlin
    // SQLite with encryption.
    implementation("io.github.smyrgeorge:sqlx4k-sqlite-cipher:x.y.z")
    ```

!!! note

    The driver alone does not set up the [code generation](../codegen/index.md) (`@Table`, `@Repository`,
    compile-time validations): use the plugin, or configure KSP yourself as described in
    [Manual setup](../codegen/index.md#manual-setup).

## Modules

| Module                 | Description                                                                                                                     |
|------------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `sqlx4k`               | The core API: drivers' common interfaces, statements, result sets, transactions, migrations.                                    |
| `sqlx4k-postgres`      | The [PostgreSQL](../databases/postgresql.md) driver.                                                                            |
| `sqlx4k-mysql`         | The [MySQL / MariaDB](../databases/mysql.md) driver.                                                                            |
| `sqlx4k-sqlite`        | The [SQLite](../databases/sqlite.md) driver.                                                                                    |
| `sqlx4k-sqlite-cipher` | The [SQLCipher](../databases/sqlcipher.md) (encrypted SQLite) driver.                                                           |
| `sqlx4k-codegen`       | The KSP [code generator](../codegen/index.md) for `@Table` entities and `@Repository` interfaces.                               |
| `sqlx4k-codegen-test`  | Generates [in-memory repositories](../codegen/in-memory-repositories.md) for unit tests.                                        |
| `sqlx4k-arrow`         | Repository interfaces for [Arrow](https://arrow-kt.io/) (see [Repositories](../codegen/repositories.md#repository-interfaces)). |
| `sqlx4k-postgres-pgmq` | A client for the [PostgreSQL Message Queue](../extensions/pgmq.md).                                                             |
| `sqlx4k-gradle-plugin` | The [Gradle plugin](gradle-plugin.md).                                                                                          |
