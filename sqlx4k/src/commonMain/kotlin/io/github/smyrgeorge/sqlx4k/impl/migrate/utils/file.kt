package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

private val fs = SystemFileSystem

internal fun readEntireFileUtf8(path: String): String {
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

internal fun String.checksum(): String = hashCode().toString()
internal fun listMigrationFiles(path: String): List<MigrationFile> {
    val dir = Path(path)
    val meta = fs.metadataOrNull(dir) ?: error("Migrations path not found: $path")
    require(meta.isDirectory) { "Migrations path not a directory: $path" }
    return fs.list(dir)
        .filter { p -> p.name.lowercase().endsWith(".sql") && (fs.metadataOrNull(p)!!.isRegularFile) }
        .map { p -> MigrationFile(name = p.name, path = p.toString()) }
}
