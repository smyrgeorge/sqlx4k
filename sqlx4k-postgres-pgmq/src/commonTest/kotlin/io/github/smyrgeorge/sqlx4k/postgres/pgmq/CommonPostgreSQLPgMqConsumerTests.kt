package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("SqlNoDataSourceInspection")
class CommonPostgreSQLPgMqConsumerTests(
    private val client: PgMqClient
) {

    private fun newQueue(): String = "test_queue_${Random.nextInt(1000000)}"

    fun `consumer should process messages automatically`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName, enableNotifyInsert = true)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumerOptions = PgMqConsumer.Options(
            queue = queueName,
            prefetch = 5,
            vt = 10.seconds,
            autoStart = false,
            enableNotifyInsert = true
        )

        val consumer = PgMqConsumer(
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
        withTimeout(5_000) {
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

    fun `consumer should retry failed messages with backoff`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val attemptCounts = mutableMapOf<Long, Int>()
        val lock = Mutex()

        val consumerOptions = PgMqConsumer.Options(
            queue = queueName,
            prefetch = 5,
            vt = 2.seconds,
            autoStart = false,
            queueMinPullDelay = 100.milliseconds,
            messageRetryDelayStep = 100.milliseconds,
            messageMaxRetryDelay = 1.seconds
        )

        val consumer = PgMqConsumer(
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
        withTimeout(10_000) {
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

    fun `consumer should stop gracefully`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(5_000) {
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

    fun `consumer should handle multiple messages with prefetch limit`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(15_000) {
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

    fun `consumer should handle error callbacks properly`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processErrors = mutableListOf<Throwable>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(5_000) {
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

    fun `consumer should process messages in order when prefetch is 1`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedOrder = mutableListOf<String>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(10_000) {
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

    fun `consumer should handle messages with headers`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(5_000) {
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

    fun `consumer should respect visibility timeout`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedCount = mutableListOf<Long>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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

    fun `consumer should work with batch message sending`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName, enableNotifyInsert = true)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(15_000) {
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

    fun `consumer metrics should reflect queue state`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(10_000) {
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

    fun `consumer should handle empty queue gracefully`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val processedMessages = mutableListOf<Message>()
        val lock = Mutex()

        val consumer = PgMqConsumer(
            pgmq = client,
            options = PgMqConsumer.Options(
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
        withTimeout(5_000) {
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
}
