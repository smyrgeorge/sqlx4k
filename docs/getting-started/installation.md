# Installation

sqlx4k is published to Maven Central. Add the driver of your database:

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

## With the Gradle plugin

Or let the [Gradle plugin](gradle-plugin.md) add the driver and set up the code generation for you:

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

## Modules

| Module                 | Description                                                                                                               |
|------------------------|---------------------------------------------------------------------------------------------------------------------------|
| `sqlx4k`               | The core API: drivers' common interfaces, statements, result sets, transactions, migrations.                              |
| `sqlx4k-postgres`      | The [PostgreSQL](../databases/postgresql.md) driver.                                                                      |
| `sqlx4k-mysql`         | The [MySQL / MariaDB](../databases/mysql.md) driver.                                                                      |
| `sqlx4k-sqlite`        | The [SQLite](../databases/sqlite.md) driver.                                                                              |
| `sqlx4k-sqlite-cipher` | The [SQLCipher](../databases/sqlcipher.md) (encrypted SQLite) driver.                                                     |
| `sqlx4k-codegen`       | The KSP [code generator](../codegen/index.md) for `@Table` entities and `@Repository` interfaces.                         |
| `sqlx4k-codegen-test`  | Generates [in-memory repositories](../codegen/in-memory-repositories.md) for unit tests.                                  |
| `sqlx4k-arrow`         | Repository interfaces for [Arrow](https://arrow-kt.io/) (see [Hooks](../codegen/hooks.md#list-of-repository-interfaces)). |
| `sqlx4k-postgres-pgmq` | A client for the [PostgreSQL Message Queue](../extensions/pgmq.md).                                                       |
| `sqlx4k-gradle-plugin` | The [Gradle plugin](gradle-plugin.md).                                                                                    |
