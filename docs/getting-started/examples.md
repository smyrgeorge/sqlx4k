# Examples

Full runnable applications, built with the [Gradle plugin](gradle-plugin.md), live in the repository:

- PostgreSQL: [examples/postgres](https://github.com/smyrgeorge/sqlx4k/tree/main/examples/postgres)
- MySQL: [examples/mysql](https://github.com/smyrgeorge/sqlx4k/tree/main/examples/mysql)
- SQLite: [examples/sqlite](https://github.com/smyrgeorge/sqlx4k/tree/main/examples/sqlite)

## Running the examples

First, start up the database instances:

```shell
docker compose up -d
```

Then [build the project](../contributing/building.md) and run the examples:

```shell
# For macosArm64
./examples/postgres/build/bin/macosArm64/releaseExecutable/postgres.kexe
./examples/mysql/build/bin/macosArm64/releaseExecutable/mysql.kexe
./examples/sqlite/build/bin/macosArm64/releaseExecutable/sqlite.kexe
# If you run in another platform consider running the correct target.
```
