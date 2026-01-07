package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

actual fun readSqlFilesFromResources(path: String): List<Pair<String, String>> =
    readSqlFilesFromDisk("resources/$path")

actual fun readMigrationFilesFromResources(path: String): List<MigrationFile> =
    readMigrationFilesFromDisk("resources/$path")
