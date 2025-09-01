package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

private val fs = FileSystem.SYSTEM

internal fun readEntireFileUtf8(path: String): String = fs.read(path.toPath()) { readUtf8() }
internal fun String.sha256Hex(): String = encodeUtf8().sha256().hex()
internal fun listMigrationFiles(path: String): List<MigrationFile> {
    val dir: Path = path.toPath()
    val meta = fs.metadataOrNull(dir) ?: error("Migrations path not found: $path")
    require(meta.isDirectory) { "Migrations path not a directory: $path" }
    return fs.list(dir)
        .filter { p -> p.name.lowercase().endsWith(".sql") && (fs.metadata(p).isRegularFile) }
        .map { p -> MigrationFile(name = p.name, path = p.toString()) }
}
