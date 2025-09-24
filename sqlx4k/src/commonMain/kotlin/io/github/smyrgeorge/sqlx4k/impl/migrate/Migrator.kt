@file:OptIn(ExperimentalTime::class)

package io.github.smyrgeorge.sqlx4k.impl.migrate

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.checksum
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.listMigrationFiles
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.readEntireFileUtf8
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.splitSqlStatements
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
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
        require(table.isNotBlank()) { "Table name cannot be blank." }
        require(tableRegex.matches(table)) { "Table name must match the regex: $tableRegex" }

        val files: List<MigrationFile> = listMigrationFiles(path)
        if (files.isEmpty()) return@runCatching Results.Empty

        var totalCount = 0
        var appliedCount = 0
        var validatedCount = 0
        val executionTime = measureTime {
            // Ensure there are no duplicate versions in the filesystem set.
            val dup = files.groupBy { it.version }.filterValues { it.size > 1 }
            if (dup.isNotEmpty()) {
                val msg = dup.entries.joinToString { (v, l) -> "version=$v -> [" + l.joinToString { it.name } + "]" }
                SQLError(SQLError.Code.Migrate, "Duplicate migration versions detected: $msg").ex()
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
                    ).ex()
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
                val name = file.name
                val version = file.version
                val content = readEntireFileUtf8(file.path)
                val checksum = content.checksum()

                applied[version]?.let { previous ->
                    // Check that the file has not been modified since it was applied.
                    if (previous.checksum == checksum) {
                        validatedCount++
                        return@forEach
                    } else SQLError(SQLError.Code.Migrate, "Checksum mismatch for migration file ${file.name}").ex()
                }

                // Split the file content into individual statements.
                val statements: List<Statement> = splitSqlStatements(content).map { Statement.create(it) }
                if (statements.isEmpty()) SQLError(SQLError.Code.Migrate, "Migration file ${file.name} is empty.").ex()

                // Execute all the statements (of a file) in a single transaction
                val (migration, duration) = db.transaction {
                    val duration = measureTime {
                        statements.forEach { statement ->
                            // Execute the statement
                            val duration = measureTime { execute(statement).getOrThrow() }
                            // Ensure callback exceptions surface as migration failures
                            afterStatementExecution(statement, duration)
                        }
                    }

                    appliedCount++

                    val res = Migration(
                        version = version,
                        name = name,
                        installedOn = Clock.System.now(),
                        checksum = checksum,
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
}
