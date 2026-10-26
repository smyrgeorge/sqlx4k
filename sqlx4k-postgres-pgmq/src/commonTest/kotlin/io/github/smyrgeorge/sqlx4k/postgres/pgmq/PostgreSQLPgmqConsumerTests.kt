package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PostgreSQLPgmqConsumerTests {

    private val db = testPostgreSQL()
    private val client = testPgmqClient(db)

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    private fun newQueue(): String = "test_queue_${Random.nextInt(1000000)}"

    @Test
    fun `consumer should process messages automatically`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName, enableNotifyInsert = true)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumerOptions = PgmqConsumer.Options(
            queue = queueName,
            prefetch = 5,
            vt = 10.seconds,
            autoStart = false,
            enableNotifyInsert = true
        )

        val consumer = PgmqConsumer(
            pgmq = client,
            options = consumerOptions,
            onMessage = { msg ->
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds) // Give consumer time to start

        // Send messages
        val messageBatch = listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}"""
        )
        client.send(queueName, messageBatch).getOrThrow()

        // Wait for messages to be processed
        withTimeout(5_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 3) break else delay(100.milliseconds)
            }
        }

        val processed = lock.withLock { processedMessages.toList() }
        assertThat(processed).hasSize(3)
        assertThat(processed.map { it.message }).containsExactlyInAnyOrder(*messageBatch.toTypedArray())

        consumer.stop()
        delay(500.milliseconds) // Give consumer time to stop

        // Queue should be empty after processing
        val remaining = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(remaining).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should retry failed messages with backoff`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val attemptCounts = mutableMapOf<Long, Int>()
        val lock = Mutex()

        val consumerOptions = PgmqConsumer.Options(
            queue = queueName,
            prefetch = 5,
            vt = 2.seconds,
            autoStart = false,
            queueMinPullDelay = 100.milliseconds,
            messageRetryDelayStep = 100.milliseconds,
            messageMaxRetryDelay = 1.seconds
        )

        val consumer = PgmqConsumer(
            pgmq = client,
            options = consumerOptions,
            onMessage = { msg ->
                lock.withLock {
                    attemptCounts[msg.msgId] = (attemptCounts[msg.msgId] ?: 0) + 1
                    val attempts = attemptCounts[msg.msgId]!!

                    // Fail first 2 attempts, succeed on 3rd
                    if (attempts < 3) {
                        error("Simulated processing failure")
                    }
                }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        client.send(queueName, """{"message": "test"}""").getOrThrow()

        // Wait for retry logic to process the message successfully
        withTimeout(10_000.milliseconds) {
            while (true) {
                val attempts = lock.withLock { attemptCounts.values.firstOrNull() ?: 0 }
                if (attempts >= 3) break else delay(200.milliseconds)
            }
        }

        val finalAttempts = lock.withLock { attemptCounts.values.first() }
        assertThat(finalAttempts).isEqualTo(3)

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should stop gracefully`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 10.seconds,
                autoStart = false
            ),
            onMessage = { msg ->
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        // Send initial messages
        client.send(
            queueName, listOf(
                """{"msg": "msg1"}""",
                """{"msg": "msg2"}"""
            )
        ).getOrThrow()

        // Wait for processing
        withTimeout(5_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 2) break else delay(100.milliseconds)
            }
        }

        // Stop consumer
        consumer.stop()
        delay(1_000.milliseconds)

        val beforeStop = lock.withLock { processedMessages.size }

        // Send more messages after stop
        client.send(queueName, """{"msg": "msg3"}""").getOrThrow()
        delay(500.milliseconds)

        val afterStop = lock.withLock { processedMessages.size }
        // Should not process new messages after stop
        assertThat(afterStop).isEqualTo(beforeStop)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should handle multiple messages with prefetch limit`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 3,
                vt = 10.seconds,
                autoStart = false
            ),
            onMessage = { msg ->
                delay(100.milliseconds) // Simulate processing time
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        // Send more messages than prefetch limit
        val messageBatch = (1..10).map { """{"msg": "msg$it"}""" }
        client.send(queueName, messageBatch).getOrThrow()

        // Wait for all messages to be processed
        withTimeout(15_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 10) break else delay(200.milliseconds)
            }
        }

        val processed = lock.withLock { processedMessages.toList() }
        assertThat(processed).hasSize(10)
        assertThat(processed.map { it.message }).containsExactlyInAnyOrder(*messageBatch.toTypedArray())

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should handle error callbacks properly`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processErrors = mutableListOf<Throwable>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 2.seconds,
                autoStart = false,
                queueMinPullDelay = 100.milliseconds
            ),
            onMessage = { msg ->
                error("Simulated processing error for message ${msg.msgId}")
            },
            onFailToProcess = { error ->
                lock.withLock { processErrors.add(error) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        client.send(queueName, """{"message": "test"}""").getOrThrow()

        // Wait for at least one error to be captured
        withTimeout(5_000.milliseconds) {
            while (true) {
                val errorCount = lock.withLock { processErrors.size }
                if (errorCount >= 1) break else delay(100.milliseconds)
            }
        }

        val errors = lock.withLock { processErrors.toList() }
        assertThat(errors).isNotEmpty()
        assertThat(errors.first().message).isNotNull().contains("Simulated processing error")

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should process messages in order when prefetch is 1`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedOrder = mutableListOf<String>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 1,
                vt = 10.seconds,
                autoStart = false,
                queueMinPullDelay = 50.milliseconds
            ),
            onMessage = { msg ->
                lock.withLock { processedOrder.add(msg.message) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        // Send messages in specific order
        val messageBatch = listOf(
            """{"order": 1}""",
            """{"order": 2}""",
            """{"order": 3}"""
        )
        client.send(queueName, messageBatch).getOrThrow()

        // Wait for all messages to be processed
        withTimeout(10_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedOrder.size }
                if (size >= 3) break else delay(100.milliseconds)
            }
        }

        val processed = lock.withLock { processedOrder.toList() }
        assertThat(processed).containsExactly(*messageBatch.toTypedArray())

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should handle messages with headers`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 10.seconds,
                autoStart = false
            ),
            onMessage = { msg ->
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        val headers = mapOf("correlationId" to "123", "source" to "test")
        client.send(queueName, """{"data": "value"}""", headers).getOrThrow()

        // Wait for message to be processed
        withTimeout(5_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 1) break else delay(100.milliseconds)
            }
        }

        val processed = lock.withLock { processedMessages.first() }
        assertThat(processed.headers).isEqualTo(headers)
        assertThat(processed.message).isEqualTo("""{"data": "value"}""")

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should respect visibility timeout`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedCount = mutableListOf<Long>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 1,
                vt = 2.seconds,
                autoStart = false,
                queueMinPullDelay = 100.milliseconds
            ),
            onMessage = { msg ->
                lock.withLock { processedCount.add(msg.msgId) }
                // Simulate long processing that exceeds VT
                delay(3.seconds)
            }
        )

        consumer.start()
        delay(200.milliseconds)

        client.send(queueName, """{"message": "test"}""").getOrThrow()

        // Wait for processing to start and VT to expire
        delay(2500.milliseconds)

        val count = lock.withLock { processedCount.size }
        // Message should be reprocessed due to VT timeout
        assertThat(count).isGreaterThanOrEqualTo(1)

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should work with batch message sending`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName, enableNotifyInsert = true)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 10,
                vt = 10.seconds,
                autoStart = false,
                enableNotifyInsert = true
            ),
            onMessage = { msg ->
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        // Send large batch
        val largeBatch = (1..20).map { """{"batch": "msg$it"}""" }
        client.send(queueName, largeBatch).getOrThrow()

        // Wait for all messages to be processed
        withTimeout(15_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 20) break else delay(200.milliseconds)
            }
        }

        val processed = lock.withLock { processedMessages.toList() }
        assertThat(processed).hasSize(20)
        assertThat(processed.map { it.message }).containsExactlyInAnyOrder(*largeBatch.toTypedArray())

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer metrics should reflect queue state`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 10.seconds,
                autoStart = false
            ),
            onMessage = { msg ->
                delay(100.milliseconds)
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        // Send messages
        client.send(
            queueName, listOf(
                """{"msg": "msg1"}""",
                """{"msg": "msg2"}""",
                """{"msg": "msg3"}"""
            )
        ).getOrThrow()

        // Check metrics before processing completes
        delay(100.milliseconds)
        val metricsBeforeCompletion = consumer.metrics().getOrThrow()
        assertThat(metricsBeforeCompletion.queueName).isEqualTo(queueName)

        // Wait for all processing to complete
        withTimeout(10_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 3) break else delay(100.milliseconds)
            }
        }

        // Check metrics after processing
        delay(500.milliseconds)
        val metricsAfterCompletion = consumer.metrics().getOrThrow()
        assertThat(metricsAfterCompletion.queueName).isEqualTo(queueName)

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should handle empty queue gracefully`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 10.seconds,
                autoStart = false,
                queueMinPullDelay = 100.milliseconds,
                queueMaxPullDelay = 500.milliseconds
            ),
            onMessage = { msg ->
                lock.withLock { processedMessages.add(msg) }
            }
        )

        consumer.start()
        delay(1_000.milliseconds) // Let it poll empty queue multiple times

        val processed = lock.withLock { processedMessages.size }
        assertThat(processed).isEqualTo(0)

        // Now send a message
        client.send(queueName, """{"msg": "msg1"}""").getOrThrow()

        // Wait for processing
        withTimeout(5_000.milliseconds) {
            while (true) {
                val size = lock.withLock { processedMessages.size }
                if (size >= 1) break else delay(100.milliseconds)
            }
        }

        val processedAfter = lock.withLock { processedMessages.size }
        assertThat(processedAfter).isEqualTo(1)

        consumer.stop()
        delay(500.milliseconds)

        // Cleanup
        client.drop(queue)
    }

    @Test
    fun `consumer should survive a cancellation exception thrown by the handler`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)
        client.create(queue).getOrThrow()

        val processed = mutableListOf<Long>()
        var thrown = false
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 2.seconds,
                autoStart = false,
                queueMinPullDelay = 100.milliseconds,
                messageRetryDelayStep = 100.milliseconds,
                messageMaxRetryDelay = 1.seconds
            ),
            onMessage = { msg ->
                lock.withLock {
                    // E.g. awaiting a Deferred cancelled elsewhere: a failure of this message, not a shutdown of
                    // the consumer, which must keep consuming (and retry this message).
                    if (!thrown) {
                        thrown = true
                        throw CancellationException("cancelled elsewhere")
                    }
                    processed += msg.msgId
                }
            }
        )

        consumer.start()
        delay(200.milliseconds)

        client.send(queueName, """{"n": 1}""").getOrThrow()
        client.send(queueName, """{"n": 2}""").getOrThrow()

        withTimeout(10_000.milliseconds) {
            while (lock.withLock { processed.size } < 2) delay(100.milliseconds)
        }

        consumer.stop()
        client.drop(queue)
    }

    // A batch is read at once, so its last messages wait for the handlers of the ones before them. With a backlog,
    // their visibility timeout used to run out meanwhile: they were read again and handled twice.
    @Test
    fun `consumer should handle each message once when a batch outlasts its visibility timeout`(): Unit = runBlocking {
        val queueName = newQueue()
        val queue = PgmqClient.Queue(queueName)
        client.create(queue).getOrThrow()

        val handled = mutableMapOf<Long, Int>()
        val lock = Mutex()
        // Sent before the consumer starts, so a single read gets them all.
        val ids = (1..5).map { client.send(queueName, """{"n": $it}""").getOrThrow() }

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                prefetch = 5,
                vt = 1.seconds, // invisible for 2 seconds after a read
                autoStart = false,
                queueMinPullDelay = 50.milliseconds,
                queueMaxPullDelay = 200.milliseconds,
            ),
            onMessage = { msg ->
                lock.withLock { handled[msg.msgId] = (handled[msg.msgId] ?: 0) + 1 }
                delay(600.milliseconds) // five of them take longer than the visibility timeout
            }
        )
        consumer.start()

        withTimeout(15_000.milliseconds) {
            while (lock.withLock { handled.keys.size } < ids.size) delay(100.milliseconds)
        }
        // Leave time for any copy read again to be handled as well.
        delay(3_000.milliseconds)
        consumer.stop()

        assertThat(lock.withLock { handled.toMap() }).isEqualTo(ids.associateWith { 1 })
        client.drop(queue)
    }

    // pgmq accepts 47-character queue names, whose insert channel (pgmq.q_<queue>.INSERT) is longer than the
    // driver accepts: the consumer must report it and keep working by polling (on native, the uncaught failure
    // used to end the process).
    @Test
    fun `consumer should work when listening for inserts fails`(): Unit = runBlocking {
        val queueName = "t_long_${Random.nextInt(1_000_000)}_".padEnd(47, 'x')
        val queue = PgmqClient.Queue(queueName)
        client.create(queue).getOrThrow()

        val processed = mutableListOf<Long>()
        val readFailures = mutableListOf<Throwable>()
        val lock = Mutex()

        val consumer = PgmqConsumer(
            pgmq = client,
            options = PgmqConsumer.Options(
                queue = queueName,
                autoStart = false,
                enableNotifyInsert = true,
                queueMinPullDelay = 50.milliseconds,
                queueMaxPullDelay = 200.milliseconds,
            ),
            onMessage = { msg -> lock.withLock { processed += msg.msgId } },
            onFailToRead = { e -> lock.withLock { readFailures += e } },
        )
        consumer.start()
        val id = client.send(queueName, """{"n": 1}""").getOrThrow()

        withTimeout(10_000.milliseconds) {
            while (lock.withLock { id !in processed }) delay(100.milliseconds)
        }
        assertThat(lock.withLock { readFailures.isNotEmpty() }).isEqualTo(true)

        consumer.stop()
        client.drop(queue)
    }
}
