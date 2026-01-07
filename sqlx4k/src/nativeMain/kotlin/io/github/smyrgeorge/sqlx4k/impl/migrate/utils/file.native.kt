package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile

actual fun listMigrationFilesFromResources(path: String): List<MigrationFile> =
    listMigrationFiles("resources/$path")
