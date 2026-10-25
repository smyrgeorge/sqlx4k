package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private val fs = SystemFileSystem

private fun readEntireFileFromDisk(path: Path): String {
    // A single readAtMostTo reads at most one segment (8 KiB on the JVM): read the source until it is exhausted.
    try {
        return fs.source(path).buffered().use { it.readByteArray().decodeToString() } // UTF-8 by default
    } catch (e: Exception) {
        println("Could not read file: ${e.message}")
        throw e
    }
}

fun readSqlFilesFromDisk(path: String): List<Pair<String, String>> {
    val dir = Path(path)
    val meta = fs.metadataOrNull(dir) ?: error("Migrations path not found: $path")
    require(meta.isDirectory) { "Migrations path not a directory: $path" }
    return fs.list(dir)
        .filter { p -> p.name.lowercase().endsWith(".sql") && (fs.metadataOrNull(p)!!.isRegularFile) }
        .map { p ->
            val content = readEntireFileFromDisk(p)
            p.name to content
        }
}

expect fun readSqlFilesFromResources(path: String): List<Pair<String, String>>

fun readMigrationFilesFromDisk(path: String): List<MigrationFile> =
    readSqlFilesFromDisk(path).map { MigrationFile(it.first, it.second) }

expect fun readMigrationFilesFromResources(path: String): List<MigrationFile>
