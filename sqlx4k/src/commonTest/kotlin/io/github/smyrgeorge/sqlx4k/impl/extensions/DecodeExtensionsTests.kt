package io.github.smyrgeorge.sqlx4k.impl.extensions

import assertk.assertThat
import assertk.assertions.*
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

class DecodeExtensionsTests {

    // Small enum used to exercise `asEnum` / `String.toEnum`.
    enum class DecodeColor { RED, GREEN, BLUE }

    // ---- Column builders --------------------------------------------------------------------

    private fun col(value: String?, type: String = "text"): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal = 0, name = "c", type = type, value = value)

    private fun colBytes(bytes: ByteArray?): ResultSet.Row.Column =
        ResultSet.Row.Column(ordinal = 0, name = "c", type = "bytea", value = null, bytes = bytes)

    // ========================================================================================
    // Numeric conversions
    // ========================================================================================

    @Test
    fun `asInt parses positive and negative integers`() {
        assertThat(col("42").asInt()).isEqualTo(42)
        assertThat(col("-7").asInt()).isEqualTo(-7)
    }

    @Test
    fun `asIntOrNull returns null for null column`() {
        assertThat(col(null).asIntOrNull()).isNull()
    }

    @Test
    fun `asIntOrNull parses present value`() {
        assertThat(col("100").asIntOrNull()).isEqualTo(100)
    }

    @Test
    fun `asInt throws on invalid value`() {
        assertFailsWith<NumberFormatException> { col("abc").asInt() }
    }

    @Test
    fun `asIntOrNull still throws on invalid non-null value`() {
        // OrNull only guards against a null column, not against an unparseable value.
        assertFailsWith<NumberFormatException> { col("abc").asIntOrNull() }
    }

    @Test
    fun `asLong parses values`() {
        assertThat(col("9999999999").asLong()).isEqualTo(9999999999L)
        assertThat(col("-1").asLong()).isEqualTo(-1L)
    }

    @Test
    fun `asLongOrNull returns null for null column`() {
        assertThat(col(null).asLongOrNull()).isNull()
    }

    @Test
    fun `asLong throws on invalid value`() {
        assertFailsWith<NumberFormatException> { col("x").asLong() }
    }

    @Test
    fun `asShort parses value`() {
        assertThat(col("100").asShort()).isEqualTo(100.toShort())
    }

    @Test
    fun `asShortOrNull returns null for null column`() {
        assertThat(col(null).asShortOrNull()).isNull()
    }

    @Test
    fun `asShort throws on overflow`() {
        // 40000 is outside the Short range (-32768..32767).
        assertFailsWith<NumberFormatException> { col("40000").asShort() }
    }

    @Test
    fun `asDouble parses value`() {
        assertThat(col("3.14").asDouble()).isEqualTo(3.14)
    }

    @Test
    fun `asDoubleOrNull returns null for null column`() {
        assertThat(col(null).asDoubleOrNull()).isNull()
    }

    @Test
    fun `asDouble throws on invalid value`() {
        assertFailsWith<NumberFormatException> { col("abc").asDouble() }
    }

    @Test
    fun `asFloat parses value`() {
        assertThat(col("2.5").asFloat()).isEqualTo(2.5f)
    }

    @Test
    fun `asFloatOrNull returns null for null column`() {
        assertThat(col(null).asFloatOrNull()).isNull()
    }

    @Test
    fun `asUInt parses value`() {
        assertThat(col("42").asUInt()).isEqualTo(42u)
    }

    @Test
    fun `asULong parses value`() {
        assertThat(col("42").asULong()).isEqualTo(42uL)
    }

    // ========================================================================================
    // asBoolean / asBooleanOrNull
    // ========================================================================================

    @Test
    fun `asBoolean recognizes true values`() {
        assertThat(col("1").asBoolean()).isEqualTo(true)
        assertThat(col("t").asBoolean()).isEqualTo(true)
        assertThat(col("true").asBoolean()).isEqualTo(true)
    }

    @Test
    fun `asBoolean recognizes false values`() {
        assertThat(col("0").asBoolean()).isEqualTo(false)
        assertThat(col("f").asBoolean()).isEqualTo(false)
        assertThat(col("false").asBoolean()).isEqualTo(false)
    }

    @Test
    fun `asBoolean throws on unknown value`() {
        val e = assertFailsWith<IllegalStateException> { col("yes").asBoolean() }
        assertThat(e.message!!).isEqualTo("Invalid boolean value: yes")
    }

    @Test
    fun `asBoolean is case sensitive and rejects uppercase TRUE`() {
        assertFailsWith<IllegalStateException> { col("TRUE").asBoolean() }
    }

    @Test
    fun `asBooleanOrNull returns null for null column`() {
        assertThat(col(null).asBooleanOrNull()).isNull()
    }

    @Test
    fun `asBooleanOrNull parses present value`() {
        assertThat(col("t").asBooleanOrNull()).isEqualTo(true)
    }

    @Test
    fun `asBooleanOrNull still throws on invalid non-null value`() {
        assertFailsWith<IllegalStateException> { col("x").asBooleanOrNull() }
    }

    // ========================================================================================
    // asChar / asCharOrNull
    // ========================================================================================

    @Test
    fun `asChar returns the single character`() {
        assertThat(col("a").asChar()).isEqualTo('a')
    }

    @Test
    fun `asChar throws on empty string`() {
        val e = assertFailsWith<IllegalArgumentException> { col("").asChar() }
        assertThat(e.message!!).isEqualTo("Invalid char value: ")
    }

    @Test
    fun `asChar throws on multi-character string`() {
        val e = assertFailsWith<IllegalArgumentException> { col("ab").asChar() }
        assertThat(e.message!!).isEqualTo("Invalid char value: ab")
    }

    @Test
    fun `asCharOrNull returns null for null column`() {
        assertThat(col(null).asCharOrNull()).isNull()
    }

    @Test
    fun `asCharOrNull returns the single character`() {
        assertThat(col("z").asCharOrNull()).isEqualTo('z')
    }

    // ========================================================================================
    // asEnum / String.toEnum
    // ========================================================================================

    @Test
    fun `asEnum decodes valid enum names`() {
        assertThat(col("RED").asEnum<DecodeColor>()).isEqualTo(DecodeColor.RED)
        assertThat(col("GREEN").asEnum<DecodeColor>()).isEqualTo(DecodeColor.GREEN)
    }

    @Test
    fun `asEnum raises SQLError with CannotDecodeEnumValue for unknown name`() {
        val e = assertFailsWith<SQLError> { col("PURPLE").asEnum<DecodeColor>() }
        assertThat(e.code).isEqualTo(SQLError.Code.CannotDecodeEnumValue)
    }

    @Test
    fun `asEnum is case sensitive`() {
        val e = assertFailsWith<SQLError> { col("red").asEnum<DecodeColor>() }
        assertThat(e.code).isEqualTo(SQLError.Code.CannotDecodeEnumValue)
    }

    @Test
    fun `asEnumOrNull returns null for null column`() {
        assertThat(col(null).asEnumOrNull<DecodeColor>()).isNull()
    }

    @Test
    fun `toEnum on string decodes valid name`() {
        assertThat("BLUE".toEnum<DecodeColor>()).isEqualTo(DecodeColor.BLUE)
    }

    @Test
    fun `toEnum on string raises SQLError with CannotDecodeEnumValue for unknown name`() {
        val e = assertFailsWith<SQLError> { "nope".toEnum<DecodeColor>() }
        assertThat(e.code).isEqualTo(SQLError.Code.CannotDecodeEnumValue)
    }

    // ========================================================================================
    // asByteArray / asByteArrayOrNull
    // ========================================================================================

    @Test
    fun `asByteArray decodes postgres hex with backslash-x prefix`() {
        // "\x48656c6c6f" is the Postgres bytea text encoding of "Hello".
        assertThat(col("\\x48656c6c6f").asByteArray().toList())
            .isEqualTo("Hello".encodeToByteArray().toList())
    }

    @Test
    fun `asByteArray decodes hex without prefix`() {
        assertThat(col("48656c6c6f").asByteArray().toList())
            .isEqualTo("Hello".encodeToByteArray().toList())
    }

    @Test
    fun `asByteArray returns raw bytes when present`() {
        val raw = byteArrayOf(1, 2, 3)
        assertThat(colBytes(raw).asByteArray().toList()).isEqualTo(raw.toList())
    }

    @Test
    fun `asByteArrayOrNull returns null when both value and bytes are null`() {
        assertThat(col(null).asByteArrayOrNull()).isNull()
    }

    @Test
    fun `asByteArrayOrNull returns raw bytes when present`() {
        val raw = byteArrayOf(9, 8, 7)
        assertThat(colBytes(raw).asByteArrayOrNull()!!.toList()).isEqualTo(raw.toList())
    }

    // ========================================================================================
    // asLocalDate / asLocalTime / asLocalDateTime
    // ========================================================================================

    @Test
    fun `asLocalDate parses ISO date`() {
        assertThat(col("2023-01-15").asLocalDate()).isEqualTo(LocalDate(2023, 1, 15))
    }

    @Test
    fun `asLocalDateOrNull returns null for null column`() {
        assertThat(col(null).asLocalDateOrNull()).isNull()
    }

    @Test
    fun `asLocalDate throws on invalid date`() {
        assertFailsWith<IllegalArgumentException> { col("not-a-date").asLocalDate() }
    }

    @Test
    fun `asLocalTime parses ISO time`() {
        assertThat(col("12:34:56").asLocalTime()).isEqualTo(LocalTime(12, 34, 56))
    }

    @Test
    fun `asLocalTimeOrNull returns null for null column`() {
        assertThat(col(null).asLocalTimeOrNull()).isNull()
    }

    @Test
    fun `asLocalDateTime parses space-separated datetime`() {
        assertThat(col("2023-01-15 12:34:56").asLocalDateTime())
            .isEqualTo(LocalDateTime(2023, 1, 15, 12, 34, 56))
    }

    @Test
    fun `asLocalDateTime parses datetime with 6-digit fraction`() {
        assertThat(col("2023-01-15 12:34:56.123456").asLocalDateTime())
            .isEqualTo(LocalDateTime(2023, 1, 15, 12, 34, 56, 123456000))
    }

    @Test
    fun `asLocalDateTimeOrNull returns null for null column`() {
        assertThat(col(null).asLocalDateTimeOrNull()).isNull()
    }

    @Test
    fun `asLocalDateTime accepts the T separator`() {
        // The ISO-8601 'T' separator is normalized to a space, so it parses like the space form.
        assertThat(col("2023-01-15T12:34:56").asLocalDateTime())
            .isEqualTo(col("2023-01-15 12:34:56").asLocalDateTime())
    }

    // ========================================================================================
    // asInstant – offset parsing
    // ========================================================================================

    @Test
    fun `asInstant assumes UTC when no offset is present`() {
        assertThat(col("2023-01-01 12:34:56").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
    }

    @Test
    fun `asInstant handles uppercase Z offset`() {
        assertThat(col("2023-01-01 12:34:56Z").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
    }

    @Test
    fun `asInstant handles the postgres +00 offset`() {
        assertThat(col("2025-03-25 07:31:43.330068+00", "timestamptz").asInstant())
            .isEqualTo(Instant.parse("2025-03-25T07:31:43.330068Z"))
    }

    @Test
    fun `asInstant handles hours-only +02 offset`() {
        assertThat(col("2023-01-01 12:34:56+02").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56+02:00"))
    }

    @Test
    fun `asInstant handles compact +0230 offset`() {
        assertThat(col("2023-01-01 12:34:56+0230").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56+02:30"))
    }

    @Test
    fun `asInstant handles colon +02_30 offset`() {
        assertThat(col("2023-01-01 12:34:56+02:30").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56+02:30"))
    }

    @Test
    fun `asInstant handles negative -05_00 offset`() {
        assertThat(col("2023-01-01 12:34:56-05:00").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56-05:00"))
    }

    @Test
    fun `asInstant +02 and +0230 produce distinct instants`() {
        assertThat(col("2023-01-01 12:34:56+02").asInstant())
            .isNotEqualTo(col("2023-01-01 12:34:56+0230").asInstant())
    }

    // ========================================================================================
    // asInstant – separators
    // ========================================================================================

    @Test
    fun `asInstant accepts the T separator`() {
        assertThat(col("2023-01-01T12:34:56Z").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
    }

    // ========================================================================================
    // asInstant – fractional seconds
    // ========================================================================================

    @Test
    fun `asInstant pads a short fraction to microseconds`() {
        // ".123" is padded to ".123000".
        assertThat(col("2023-01-01 12:34:56.123Z").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56.123Z"))
    }

    @Test
    fun `asInstant preserves a full 6-digit fraction`() {
        assertThat(col("2023-01-01 12:34:56.123456Z").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56.123456Z"))
    }

    @Test
    fun `asInstant truncates an over-6-digit fraction without rounding`() {
        // ".1234569" -> truncated to ".123456" (NOT rounded to ".123457").
        val result = col("2023-01-01 12:34:56.1234569Z").asInstant()
        assertThat(result).isEqualTo(Instant.parse("2023-01-01T12:34:56.123456Z"))
        assertThat(result).isNotEqualTo(Instant.parse("2023-01-01T12:34:56.123457Z"))
    }

    @Test
    fun `asInstant with no fraction has zero nanoseconds`() {
        val result = col("2023-01-01 12:34:56Z").asInstant()
        assertThat(result).isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
        assertThat(result.nanosecondsOfSecond).isEqualTo(0)
    }

    // ========================================================================================
    // asInstant – errors
    // ========================================================================================

    @Test
    fun `asInstant throws when offset hours exceed 18`() {
        val e = assertFailsWith<IllegalArgumentException> { col("2023-01-01 12:34:56+19").asInstant() }
        assertThat(e.message!!).contains("out of range")
    }

    @Test
    fun `asInstant throws when offset minutes exceed 59`() {
        val e = assertFailsWith<IllegalArgumentException> { col("2023-01-01 12:34:56+02:60").asInstant() }
        assertThat(e.message!!).contains("out of range")
    }

    @Test
    fun `asInstant throws on single-digit offset`() {
        // "+2" is not accepted by the regex (needs two digits) -> whole match fails.
        val e = assertFailsWith<IllegalStateException> { col("2023-01-01 12:34:56+2").asInstant() }
        assertThat(e.message!!).contains("Invalid timestamp")
    }

    @Test
    fun `asInstant throws on trailing-colon offset`() {
        val e = assertFailsWith<IllegalStateException> { col("2023-01-01 12:34:56+02:").asInstant() }
        assertThat(e.message!!).contains("Invalid timestamp")
    }

    @Test
    fun `asInstant throws on non-timestamp string`() {
        val e = assertFailsWith<IllegalStateException> { col("not-a-timestamp").asInstant() }
        assertThat(e.message!!).isEqualTo("Invalid timestamp with optional offset: 'not-a-timestamp'")
    }

    @Test
    fun `asInstant handles a lowercase z offset as UTC`() {
        assertThat(col("2023-01-01 12:34:56z").asInstant())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
    }

    @Test
    fun `asInstantOrNull returns null for null column`() {
        assertThat(col(null).asInstantOrNull()).isNull()
    }

    @Test
    fun `asInstantOrNull parses present value`() {
        assertThat(col("2023-01-01 12:34:56Z").asInstantOrNull())
            .isEqualTo(Instant.parse("2023-01-01T12:34:56Z"))
    }
}
