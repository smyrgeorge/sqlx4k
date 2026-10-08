package io.github.smyrgeorge.sqlx4k.gradle

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sqlx4kExtensionTest {

    private fun extension(): Sqlx4kExtension = ProjectBuilder.builder().build().applySqlx4k()

    @Test
    fun `drivers carry the codegen dialect and the sqlx4k artifact`() {
        assertEquals("mysql" to "sqlx4k-mysql", Driver.MySQL.dialect to Driver.MySQL.artifact)
        assertEquals("mariadb" to "sqlx4k-mysql", Driver.MariaDB.dialect to Driver.MariaDB.artifact)
        assertEquals("postgresql" to "sqlx4k-postgres", Driver.PostgreSQL.dialect to Driver.PostgreSQL.artifact)
        assertEquals("sqlite" to "sqlx4k-sqlite", Driver.SQLite.dialect to Driver.SQLite.artifact)
        assertEquals("sqlite" to "sqlx4k-sqlite-cipher", Driver.SQLiteCipher.dialect to Driver.SQLiteCipher.artifact)
    }

    @Test
    fun `extensions carry their artifact and driver requirement`() {
        assertEquals("sqlx4k-postgres-pgmq", Extension.Pgmq.artifact)
        assertEquals(Driver.PostgreSQL, Extension.Pgmq.requiredDriver)
        assertEquals("sqlx4k-arrow", Extension.Arrow.artifact)
        assertEquals(null, Extension.Arrow.requiredDriver)
    }

    @Test
    fun `options have sensible defaults`() {
        val options = extension()

        assertFalse(options.driver.isPresent) // required — no default
        assertFalse(options.generatedCodePackage.isPresent)
        assertEquals(listOf("commonMain"), options.sourceSets.get())
        assertEquals(emptyList(), options.enabledExtensions.get())
        assertEquals(emptyMap(), options.args.get())
        assertTrue(options.addDependencies.get())
    }

    @Test
    fun `the driver and extension values are exposed in the dsl scope`() {
        val options = extension()

        assertEquals(Driver.MySQL, options.MySQL)
        assertEquals(Driver.MariaDB, options.MariaDB)
        assertEquals(Driver.PostgreSQL, options.PostgreSQL)
        assertEquals(Driver.SQLite, options.SQLite)
        assertEquals(Driver.SQLiteCipher, options.SQLiteCipher)
        assertEquals(Extension.Pgmq, options.Pgmq)
        assertEquals(Extension.Arrow, options.Arrow)
    }

    @Test
    fun `extensions accumulate`() {
        val options = extension()

        options.extensions(Extension.Pgmq)
        options.extensions(Extension.Arrow)
        assertEquals(listOf(Extension.Pgmq, Extension.Arrow), options.enabledExtensions.get())
    }

    @Test
    fun `args are collected in order`() {
        val options = extension()

        options.arg("b", "2")
        options.arg("a", "1")
        assertEquals(listOf("b" to "2", "a" to "1"), options.args.get().toList())
    }

    @Test
    fun `assigning args replaces what arg added`() {
        val options = extension()

        options.arg("a", "1")
        options.args.set(mapOf("b" to "2"))
        options.arg("c", "3")
        assertEquals(mapOf("b" to "2", "c" to "3"), options.args.get())
    }
}
