package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

@Suppress("SqlNoDataSourceInspection")
class CommonPostgreSQLListenNotifyTests(
    private val db: IPostgresSQL
) {

    private fun newChannel(): String = "chan_${Random.nextInt(1000000)}"

    fun `listen on single channel should receive notifications`() = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }

        // Give listener a moment to subscribe
        delay(150)

        (1..3).forEach { i -> db.notify(chan, "hello-$i") }

        withTimeout(2_000) {
            while (true) {
                val size = lock.withLock { received.size }
                if (size >= 3) break else delay(50)
            }
        }

        assertThat(lock.withLock { received.toList() }.size).isEqualTo(3)
        job.cancel()
    }

    fun `listen on multiple channels should route notifications`() = runBlocking {
        val chan1 = newChannel()
        val chan2 = newChannel()
        val got = mutableListOf<Pair<String, String>>()
        val lock = Mutex()

        val job = launch {
            db.listen(listOf(chan1, chan2)) { n: Notification ->
                lock.withLock { got.add(n.channel to n.value.asString()) }
            }
        }
        delay(150)

        db.notify(chan1, "a1"); db.notify(chan2, "b1"); db.notify(chan1, "a2")

        withTimeout(2_000) {
            while (true) {
                val size = lock.withLock { got.size }
                if (size >= 3) break else delay(50)
            }
        }

        val data = lock.withLock { got.toList() }
        val onlyCh1 = data.filter { it.first == chan1 }.map { it.second }
        val onlyCh2 = data.filter { it.first == chan2 }.map { it.second }
        assertThat(onlyCh1).containsExactlyInAnyOrder("a1", "a2")
        assertThat(onlyCh2).containsExactlyInAnyOrder("b1")
        job.cancel()
    }

    fun `validateChannelName should fail for invalid names`() = runBlocking {
        val invalid = listOf("", "1chan", "-bad", "toolooooooooooooooooooooooooooooooooooooooooooooooooooooooong")
        invalid.forEach { name ->
            val res = runCatching { db.validateChannelName(name) }
            assertThat(res).isFailure()
        }
        // valid samples
        listOf("_ok", "chan_1", "Abc").forEach { name ->
            val res = runCatching { db.validateChannelName(name) }
            assertThat(res.isSuccess).isTrue()
        }
    }

    fun `notify without listener should not fail`() = runBlocking {
        val chan = newChannel()
        val res = runCatching { db.notify(chan, "ping") }
        assertThat(res.isSuccess).isTrue()
    }

    fun `multiple notifications should be delivered`() = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }
        delay(150)

        // Send a burst of messages
        (1..10).forEach { i -> db.notify(chan, i.toString()) }

        withTimeout(3_000) {
            while (true) {
                val size = lock.withLock { received.size }
                if (size >= 10) break else delay(50)
            }
        }

        val values = lock.withLock { received.toList() }
        // Ensure all values 1..10 arrived (order may vary across implementations)
        assertThat(values).containsExactlyInAnyOrder(*(1..10).map { it.toString() }.toTypedArray())
        job.cancel()
    }
}
