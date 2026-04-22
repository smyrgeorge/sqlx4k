package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.impl.types.SqlRawLiteral
import kotlin.test.Test

class ByteArrayEncoderTests {

    private fun column(value: String?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal = 0, name = "b", type = "VARBINARY", value = value)

    @Test
    fun `encode empty byte array to X prefix`() {
        assertThat(ByteArrayEncoder.encode(ByteArray(0))).isEqualTo(SqlRawLiteral("X''"))
    }

    @Test
    fun `encode single byte`() {
        assertAll {
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x00))).isEqualTo(SqlRawLiteral("X'00'"))
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x7F))).isEqualTo(SqlRawLiteral("X'7f'"))
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x80.toByte()))).isEqualTo(SqlRawLiteral("X'80'"))
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0xFF.toByte()))).isEqualTo(SqlRawLiteral("X'ff'"))
        }
    }

    @Test
    fun `encode multi-byte sequence`() {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertThat(ByteArrayEncoder.encode(payload)).isEqualTo(SqlRawLiteral("X'deadbeef'"))
    }

    @Test
    fun `encode full byte range 0x00 to 0xFF`() {
        val payload = ByteArray(256) { it.toByte() }
        val encoded = ByteArrayEncoder.encode(payload) as SqlRawLiteral
        val literal = encoded.sql
        assertAll {
            assertThat(literal.length).isEqualTo(3 + 512)
            assertThat(literal.startsWith("X'")).isEqualTo(true)
            assertThat(literal.endsWith("'")).isEqualTo(true)
            assertThat(literal.substring(2, 4)).isEqualTo("00")
            assertThat(literal.substring(literal.length - 3, literal.length - 1)).isEqualTo("ff")
        }
    }

    @Test
    fun `encode preserves embedded nulls and zeros`() {
        val payload = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x02, 0x00)
        assertThat(ByteArrayEncoder.encode(payload)).isEqualTo(SqlRawLiteral("X'000100000200'"))
    }

    @Test
    fun `decode hex text column to byte array`() {
        val col = column("deadbeef")
        assertThat(ByteArrayEncoder.decode(col).toList()).isEqualTo(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toList()
        )
    }

    @Test
    fun `decode empty hex column`() {
        val col = column("")
        assertThat(ByteArrayEncoder.decode(col).toList()).isEqualTo(ByteArray(0).toList())
    }

    @Test
    fun `encode then decode roundtrips through a synthetic column`() {
        val payload = ByteArray(256) { it.toByte() }
        val literal = (ByteArrayEncoder.encode(payload) as SqlRawLiteral).sql
        // Strip the X'…' wrapping to simulate what HEX() would surface.
        val hex = literal.substring(2, literal.length - 1)
        val decoded = ByteArrayEncoder.decode(column(hex))
        assertThat(decoded.toList()).isEqualTo(payload.toList())
    }
}
