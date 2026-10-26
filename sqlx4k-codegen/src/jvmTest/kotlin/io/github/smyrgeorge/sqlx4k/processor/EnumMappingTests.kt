package io.github.smyrgeorge.sqlx4k.processor

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.processor.test.generated.TicketAutoRowMapper
import io.github.smyrgeorge.sqlx4k.processor.util.MockQueryExecutor.Companion.row
import io.github.smyrgeorge.sqlx4k.processor.util.Ticket
import io.github.smyrgeorge.sqlx4k.processor.util.TicketStatus
import kotlin.test.Test

/**
 * Enum properties are read back the way they are written: through the encoder registered for the enum, or by name.
 */
class EnumMappingTests {

    private object LowercaseStatus : ValueEncoder<TicketStatus> {
        override fun encode(value: TicketStatus): Any = value.name.lowercase()
        override fun decode(value: ResultSet.Row.Column): TicketStatus = TicketStatus.valueOf(value.asString().uppercase())
    }

    @Test
    fun `an enum is read through its registered encoder`() {
        val registry = ValueEncoderRegistry().register(LowercaseStatus)
        val row = row("id" to "1", "status" to "closed", "previous" to "open")
        assertThat(TicketAutoRowMapper.map(row, registry))
            .isEqualTo(Ticket(id = 1, status = TicketStatus.CLOSED, previous = TicketStatus.OPEN))
    }

    @Test
    fun `an enum without an encoder is read by name`() {
        val row = row("id" to "2", "status" to "OPEN", "previous" to null)
        assertThat(TicketAutoRowMapper.map(row, ValueEncoderRegistry.EMPTY))
            .isEqualTo(Ticket(id = 2, status = TicketStatus.OPEN, previous = null))
    }
}
