package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import java.io.File
import java.net.URI
import java.util.jar.JarFile

actual fun readSqlFilesFromResources(path: String): List<Pair<String, String>> =
    readSqlFilesFromResources(path, ClassLoader.getSystemClassLoader())

internal fun readSqlFilesFromResources(path: String, classLoader: ClassLoader): List<Pair<String, String>> {
    val url = classLoader.getResource(path) ?: throw IllegalArgumentException("Resource path not found: $path")

    return when (url.protocol) {
        "file" -> {
            // Running from IDE or unpacked
            File(url.toURI()).walkTopDown()
                .filter { it.isFile }
                .filter { it.extension == "sql" }
                .map { file -> file.name to file.bufferedReader().use { it.readText() } }
                .toList()
        }

        "jar" -> {
            // Running from JAR
            val jarPath = url.path.substringBefore("!")
            // The entries under the directory, as when unpacked: a bare prefix would also match its siblings
            // (`db/migrations_seed/` for `db/migrations`).
            val directory = path.trimEnd('/') + "/"
            JarFile(File(URI(jarPath))).use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { it.name.startsWith(directory) }
                    .filter { it.name.endsWith(".sql") }
                    .map { entry ->
                        entry.name.substringAfterLast('/') to
                                jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
                    }
                    .toList()
            }
        }

        else -> throw IllegalArgumentException("Unsupported protocol: ${url.protocol}")
    }
}

actual fun readMigrationFilesFromResources(path: String): List<MigrationFile> =
    readSqlFilesFromResources(path).map { MigrationFile(it.first, it.second) }
