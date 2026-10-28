# Gradle plugin

The Gradle plugin (`sqlx4k-gradle-plugin`) sets up sqlx4k in a project from a single `sqlx4k { }` block, replacing the
manual KSP wiring shown in [Code generation](../codegen/index.md#manual-setup). When applied, it:

- applies the KSP Gradle plugin and registers the sqlx4k code generator (`sqlx4k-codegen`) on the configured source
  sets (`commonMain` by default), at the plugin's own version,
- passes to the code generator the SQL dialect of the chosen `driver` and the `generatedCodePackage`, followed by any
  option you put in `args` (so an `args` entry can override both),
- adds the generated sources of `commonMain` to the project and orders every Kotlin compilation and KSP task after the
  `commonMain` code generation, so the generated code exists before anything compiles,
- adds the driver (e.g. `sqlx4k-postgres`) and the enabled extensions (e.g. `sqlx4k-postgres-pgmq`) as dependencies at
  the matching version, as `implementation` or, with `dependencyScope = Api`, as `api` (for a library module whose
  public API, e.g. the generated code, exposes sqlx4k types), unless `addDependencies = false`.

A complete build script (see the [examples](examples.md), which are built with the plugin):

```kotlin
plugins {
    kotlin("multiplatform") // or kotlin("jvm")
    id("io.github.smyrgeorge.sqlx4k") version "x.y.z"
}

kotlin {
    jvm()
    macosArm64 { binaries { executable() } }
    // Include other targets as needed
}

sqlx4k {
    driver = PostgreSQL // also: MySQL, MariaDB, SQLite, SQLiteCipher
    generatedCodePackage = "io.github.smyrgeorge.sqlx4k.examples.postgres"
    extensions(Pgmq) // sqlx4k extensions; Pgmq (`sqlx4k-postgres-pgmq`) is PostgreSQL only, Arrow (`sqlx4k-arrow`)
    // Any sqlx4k code-generator option, applied last.
    // See "Code generation" for the full list.
    args = mapOf("expand-select-star" to "false")
}
```

## Options

| Option                 | Default          | Description                                                                                                                                                        |
|------------------------|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `driver`               | required         | The database driver. It also selects the SQL dialect of the code generator.                                                                                        |
| `generatedCodePackage` | required         | The package of the generated sources (the code generator's `output-package`).                                                                                      |
| `sourceSets`           | `["commonMain"]` | The source sets the code generator processes: `commonMain` (generated once, visible to every target), a target's `<target>Main`, or `main` for plain JVM projects. |
| `extensions(...)`      | none             | The sqlx4k extensions to add: `Pgmq` (PostgreSQL only) and `Arrow`.                                                                                                |
| `args` / `arg(k, v)`   | none             | Code-generator options, applied last (an entry under `dialect` or `output-package` overrides the derived value).                                                   |
| `addDependencies`      | `true`           | Whether the driver and extension dependencies are added at the plugin's version. Disable to manage them (and their versions) yourself.                             |
| `dependencyScope`      | `Implementation` | The configuration the driver and extension dependencies are added to: `Implementation` or `Api` (`commonMainApi`, or `api` for plain JVM projects).                |

The options are documented in detail in
[Sqlx4kExtension.kt](https://github.com/smyrgeorge/sqlx4k/blob/main/sqlx4k-gradle-plugin/src/main/kotlin/io/github/smyrgeorge/sqlx4k/gradle/Sqlx4kExtension.kt).
The code-generator options you can pass through `args` are listed in [Code generation](../codegen/index.md) and
[Validation](../codegen/validation.md).

!!! tip "Plain JVM projects"

    For a plain JVM project (`kotlin("jvm")`) set `sourceSets = listOf("main")`; KSP then wires the generated sources
    into the compilation itself.
