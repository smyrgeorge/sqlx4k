package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.test.generated.DocumentAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.test.generated.applyInsertResult
import io.github.smyrgeorge.sqlx4k.processor.test.generated.applyUpdateResult
import io.github.smyrgeorge.sqlx4k.processor.test.generated.delete
import io.github.smyrgeorge.sqlx4k.processor.test.generated.insert
import io.github.smyrgeorge.sqlx4k.processor.test.generated.update
import io.github.smyrgeorge.sqlx4k.processor.util.Document
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.Setting
import io.github.smyrgeorge.sqlx4k.processor.util.render
import io.github.smyrgeorge.sqlx4k.processor.util.renderValues
import kotlin.test.Test

/**
 * Tests for the statements generated for `@Version`-ed entities (optimistic locking).
 */
class OptimisticLockStatementTests {

    private val doc = Document(id = 1, title = "Title", body = "Body", version = 3)

    // ==================== INSERT ====================

    @Test
    fun `insert writes the version as a regular column`() {
        val sql = doc.insert().render()

        assertThat(sql).contains("insert into documents(title, body, version)")
        assertThat(doc.insert().renderValues()).containsExactly("Title", "Body", 3L)
    }

    @Test
    fun `insert reads the version back via RETURNING`() {
        assertThat(doc.insert().render()).contains("returning id, version")
    }

    @Test
    fun `insert with an application-provided id returns only the version`() {
        val setting = Setting(name = "theme", content = "dark")
        val sql = setting.insert().render()

        assertThat(sql).contains("insert into settings(name, content, revision)")
        assertThat(sql).contains("returning revision")
        assertThat(setting.insert().renderValues()).containsExactly("theme", "dark", 0)
    }

    @Test
    fun `applyInsertResult merges the stored version`() {
        val fresh = Document(id = 0, title = "T", body = "B")
        val applied = fresh.applyInsertResult(row("id" to "7", "version" to "0"), ValueEncoderRegistry.EMPTY)

        assertThat(applied.id).isEqualTo(7L)
        assertThat(applied.version).isEqualTo(0L)
    }

    // ==================== UPDATE ====================

    @Test
    fun `update increments the version in SQL instead of binding it`() {
        val sql = doc.update().render()

        assertThat(sql).contains("version = version + 1")
        assertThat(sql).doesNotContain("set version =")
    }

    @Test
    fun `update matches the row on id and current version`() {
        val sql = doc.update().render()

        assertThat(sql).contains("where id = ")
        assertThat(sql).contains(" and version = ")
        // SET values first, then id, then the expected (current) version — nothing else.
        assertThat(doc.update().renderValues()).containsExactly("Title", "Body", 1L, 3L)
    }

    @Test
    fun `update reads id and version back via RETURNING`() {
        assertThat(doc.update().render()).contains("returning id, version")
    }

    @Test
    fun `update honors a custom version column name and Int type`() {
        val setting = Setting(name = "theme", content = "dark", revision = 7)
        val sql = setting.update().render()

        assertThat(sql).contains("set content = ")
        assertThat(sql).contains("revision = revision + 1")
        assertThat(sql).contains("where name = ")
        assertThat(sql).contains(" and revision = ")
        assertThat(sql).contains("returning name, revision")
        assertThat(setting.update().renderValues()).containsExactly("dark", "theme", 7)
    }

    @Test
    fun `applyUpdateResult merges the incremented version`() {
        val applied = doc.applyUpdateResult(row("id" to "1", "version" to "4"), ValueEncoderRegistry.EMPTY)

        assertThat(applied.id).isEqualTo(1L)
        assertThat(applied.version).isEqualTo(4L)
        assertThat(applied.title).isEqualTo("Title")
    }

    // ==================== DELETE ====================

    @Test
    fun `delete matches the row on id and current version`() {
        val sql = doc.delete().render()

        assertThat(sql).contains("delete from documents where id = ")
        assertThat(sql).contains(" and version = ")
        assertThat(doc.delete().renderValues()).containsExactly(1L, 3L)
    }

    // ==================== BATCH UPDATE ====================

    @Test
    fun `batch update carries the version in the VALUES list and joins on it`() {
        val docs = listOf(
            Document(id = 1, title = "A", body = "a", version = 1),
            Document(id = 2, title = "B", body = "b", version = 5),
        )
        val sql = docs.update().render()

        assertThat(sql).contains("as v(id, title, body, version)")
        assertThat(sql).contains("where t.id = v.id and t.version = v.version")
        assertThat(sql).contains("version = t.version + 1")
        assertThat(sql).doesNotContain("version = v.version,")
        assertThat(sql).contains("returning t.id, t.version")
    }

    @Test
    fun `batch update binds id, SET values, then the current version per row`() {
        val docs = listOf(
            Document(id = 1, title = "A", body = "a", version = 1),
            Document(id = 2, title = "B", body = "b", version = 5),
        )

        assertThat(docs.update().renderValues()).containsExactly(1L, "A", "a", 1L, 2L, "B", "b", 5L)
    }

    // ==================== ROW MAPPER ====================

    @Test
    fun `row mapper reads the version like any other column`() {
        val mapped = DocumentAutoRowMapper.map(
            row("id" to "9", "title" to "T", "body" to "B", "version" to "12"),
            ValueEncoderRegistry.EMPTY,
        )

        assertThat(mapped).isEqualTo(Document(id = 9, title = "T", body = "B", version = 12))
    }
}
