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
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

object Migrator {
    suspend fun migrate(
        db: Driver,
        path: String,
        table: String,
        dialect: Dialect,
        afterSuccessfulStatementExecution: suspend (Statement) -> Unit = {},
        afterSuccessfullyFileMigration: suspend (Migration) -> Unit = {}
    ): Result<Unit> = runCatching {
        val files: List<MigrationFile> = listMigrationFiles(path)
        if (files.isEmpty()) return@runCatching

        // Ensure there are no duplicate versions in the filesystem set.
        val dup = files.groupBy { it.version }.filterValues { it.size > 1 }
        if (dup.isNotEmpty()) {
            val msg = dup.entries.joinToString { (v, list) -> "version=$v -> [" + list.joinToString { it.name } + "]" }
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

        val applied = db.transaction {
            // Ensure migrations table exists.
            execute(Migration.createTableIfNotExists(table, dialect)).getOrThrow()
            // Fetch already applied versions and checksums.
            fetchAll(Migration.selectAll(table), Migration.RowMapper).getOrThrow()
                .associateBy { row -> row.version }
        }

        sortedFiles.forEach { file ->
            val name = file.name
            val version = file.version
            val content = readEntireFileUtf8(file.path)
            val checksum = content.checksum()

            applied[version]?.let { previous ->
                // Check that the file has not been modified since it was applied.
                if (previous.checksum == checksum) return@forEach
                else SQLError(SQLError.Code.Migrate, "Checksum mismatch for migration file ${file.name}").ex()
            }

            // Split the file content into individual statements.
            val statements: List<Statement> = splitSqlStatements(content).map { Statement.create(it) }
            if (statements.isEmpty()) SQLError(SQLError.Code.Migrate, "Migration file ${file.name} is empty.").ex()

            // Execute all the statements (of a file) in a single transaction
            val migration: Migration = db.transaction {
                val executionTime = measureTime {
                    statements.forEach { statement ->
                        // Execute the statement
                        execute(statement).getOrThrow()
                        // Ensure callback exceptions surface as migration failures
                        afterSuccessfulStatementExecution(statement)
                    }
                }
                val res = Migration(
                    version = version,
                    name = name,
                    installedOn = Clock.System.now(),
                    checksum = checksum,
                    executionTime = executionTime.inWholeMilliseconds
                )
                // Insert the result in the table.
                execute(res.insert(table)).getOrThrow()
                res
            }
            // Callback after full-file success
            afterSuccessfullyFileMigration(migration)
        }
    }
}
