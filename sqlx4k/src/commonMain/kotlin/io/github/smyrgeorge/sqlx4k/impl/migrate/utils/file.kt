package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private val fs = SystemFileSystem

private fun readEntireFileUtf8(path: String): String {
    val buffer = Buffer()
    val source = fs.source(Path(path))
    try {
        source.readAtMostTo(buffer, Int.MAX_VALUE.toLong())
        return buffer.readByteArray().decodeToString() // UTF-8 by default
    } catch (e: Exception) {
        println("Could not read file: ${e.message}")
        throw e
    } finally {
        source.close()
        buffer.close()
    }
}

fun listFilesWithContent(path: String): List<Pair<String, String>> {
    val dir = Path(path)
    val meta = fs.metadataOrNull(dir) ?: error("Migrations path not found: $path")
    require(meta.isDirectory) { "Migrations path not a directory: $path" }
    return fs.list(dir)
        .filter { p -> p.name.lowercase().endsWith(".sql") && (fs.metadataOrNull(p)!!.isRegularFile) }
        .map { p ->
            val content = readEntireFileUtf8(p.toString())
            p.name to content
        }
}

fun listMigrationFiles(path: String): List<MigrationFile> =
    listFilesWithContent(path).map { MigrationFile(it.first, it.second) }
