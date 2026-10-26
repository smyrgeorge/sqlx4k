@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isTrue
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class PostgreSQLListenNotifyTests {

    private val db = testPostgreSQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newChannel(): String = "chan_${Random.nextInt(1000000)}"

    @Test
    fun `listen on single channel should receive notifications`(): Unit = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }

        // Give listener a moment to subscribe
        delay(150.milliseconds)

        (1..3).forEach { i -> db.notify(chan, "hello-$i") }

        withTimeout(2_000.milliseconds) {
            while (true) {
                val size = lock.withLock { received.size }
                if (size >= 3) break else delay(50.milliseconds)
            }
        }

        assertThat(lock.withLock { received.toList() }.size).isEqualTo(3)
        job.cancel()
    }

    @Test
    fun `listen on multiple channels should route notifications`(): Unit = runBlocking {
        val chan1 = newChannel()
        val chan2 = newChannel()
        val got = mutableListOf<Pair<String, String>>()
        val lock = Mutex()

        val job = launch {
            db.listen(listOf(chan1, chan2)) { n: Notification ->
                lock.withLock { got.add(n.channel to n.value.asString()) }
            }
        }
        delay(150.milliseconds)

        db.notify(chan1, "a1"); db.notify(chan2, "b1"); db.notify(chan1, "a2")

        withTimeout(2_000.milliseconds) {
            while (true) {
                val size = lock.withLock { got.size }
                if (size >= 3) break else delay(50.milliseconds)
            }
        }

        val data = lock.withLock { got.toList() }
        val onlyCh1 = data.filter { it.first == chan1 }.map { it.second }
        val onlyCh2 = data.filter { it.first == chan2 }.map { it.second }
        assertThat(onlyCh1).containsExactlyInAnyOrder("a1", "a2")
        assertThat(onlyCh2).containsExactlyInAnyOrder("b1")
        job.cancel()
    }

    @Test
    fun `validateChannelName should fail for invalid names`(): Unit = runBlocking {
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

    @Test
    fun `notify without listener should not fail`(): Unit = runBlocking {
        val chan = newChannel()
        val res = runCatching { db.notify(chan, "ping") }
        assertThat(res.isSuccess).isTrue()
    }

    @Test
    fun `multiple notifications should be delivered`(): Unit = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }
        delay(150.milliseconds)

        // Send a burst of messages
        (1..10).forEach { i -> db.notify(chan, i.toString()) }

        withTimeout(3_000.milliseconds) {
            while (true) {
                val size = lock.withLock { received.size }
                if (size >= 10) break else delay(50.milliseconds)
            }
        }

        val values = lock.withLock { received.toList() }
        // Ensure all values 1..10 arrived (order may vary across implementations)
        assertThat(values).containsExactlyInAnyOrder(*(1..10).map { it.toString() }.toTypedArray())
        job.cancel()
    }

    // The server may drop a listening connection at any time (a restart, pg_terminate_backend): the listener must
    // reconnect and keep delivering notifications (the native driver used to abort the process instead).
    @Test
    fun `listen should survive a terminated connection`(): Unit = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }
        // Give listener a moment to subscribe
        delay(150.milliseconds)

        suspend fun deliversAfter(payload: String) = withTimeout(10_000.milliseconds) {
            // Keep notifying until the (re)subscribed listener gets one.
            while (lock.withLock { payload !in received }) {
                db.notify(chan, payload)
                delay(200.milliseconds)
            }
        }

        deliversAfter("before")
        // Terminate the listening connection(s) for this channel.
        val terminated = db.fetchAll(
            "select pg_terminate_backend(pid) from pg_stat_activity " +
                "where pid <> pg_backend_pid() and query ilike '%listen%$chan%'"
        ).getOrThrow()
        assertThat(terminated.size >= 1, "a listening connection was terminated").isTrue()
        deliversAfter("after")

        job.cancel()
    }

    @Test
    fun `notifications should be delivered in order`(): Unit = runBlocking {
        val chan = newChannel()
        val received = mutableListOf<String>()
        val lock = Mutex()

        val job = launch {
            db.listen(chan) { n: Notification ->
                lock.withLock { received.add(n.value.asString()) }
            }
        }
        // Give listener a moment to subscribe
        delay(150.milliseconds)

        val sent = (1..50).map { "n-$it" }
        sent.forEach { db.notify(chan, it) }

        withTimeout(5_000.milliseconds) {
            while (lock.withLock { received.size } < sent.size) delay(50.milliseconds)
        }
        assertThat(lock.withLock { received.toList() }).isEqualTo(sent)
        job.cancel()
    }
}
