package io.github.smyrgeorge.sqlx4k.impl.migrate

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.mappers.StringRowMapper
import io.github.smyrgeorge.sqlx4k.impl.mappers.StringRowMapper.toSingleString
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.readMigrationFilesFromDisk
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.splitSqlStatements
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.measureTime

object Migrator {
    /**
     * Represents the results of a specific operation, typically within the context of migrations
     * or data processing tasks.
     *
     * @property total The total number of items or actions processed.
     * @property applied The number of items or actions successfully applied.
     * @property validated The number of items or actions validated successfully.
     * @property executionTime The overall time taken to execute the operation.
     */
    data class Results(
        val total: Int,
        val applied: Int,
        val validated: Int,
        val executionTime: Duration
    ) {
        companion object {
            val Empty = Results(0, 0, 0, Duration.ZERO)
        }
    }

    private val tableRegex = "_?[A-Za-z0-9_]+".toRegex()
    private val schemaRegex = "_?[A-Za-z0-9_]+".toRegex()

    /**
     * Migrates a database by applying appropriate migration files located at the specified path.
     * It updates the database schema and updates the migration information within the specified table.
     *
     * @param db The `Driver` instance responsible for executing SQL queries and managing migrations.
     * @param path The directory path where the migration files are located. Must not be blank.
     * @param table The name of the table used to track migration progress.
     * @param schema The schema within which the table resides. Can be null.
     * @param createSchema A flag indicating if the schema should be created if it does not exist.
     * @param dialect The `Dialect` type specifying the SQL dialect to use for migrations.
     * @param afterStatementExecution A suspendable callback function executed after each statement execution. It receives the executed `Statement` and `Duration` of execution as
     *  arguments.
     * @param afterFileMigration A suspendable callback function executed after migrating each file. It receives the `Migration` details and `Duration` of migration as arguments.
     * @return A `Result` containing a `Results` instance indicating whether the migration was successful or empty if no files were processed.
     */
    suspend fun migrate(
        db: Driver,
        path: String,
        table: String,
        schema: String?,
        createSchema: Boolean,
        dialect: Dialect,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Results> = runCatching {
        require(path.isNotBlank()) { "Path cannot be blank." }
        migrate(
            db = db,
            files = readMigrationFilesFromDisk(path),
            table = table,
            schema = schema,
            createSchema = createSchema,
            dialect = dialect,
            afterStatementExecution = afterStatementExecution,
            afterFileMigration = afterFileMigration
        ).getOrThrow()
    }

    /**
     * Executes the migration of a database using the provided configuration and files.
     *
     * This function performs database schema migration by applying a sequence of migration files
     * to the specified database. It handles version tracking, optional schema creation, and dialect-specific nuances.
     *
     * @param db The database driver used for executing queries and managing transactions.
     * @param supplier A function that supplies a list of migration files to be applied to the database.
     * @param table The name of the table used for migration tracking (e.g., to store the migration history).
     * @param schema The optional schema in which the migration tracking table resides. If null, defaults to the database's default schema.
     * @param createSchema Whether to create the schema if it doesn't already exist.
     * @param dialect The SQL dialect to use for executing structured queries.
     * @param afterStatementExecution A suspendable callback executed after each SQL statement, providing the executed statement and its execution duration.
     * @param afterFileMigration A suspendable callback executed after each migration file is applied, providing details of the migration and its duration.
     * @return A `Result` containing the migration results if successful, or an exception if an error occurs during migration.
     */
    suspend fun migrate(
        db: Driver,
        supplier: () -> List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        dialect: Dialect,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Results> = runCatching {
        migrate(
            db = db,
            files = supplier(),
            table = table,
            schema = schema,
            createSchema = createSchema,
            dialect = dialect,
            afterStatementExecution = afterStatementExecution,
            afterFileMigration = afterFileMigration
        ).getOrThrow()
    }

    /**
     * Migrates a database by applying appropriate migration files located at the specified path.
     * It updates the database schema and updates the migration information within the specified table.
     *
     * @param db The `Driver` instance responsible for executing SQL queries and managing migrations.
     * @param files A list of `MigrationFile` instances representing the migration files to be applied.
     * @param table The name of the table used to track migration progress.
     * @param schema The schema within which the table resides. Can be null.
     * @param createSchema A flag indicating if the schema should be created if it does not exist.
     * @param dialect The `Dialect` type specifying the SQL dialect to use for migrations.
     * @param afterStatementExecution A suspendable callback function executed after each statement execution. It receives the executed `Statement` and `Duration` of execution as
     *  arguments.
     * @param afterFileMigration A suspendable callback function executed after migrating each file. It receives the `Migration` details and `Duration` of migration as arguments.
     * @return A `Result` containing a `Results` instance indicating whether the migration was successful or empty if no files were processed.
     */
    suspend fun migrate(
        db: Driver,
        files: List<MigrationFile>,
        table: String,
        schema: String?,
        createSchema: Boolean,
        dialect: Dialect,
        afterStatementExecution: suspend (Statement, Duration) -> Unit,
        afterFileMigration: suspend (Migration, Duration) -> Unit,
    ): Result<Results> = runCatching {
        require(table.isNotBlank()) { "Table name cannot be blank." }
        require(tableRegex.matches(table)) { "Table name must match the regex: $tableRegex" }
        if (createSchema) require(schema != null) { "Schema must be specified when creating a new schema."}
        if (files.isEmpty()) return@runCatching Results.Empty

        var totalCount = 0
        var appliedCount = 0
        var validatedCount = 0
        val executionTime = measureTime {
            // Ensure there are no duplicate versions in the filesystem set.
            val dup = files.groupBy { it.version }.filterValues { it.size > 1 }
            if (dup.isNotEmpty()) {
                val msg = dup.entries.joinToString { (v, l) -> "version=$v -> [" + l.joinToString { it.name } + "]" }
                SQLError(SQLError.Code.Migrate, "Duplicate migration versions detected: $msg").raise()
            }

            // Ensure deterministic order and monotonic versions.
            val sortedFiles = files.sortedBy { it.version }
            // Ensure the discovered (filesystem) order is strictly monotonic by version.
            for (i in 1 until sortedFiles.size) {
                val prev = sortedFiles[i - 1]
                val curr = sortedFiles[i]
                if (curr.version - prev.version > 1) {
                    SQLError(
                        SQLError.Code.Migrate,
                        "Non-monotonic migration versions detected in filesystem order: ${prev.name} (v=${prev.version}) then ${curr.name} (v=${curr.version})."
                    ).raise()
                }
            }

            // Build the qualified table name.
            val table = schema?.let {
                require(dialect != Dialect.SQLite) { "SQLite does not support schemas." }
                require(it.isNotBlank()) { "Schema name cannot be blank." }
                require(schemaRegex.matches(it)) { "Schema name must match the regex: $schemaRegex" }
                // Ensure the schema exists.
                if (createSchema) db.execute(Migration.createSchemaIfNotExists(it, dialect)).getOrThrow()
                "$it.$table"
            } ?: table

            val applied = db.transaction {
                // Ensure migrations table exists.
                execute(Migration.createTableIfNotExists(table, dialect)).getOrThrow()
                // Fetch already applied versions and checksums.
                fetchAll(Migration.selectAll(table), Migration.RowMapper).getOrThrow().associateBy { it.version }
            }

            totalCount = files.size

            // Apply migrations.
            sortedFiles.forEach { file ->
                applied[file.version]?.let { previous ->
                    // Check that the file has not been modified since it was applied.
                    if (previous.checksum == file.checksum) {
                        validatedCount++
                        return@forEach
                    } else SQLError(SQLError.Code.Migrate, "Checksum mismatch for migration file ${file.name}").raise()
                }

                // Split the file content into individual statements.
                val statements: List<Statement> = splitSqlStatements(file.content).map { Statement.create(it) }
                if (statements.isEmpty()) SQLError(
                    SQLError.Code.Migrate,
                    "Migration file ${file.name} is empty."
                ).raise()

                // Execute all the statements (of a file) in a single transaction
                val (migration, duration) = db.transaction {
                    // Ensure that the search path is set for the schema.
                    val searchPath = schema?.let {
                        // Find current search path.
                        val getSearchPathStatement = Migration.getSearchPath(dialect)
                        val searchPath = fetchAll(getSearchPathStatement, StringRowMapper).toSingleString().getOrThrow()

                        // Set the search path to the desired schema.
                        val setSearchpathStatement = Migration.setSearchpath(it, dialect)
                        execute(setSearchpathStatement).getOrThrow()

                        searchPath
                    }

                    val duration = measureTime {
                        statements.forEach { statement ->
                            // Execute the statement
                            val duration = measureTime {
                                execute(statement)
                                    .onFailure { error ->
                                        // Reset the search path to what it was before.
                                        searchPath?.let {
                                            val searchpathStatement = Migration.setSearchpath(it, dialect)
                                            execute(searchpathStatement).getOrThrow()
                                        }
                                        throw error
                                    }
                            }
                            // Ensure callback exceptions surface as migration failures
                            afterStatementExecution(statement, duration)
                        }
                    }

                    // Reset the search path to what it was before.
                    searchPath?.let {
                        val searchpathStatement = Migration.setSearchpath(it, dialect)
                        execute(searchpathStatement).getOrThrow()
                    }

                    appliedCount++

                    val res = Migration(
                        version = file.version,
                        name = file.name,
                        installedOn = Clock.System.now(),
                        checksum = file.checksum,
                        executionTime = duration.inWholeMilliseconds
                    )
                    // Insert the result in the table.
                    execute(res.insert(table)).getOrThrow()
                    res to duration
                }
                // Callback after full-file success
                afterFileMigration(migration, duration)
            }
        }

        return@runCatching Results(totalCount, appliedCount, validatedCount, executionTime)
    }

    /**
     * Represents a database interface that provides functionality for executing queries
     * and managing transactional operations.
     *
     * This interface extends both `QueryExecutor` and `QueryExecutor.Transactional`,
     * combining the ability to perform query execution with transactional control.
     */
    interface Driver : QueryExecutor, QueryExecutor.Transactional
}
