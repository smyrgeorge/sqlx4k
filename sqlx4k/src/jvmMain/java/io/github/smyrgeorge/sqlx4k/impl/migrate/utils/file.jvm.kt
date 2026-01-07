package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import java.io.File
import java.net.URI
import java.util.jar.JarFile

actual fun listMigrationFilesFromResources(path: String): List<MigrationFile> {
    val classLoader = ClassLoader.getSystemClassLoader()
    val url = classLoader.getResource(path) ?: throw IllegalArgumentException("Resource path not found: $path")

    return when (url.protocol) {
        "file" -> {
            // Running from IDE or unpacked
            File(url.toURI()).walkTopDown()
                .filter { it.isFile }
                .map { file ->
                    MigrationFile(
                        name = file.name,
                        content = file.readText()
                    )
                }
                .toList()
        }

        "jar" -> {
            // Running from JAR
            val jarPath = url.path.substringBefore("!")
            val jarFile = JarFile(File(URI(jarPath)))

            jarFile.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(path) }
                .map { entry ->
                    MigrationFile(
                        name = entry.name.substringAfterLast('/'),
                        content = jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
                    )
                }
                .toList()
        }

        else -> throw IllegalArgumentException("Unsupported protocol: ${url.protocol}")
    }
}
