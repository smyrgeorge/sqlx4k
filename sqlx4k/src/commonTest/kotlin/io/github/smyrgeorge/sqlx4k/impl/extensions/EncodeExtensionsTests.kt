@file:OptIn(ExperimentalUuidApi::class)

package io.github.smyrgeorge.sqlx4k.impl.extensions

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.impl.types.TypedNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class EncodeExtensionsTests {

    private val empty = ValueEncoderRegistry.EMPTY

    private enum class Color { RED, GREEN }

    private data class Money(val amount: Double, val currency: String)
    private class MoneyEncoder : ValueEncoder<Money> {
        override fun encode(value: Money): Any = "${value.amount}:${value.currency}"
        override fun decode(value: ResultSet.Row.Column): Money = error("not needed")
    }

    // Encoder whose output is itself an Enum (drives the recursive resolution path).
    private data class Wrapper(val color: Color)
    private class WrapperEncoder : ValueEncoder<Wrapper> {
        override fun encode(value: Wrapper): Any = value.color
        override fun decode(value: ResultSet.Row.Column): Wrapper = error("not needed")
    }

    // Two encoders that chain: Outer -> Inner (custom) -> String (multi-hop recursion).
    private data class Inner(val v: Int)
    private data class Outer(val inner: Inner)
    private class InnerEncoder : ValueEncoder<Inner> {
        override fun encode(value: Inner): Any = "inner:${value.v}"
        override fun decode(value: ResultSet.Row.Column): Inner = error("not needed")
    }
    private class OuterEncoder : ValueEncoder<Outer> {
        override fun encode(value: Outer): Any = value.inner
        override fun decode(value: ResultSet.Row.Column): Outer = error("not needed")
    }

    // An enum encoder used to verify a registered encoder takes precedence over the enum `.name`.
    private class ColorEncoder : ValueEncoder<Color> {
        override fun encode(value: Color): Any = "ENCODED-$value"
        override fun decode(value: ResultSet.Row.Column): Color = error("not needed")
    }

    // A type nobody knows how to encode.
    private class Unknown

    /**
     * Builds a `kotlin.time.Instant` from explicit UTC wall-clock fields, mirroring the
     * conversion already used in `decode.kt` so we can pin fields like a single/double-digit
     * year that are awkward to express as an epoch offset.
     */
    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        nanos: Int = 0
    ): Instant {
        val kd = LocalDateTime(year, month, day, hour, minute, second, nanos).toInstant(UtcOffset.ZERO)
        return Instant.fromEpochSeconds(kd.epochSeconds, kd.nanosecondsOfSecond.toLong())
    }

    // ========================================================================================
    // Instant.toTimestampString – fractional seconds
    // ========================================================================================

    @Test
    fun `toTimestampString formats the UTC epoch with zeroed microseconds`() {
        val ts = Instant.fromEpochSeconds(0, 0).toTimestampString()
        assertThat(ts).isEqualTo("1970-01-01 00:00:00.000000")
    }

    @Test
    fun `toTimestampString truncates nanoseconds to six microsecond digits`() {
        val ts = Instant.fromEpochSeconds(0, 123_456_789).toTimestampString()
        assertThat(ts).isEqualTo("1970-01-01 00:00:00.123456")
    }

    @Test
    fun `toTimestampString drops sub-microsecond nanoseconds to zero`() {
        // 999ns / 1000 == 0 (integer-division truncation to microseconds, not rounding).
        val ts = Instant.fromEpochSeconds(0, 999).toTimestampString()
        assertThat(ts).isEqualTo("1970-01-01 00:00:00.000000")
    }

    @Test
    fun `toTimestampString renders exactly one microsecond at the division boundary`() {
        // 1000ns / 1000 == 1 -> ".000001" (pins the integer-division boundary and left-padding).
        val ts = Instant.fromEpochSeconds(0, 1_000).toTimestampString()
        assertThat(ts).isEqualTo("1970-01-01 00:00:00.000001")
    }

    // ========================================================================================
    // Instant.toTimestampString – time zone handling
    // ========================================================================================

    @Test
    fun `toTimestampString shifts local fields by the supplied time zone`() {
        val instant = Instant.fromEpochSeconds(0, 0)
        // Same instant, rendered at UTC vs. a fixed +02:00 offset.
        assertThat(instant.toTimestampString(TimeZone.UTC)).isEqualTo("1970-01-01 00:00:00.000000")
        assertThat(instant.toTimestampString(TimeZone.of("+02:00"))).isEqualTo("1970-01-01 02:00:00.000000")
    }

    // ========================================================================================
    // Instant.toTimestampString – zero-padding of the calendar fields
    // ========================================================================================

    @Test
    fun `toTimestampString zero-pads year month day hour minute and second`() {
        // Year 7 AD with single-digit month/day/hour/minute/second exercises every padStart.
        val ts = utc(year = 7, month = 3, day = 4, hour = 5, minute = 6, second = 7).toTimestampString()
        assertThat(ts).isEqualTo("0007-03-04 05:06:07.000000")
    }

    // ========================================================================================
    // resolveNativeValue – null / TypedNull
    // ========================================================================================

    @Test
    fun `resolveNativeValue returns null for null`() {
        val value: Any? = null
        assertThat(value.resolveNativeValue(empty)).isNull()
    }

    @Test
    fun `resolveNativeValue returns TypedNull unchanged`() {
        val value = TypedNull(String::class)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    // ========================================================================================
    // resolveNativeValue – primitives pass through
    // ========================================================================================

    @Test
    fun `resolveNativeValue passes Int through`() {
        val value: Any = 42
        assertThat(value.resolveNativeValue(empty)).isEqualTo(42)
    }

    @Test
    fun `resolveNativeValue passes Long through`() {
        val value: Any = 9_000_000_000L
        assertThat(value.resolveNativeValue(empty)).isEqualTo(9_000_000_000L)
    }

    @Test
    fun `resolveNativeValue passes Boolean through`() {
        val value: Any = true
        assertThat(value.resolveNativeValue(empty)).isEqualTo(true)
    }

    @Test
    fun `resolveNativeValue passes Double through`() {
        val value: Any = 3.14
        assertThat(value.resolveNativeValue(empty)).isEqualTo(3.14)
    }

    @Test
    fun `resolveNativeValue passes String through by identity`() {
        val value: Any = "hello"
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    @Test
    fun `resolveNativeValue passes ByteArray through by identity`() {
        val value: Any = byteArrayOf(1, 2, 3)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    // ========================================================================================
    // resolveNativeValue – date/time and Uuid pass through
    // ========================================================================================

    @Test
    fun `resolveNativeValue passes Instant through by identity`() {
        val value: Any = Instant.fromEpochSeconds(1_700_000_000, 0)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    @Test
    fun `resolveNativeValue passes LocalDate through by identity`() {
        val value: Any = LocalDate(2025, 3, 25)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    @Test
    fun `resolveNativeValue passes LocalTime through by identity`() {
        val value: Any = LocalTime(7, 31, 43)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    @Test
    fun `resolveNativeValue passes LocalDateTime through by identity`() {
        val value: Any = LocalDateTime(2025, 3, 25, 7, 31, 43)
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    @Test
    fun `resolveNativeValue passes Uuid through by identity`() {
        val value: Any = Uuid.random()
        assertThat(value.resolveNativeValue(empty)).isSameInstanceAs(value)
    }

    // ========================================================================================
    // resolveNativeValue – enums
    // ========================================================================================

    @Test
    fun `resolveNativeValue resolves an enum to its name`() {
        assertThat(Color.GREEN.resolveNativeValue(empty)).isEqualTo("GREEN")
    }

    @Test
    fun `resolveNativeValue uses a registered encoder for an enum type`() {
        // A registered encoder for the enum type takes precedence over the enum's `.name`.
        val registry = ValueEncoderRegistry().register(ColorEncoder())
        assertThat(Color.GREEN.resolveNativeValue(registry)).isEqualTo("ENCODED-GREEN")
        // Without a registered encoder, an enum still resolves to its name.
        assertThat(Color.GREEN.resolveNativeValue(ValueEncoderRegistry.EMPTY)).isEqualTo("GREEN")
    }

    // ========================================================================================
    // resolveNativeValue – custom encoders (including recursive resolution)
    // ========================================================================================

    @Test
    fun `resolveNativeValue resolves a custom type through a registered encoder`() {
        val registry = ValueEncoderRegistry().register(MoneyEncoder())
        assertThat(Money(100.5, "USD").resolveNativeValue(registry)).isEqualTo("100.5:USD")
    }

    @Test
    fun `resolveNativeValue recursively resolves an encoder output that is an enum`() {
        // Wrapper -> (encoder) -> Color.GREEN -> (Enum branch) -> "GREEN".
        val registry = ValueEncoderRegistry().register(WrapperEncoder())
        assertThat(Wrapper(Color.GREEN).resolveNativeValue(registry)).isEqualTo("GREEN")
    }

    @Test
    fun `resolveNativeValue recursively resolves through chained custom encoders`() {
        // Outer -> (encoder) -> Inner -> (encoder) -> "inner:7".
        val registry = ValueEncoderRegistry()
            .register(OuterEncoder())
            .register(InnerEncoder())
        assertThat(Outer(Inner(7)).resolveNativeValue(registry)).isEqualTo("inner:7")
    }

    // ========================================================================================
    // resolveNativeValue – unknown types
    // ========================================================================================

    @Test
    fun `resolveNativeValue passes an unknown type through when no encoder is registered`() {
        val value: Any = Unknown()
        assertThat(value.resolveNativeValue(ValueEncoderRegistry.EMPTY)).isSameInstanceAs(value)
    }
}
