package io.github.smyrgeorge.sqlx4k.impl.migrate.utils

import assertk.assertThat
import assertk.assertions.containsExactly
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

class FileTests {

    @Test
    fun `reads files larger than one buffer segment completely`() {
        val dir = Path(SystemTemporaryDirectory, "sqlx4k-file-tests-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        val file = Path(dir, "1_large.sql")
        // Well past the 8 KiB segment size, with multi-byte UTF-8 characters crossing segment boundaries.
        val sql = (1..2_000).joinToString("\n") { "insert into t(id, name) values ($it, 'ünïcödé-$it');" }
        try {
            SystemFileSystem.sink(file).buffered().use { it.writeString(sql) }
            assertThat(readSqlFilesFromDisk(dir.toString())).containsExactly("1_large.sql" to sql)
        } finally {
            SystemFileSystem.delete(file, mustExist = false)
            SystemFileSystem.delete(dir, mustExist = false)
        }
    }
}
