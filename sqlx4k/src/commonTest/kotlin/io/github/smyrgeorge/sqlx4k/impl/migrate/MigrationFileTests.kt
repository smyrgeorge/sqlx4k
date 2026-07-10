package io.github.smyrgeorge.sqlx4k.impl.migrate

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MigrationFileTests {

    // ========================================================================================
    // valid filenames – version + description extraction
    // ========================================================================================

    @Test
    fun `valid simple name parses version and description`() {
        val file = MigrationFile("1_init.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(1L)
        assertThat(file.description).isEqualTo("init")
    }

    @Test
    fun `valid large numeric version with underscore in description`() {
        // The version group is (\d+); the FIRST underscore terminates it,
        // remaining underscores belong to the description.
        val file = MigrationFile("20230101_add_users.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(20230101L)
        assertThat(file.description).isEqualTo("add_users")
    }

    @Test
    fun `leading zeros in version collapse via toLong`() {
        val file = MigrationFile("001_a.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(1L)
        assertThat(file.description).isEqualTo("a")
    }

    @Test
    fun `zero version is valid`() {
        val file = MigrationFile("0_init.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(0L)
        assertThat(file.description).isEqualTo("init")
    }

    @Test
    fun `description charset allows dash dot underscore and digits`() {
        // Description class is [A-Za-z0-9._-]; the trailing .sql is stripped by
        // regex backtracking even though '.' is a valid description character.
        val file = MigrationFile("1_add-users_table.v2.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(1L)
        assertThat(file.description).isEqualTo("add-users_table.v2")
    }

    @Test
    fun `surrounding whitespace is trimmed before parsing`() {
        // parseFileName trims first, and the regex also tolerates \s* on both ends.
        val file = MigrationFile("  2_second.sql  ", "SELECT 1")
        assertThat(file.version).isEqualTo(2L)
        assertThat(file.description).isEqualTo("second")
    }

    @Test
    fun `version at Long MAX_VALUE is valid`() {
        val file = MigrationFile("9223372036854775807_x.sql", "SELECT 1")
        assertThat(file.version).isEqualTo(Long.MAX_VALUE)
        assertThat(file.description).isEqualTo("x")
    }

    // ========================================================================================
    // invalid filenames – regex mismatch => "Migration filename must be ..."
    // ========================================================================================

    @Test
    fun `missing version prefix is rejected`() {
        val ex = assertFailsWith<IllegalStateException> {
            MigrationFile("init.sql", "SELECT 1")
        }
        assertThat(ex.message).isEqualTo("Migration filename must be <version>_<name>.sql, got init.sql")
    }

    @Test
    fun `version without underscore separator is rejected`() {
        val ex = assertFailsWith<IllegalStateException> {
            MigrationFile("1.sql", "SELECT 1")
        }
        assertThat(ex.message).isEqualTo("Migration filename must be <version>_<name>.sql, got 1.sql")
    }

    @Test
    fun `empty description is rejected`() {
        // "1_.sql": after "1_", the description group needs >=1 char AND a trailing
        // ".sql", but only ".sql" (4 chars) remains, so no match.
        val ex = assertFailsWith<IllegalStateException> {
            MigrationFile("1_.sql", "SELECT 1")
        }
        assertThat(ex.message).isEqualTo("Migration filename must be <version>_<name>.sql, got 1_.sql")
    }

    @Test
    fun `wrong extension is rejected`() {
        val ex = assertFailsWith<IllegalStateException> {
            MigrationFile("1_init.txt", "SELECT 1")
        }
        assertThat(ex.message).isEqualTo("Migration filename must be <version>_<name>.sql, got 1_init.txt")
    }

    // ========================================================================================
    // invalid filenames – version overflow => "Invalid version prefix ..."
    // ========================================================================================

    @Test
    fun `version overflow beyond Long is rejected with distinct message`() {
        // The regex matches (all digits), but toLongOrNull returns null for a
        // 20-digit number, producing the SECOND error branch.
        val ex = assertFailsWith<IllegalStateException> {
            MigrationFile("99999999999999999999_x.sql", "SELECT 1")
        }
        assertThat(ex.message)
            .isEqualTo("Invalid version prefix in migration filename: 99999999999999999999_x.sql")
    }

    // ========================================================================================
    // checksum – content.hashCode().toString()
    // ========================================================================================

    @Test
    fun `identical content produces identical checksum`() {
        val a = MigrationFile("1_a.sql", "SELECT 1")
        val b = MigrationFile("2_b.sql", "SELECT 1")
        // checksum depends only on content, not on the filename.
        assertThat(a.checksum).isEqualTo(b.checksum)
    }

    @Test
    fun `changed content produces different checksum`() {
        val a = MigrationFile("1_a.sql", "SELECT 1")
        val b = MigrationFile("1_a.sql", "SELECT 2")
        assertThat(a.checksum).isNotEqualTo(b.checksum)
    }

    @Test
    fun `empty content checksum is the String hashCode of empty string`() {
        // "".hashCode() == 0 in Kotlin/Java, so the checksum is exactly "0".
        val file = MigrationFile("1_init.sql", "")
        assertThat(file.checksum).isEqualTo("0")
    }

    @Test
    fun `checksum equals the raw content hashCode as string`() {
        val content = "CREATE TABLE t (id INT);"
        val file = MigrationFile("1_create.sql", content)
        assertThat(file.checksum).isEqualTo(content.hashCode().toString())
    }

    // ========================================================================================
    // CASE-SENSITIVITY – uppercase extension
    // ========================================================================================

    @Test
    fun `uppercase SQL extension is accepted by the name parser`() {
        // The disk scanner accepts `.sql` case-insensitively; the parser matches the extension
        // case-insensitively too, so a file legitimately named "1_init.SQL" parses like "1_init.sql".
        val f = MigrationFile("1_init.SQL", "SELECT 1")
        assertThat(f.version).isEqualTo(1L)
        assertThat(f.description).isEqualTo("init")
    }
}
