package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PgmqConsumerOptionsTests {

    // pgmq takes the visibility timeout in whole seconds: the consumer counts on the one the server applies.
    @Test
    fun `vtBias is the visibility timeout pgmq applies`() {
        assertThat(PgmqConsumer.Options(queue = "q", vt = 10.seconds).vtBias).isEqualTo(20.seconds)
        assertThat(PgmqConsumer.Options(queue = "q", vt = 1900.milliseconds).vtBias).isEqualTo(3.seconds)
        assertThat(PgmqConsumer.Options(queue = "q", vt = 1500.milliseconds).vtBias).isEqualTo(3.seconds)
    }

    // pgmq notifies on the channel of its (lowercase) table name.
    @Test
    fun `listenChannel names the table of the queue in lowercase`() {
        assertThat(PgmqConsumer.Options(queue = "Orders").listenChannel).isEqualTo("pgmq.q_orders.INSERT")
    }
}

