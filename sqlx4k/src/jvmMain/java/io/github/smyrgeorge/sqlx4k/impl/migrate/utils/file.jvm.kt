package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import java.io.File
import java.net.URI
import java.util.jar.JarFile

actual fun readSqlFilesFromResources(path: String): List<Pair<String, String>> {
    val classLoader = ClassLoader.getSystemClassLoader()
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
            val jarFile = JarFile(File(URI(jarPath)))
            jarFile.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { it.name.startsWith(path) }
                .filter { it.name.endsWith(".sql") }
                .map { entry ->
                    entry.name.substringAfterLast('/') to
                            jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
                }
                .toList()
        }

        else -> throw IllegalArgumentException("Unsupported protocol: ${url.protocol}")
    }
}

actual fun readMigrationFilesFromResources(path: String): List<MigrationFile> =
    readSqlFilesFromResources(path).map { MigrationFile(it.first, it.second) }
