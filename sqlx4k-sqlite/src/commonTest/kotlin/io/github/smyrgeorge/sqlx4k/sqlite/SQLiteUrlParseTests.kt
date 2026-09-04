package io.github.smyrgeorge.sqlx4k.sqlite

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SQLiteUrlParseTests {

    @Test
    fun `in-memory url`() {
        val u = SQLiteUrl.parse("sqlite::memory:")
        assertAll {
            assertThat(u.database).isEqualTo(":memory:")
            assertThat(u.query).isNull()
            assertThat(u.params).isEmpty()
            assertThat(u.isInMemory).isTrue()
            assertThat(u.isReadOnly).isFalse()
        }
    }

    @Test
    fun `relative path with one or two slashes`() {
        assertAll {
            assertThat(SQLiteUrl.parse("sqlite:data.db").database).isEqualTo("data.db")
            assertThat(SQLiteUrl.parse("sqlite://data.db").database).isEqualTo("data.db")
            assertThat(SQLiteUrl.parse("sqlite:data.db").isInMemory).isFalse()
        }
    }

    @Test
    fun `absolute path`() {
        val u = SQLiteUrl.parse("sqlite:///home/tech/Music/video/test.db")
        assertAll {
            assertThat(u.database).isEqualTo("/home/tech/Music/video/test.db")
            assertThat(u.query).isNull()
        }
    }

    @Test
    fun `bare path without scheme`() {
        assertAll {
            assertThat(SQLiteUrl.parse("test.db").database).isEqualTo("test.db")
            assertThat(SQLiteUrl.parse("/abs/test.db").database).isEqualTo("/abs/test.db")
        }
    }

    @Test
    fun `query string is split off the path`() {
        // The exact URL from https://github.com/smyrgeorge/sqlx4k/issues/171
        val u = SQLiteUrl.parse("sqlite:///home/tech/Music/video/test.db?mode=rwc")
        assertAll {
            assertThat(u.database).isEqualTo("/home/tech/Music/video/test.db")
            assertThat(u.query).isEqualTo("mode=rwc")
            assertThat(u.params).containsOnly("mode" to "rwc")
            assertThat(u.mode).isEqualTo("rwc")
            assertThat(u.isInMemory).isFalse()
            assertThat(u.isReadOnly).isFalse()
        }
    }

    @Test
    fun `mode ro is read-only`() {
        val u = SQLiteUrl.parse("sqlite://data.db?mode=ro")
        assertAll {
            assertThat(u.database).isEqualTo("data.db")
            assertThat(u.isReadOnly).isTrue()
            assertThat(u.isInMemory).isFalse()
        }
    }

    @Test
    fun `mode memory is in-memory`() {
        val u = SQLiteUrl.parse("sqlite:///tmp/whatever.db?mode=memory")
        assertAll {
            assertThat(u.database).isEqualTo("/tmp/whatever.db")
            assertThat(u.isInMemory).isTrue()
        }
    }

    @Test
    fun `in-memory with shared cache`() {
        val u = SQLiteUrl.parse("sqlite::memory:?cache=shared")
        assertAll {
            assertThat(u.database).isEqualTo(":memory:")
            assertThat(u.query).isEqualTo("cache=shared")
            assertThat(u.params).containsOnly("cache" to "shared")
            assertThat(u.isInMemory).isTrue()
        }
    }

    @Test
    fun `file uri prefix is kept and still detected as in-memory`() {
        val u = SQLiteUrl.parse("sqlite:file::memory:?cache=shared")
        assertAll {
            assertThat(u.database).isEqualTo("file::memory:")
            assertThat(u.isInMemory).isTrue()
        }
    }

    @Test
    fun `multiple parameters and last duplicate wins`() {
        val u = SQLiteUrl.parse("sqlite://a.db?mode=ro&cache=shared&vfs=unix&mode=rw")
        assertAll {
            assertThat(u.database).isEqualTo("a.db")
            assertThat(u.query).isEqualTo("mode=ro&cache=shared&vfs=unix&mode=rw")
            assertThat(u.params).containsOnly("mode" to "rw", "cache" to "shared", "vfs" to "unix")
            assertThat(u.isReadOnly).isFalse()
        }
    }

    @Test
    fun `parameter without value and empty segments`() {
        val u = SQLiteUrl.parse("sqlite://a.db?immutable&&mode=ro&")
        assertThat(u.params).containsOnly("immutable" to "", "mode" to "ro")
    }

    @Test
    fun `empty query is treated as no query`() {
        val u = SQLiteUrl.parse("sqlite://a.db?")
        assertAll {
            assertThat(u.database).isEqualTo("a.db")
            assertThat(u.query).isNull()
            assertThat(u.params).isEmpty()
        }
    }

    @Test
    fun `only the first question mark splits`() {
        val u = SQLiteUrl.parse("sqlite://a.db?vfs=a?b")
        assertAll {
            assertThat(u.database).isEqualTo("a.db")
            assertThat(u.params).containsOnly("vfs" to "a?b")
        }
    }

    @Test
    fun `unknown mode is rejected`() {
        val e = assertFailsWith<SQLError> { SQLiteUrl.parse("sqlite://a.db?mode=bogus") }
        assertAll {
            assertThat(e.code).isEqualTo(SQLError.Code.Pool)
            assertThat(e.message!!.contains("mode")).isTrue()
        }
    }

    @Test
    fun `unknown cache is rejected`() {
        val e = assertFailsWith<SQLError> { SQLiteUrl.parse("sqlite://a.db?cache=bogus") }
        assertAll {
            assertThat(e.code).isEqualTo(SQLError.Code.Pool)
            assertThat(e.message!!.contains("cache")).isTrue()
        }
    }

    @Test
    fun `unknown parameters are kept by the parser`() {
        val u = SQLiteUrl.parse("sqlite://a.db?busy_timeout=5000")
        assertThat(u.params).containsOnly("busy_timeout" to "5000")
    }

    @Test
    fun `requireKnownParams accepts the sqlite parameters and rejects others`() {
        SQLiteUrl.parse("sqlite://a.db?mode=ro&cache=shared&immutable=1&vfs=unix").requireKnownParams()
        val e = assertFailsWith<SQLError> { SQLiteUrl.parse("sqlite://a.db?mode=ro&foo=bar").requireKnownParams() }
        assertAll {
            assertThat(e.code).isEqualTo(SQLError.Code.Pool)
            assertThat(e.message!!.contains("foo")).isTrue()
        }
    }
}
