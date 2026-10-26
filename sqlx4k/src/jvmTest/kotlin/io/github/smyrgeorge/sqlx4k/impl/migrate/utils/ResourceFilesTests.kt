package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test

class ResourceFilesTests {

    private fun jarWith(vararg entries: Pair<String, String>): File {
        val jar = Files.createTempFile("sqlx4k-migrations", ".jar").toFile().also { it.deleteOnExit() }
        JarOutputStream(jar.outputStream()).use { out ->
            // Directory entries too, as in a jar built by Gradle: the class loader resolves a directory through them.
            entries.flatMap { (name, _) -> name.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }
                .distinct()
                .forEach { dir -> out.putNextEntry(JarEntry("$dir/")); out.closeEntry() }
            entries.forEach { (name, content) ->
                out.putNextEntry(JarEntry(name))
                out.write(content.encodeToByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `reads the migrations of the directory only, not of its siblings`() {
        val jar = jarWith(
            "db/migrations/1_create.sql" to "create table a(id int);",
            "db/migrations/2_insert.sql" to "insert into a values (1);",
            // A sibling directory sharing the prefix: must not be picked up from the jar either.
            "db/migrations_seed/3_seed.sql" to "insert into a values (2);",
        )
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
            val files = readSqlFilesFromResources("db/migrations", loader)
            assertThat(files.map { it.first }).containsExactlyInAnyOrder("1_create.sql", "2_insert.sql")
        }
    }
}
