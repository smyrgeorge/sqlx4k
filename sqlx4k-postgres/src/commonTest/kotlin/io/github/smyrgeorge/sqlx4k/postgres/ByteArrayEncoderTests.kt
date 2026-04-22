package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import kotlin.test.Test

class ByteArrayEncoderTests {

    private fun column(value: String?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal = 0, name = "b", type = "bytea", value = value)

    @Test
    fun `encode empty byte array to bytea prefix`() {
        assertThat(ByteArrayEncoder.encode(ByteArray(0))).isEqualTo("\\x")
    }

    @Test
    fun `encode single byte`() {
        assertAll {
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x00))).isEqualTo("\\x00")
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x7F))).isEqualTo("\\x7f")
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0x80.toByte()))).isEqualTo("\\x80")
            assertThat(ByteArrayEncoder.encode(byteArrayOf(0xFF.toByte()))).isEqualTo("\\xff")
        }
    }

    @Test
    fun `encode multi-byte sequence`() {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        assertThat(ByteArrayEncoder.encode(payload)).isEqualTo("\\xdeadbeef")
    }

    @Test
    fun `encode full byte range 0x00 to 0xFF`() {
        val payload = ByteArray(256) { it.toByte() }
        val encoded = ByteArrayEncoder.encode(payload) as String
        assertAll {
            assertThat(encoded.length).isEqualTo(2 + 512)
            assertThat(encoded.startsWith("\\x")).isEqualTo(true)
            assertThat(encoded.substring(2, 4)).isEqualTo("00")
            assertThat(encoded.substring(encoded.length - 2)).isEqualTo("ff")
        }
    }

    @Test
    fun `encode preserves embedded nulls and zeros`() {
        val payload = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x02, 0x00)
        assertThat(ByteArrayEncoder.encode(payload)).isEqualTo("\\x000100000200")
    }

    @Test
    fun `decode bytea hex prefix to byte array`() {
        val col = column("\\xdeadbeef")
        assertThat(ByteArrayEncoder.decode(col).toList()).isEqualTo(
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toList()
        )
    }

    @Test
    fun `decode empty bytea`() {
        val col = column("\\x")
        assertThat(ByteArrayEncoder.decode(col).toList()).isEqualTo(ByteArray(0).toList())
    }

    @Test
    fun `encode then decode roundtrips through a synthetic column`() {
        val payload = ByteArray(256) { it.toByte() }
        val encoded = ByteArrayEncoder.encode(payload) as String
        // Simulate what the driver would surface: the text form of a bytea value.
        val decoded = ByteArrayEncoder.decode(column(encoded))
        assertThat(decoded.toList()).isEqualTo(payload.toList())
    }
}
