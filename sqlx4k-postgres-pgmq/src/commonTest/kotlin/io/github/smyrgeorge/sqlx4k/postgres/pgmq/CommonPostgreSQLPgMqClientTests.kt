package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("SqlNoDataSourceInspection")
class CommonPostgreSQLPgMqClientTests(
    private val client: PgMqClient
) {

    private fun newQueue(): String = "test_queue_${Random.nextInt(1000000)}"

    fun `create queue should succeed`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        val result = client.create(queue)
        assertThat(result.isSuccess).isTrue()

        // Verify queue was created
        val queues = client.listQueues().getOrThrow()
        assertThat(queues.map { it.name }).contains(queueName)

        // Cleanup
        client.drop(queue)
    }

    fun `create unlogged queue should succeed`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName, unlogged = true)

        val result = client.create(queue)
        assertThat(result.isSuccess).isTrue()

        // Verify queue was created with unlogged flag
        val queues = client.listQueues().getOrThrow()
        val createdQueue = queues.find { it.name == queueName }
        assertThat(createdQueue).isNotNull()
        assertThat(createdQueue!!.unlogged).isTrue()

        // Cleanup
        client.drop(queue)
    }

    fun `send and read single message should work`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        // Send a message
        val messageContent = """{"message": "test message"}"""
        val msgId = client.send(queueName, messageContent).getOrThrow()
        assertThat(msgId).isGreaterThan(0)

        // Read the message
        val messages = client.read(queueName).getOrThrow()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].message).isEqualTo(messageContent)
        assertThat(messages[0].msgId).isEqualTo(msgId)

        // Cleanup
        client.drop(queue)
    }

    fun `send message with headers should preserve headers`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val headers = mapOf("key1" to "value1", "key2" to "value2")
        client.send(queueName, """{"test": "value"}""", headers).getOrThrow()

        val messages = client.read(queueName).getOrThrow()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].headers).isEqualTo(headers)

        // Cleanup
        client.drop(queue)
    }

    fun `send batch messages should enqueue all`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val messageBatch = listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}""",
            """{"msg": "msg4"}""",
            """{"msg": "msg5"}"""
        )
        val msgIds = client.send(queueName, messageBatch).getOrThrow()
        assertThat(msgIds).hasSize(5)

        val messages = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(messages).hasSize(5)
        assertThat(messages.map { it.message }).containsExactlyInAnyOrder(*messageBatch.toTypedArray())

        // Cleanup
        client.drop(queue)
    }

    fun `pop message should remove from queue`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        client.send(queueName, """{"message": "test"}""").getOrThrow()

        // Pop the message
        val poppedMessages = client.pop(queueName).getOrThrow()
        assertThat(poppedMessages).hasSize(1)

        // Verify queue is empty
        val remainingMessages = client.read(queueName).getOrThrow()
        assertThat(remainingMessages).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    fun `pop multiple messages should work`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val messageBatch = listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}"""
        )
        client.send(queueName, messageBatch).getOrThrow()

        val poppedMessages = client.pop(queueName, quantity = 2).getOrThrow()
        assertThat(poppedMessages).hasSize(2)

        // One message should remain
        val remainingMessages = client.read(queueName).getOrThrow()
        assertThat(remainingMessages).hasSize(1)

        // Cleanup
        client.drop(queue)
    }

    fun `archive message should move to archive`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgId = client.send(queueName, """{"test": "value"}""").getOrThrow()
        client.read(queueName).getOrThrow()

        val archived = client.archive(queueName, msgId).getOrThrow()
        assertThat(archived).isTrue()

        // Message should not be readable anymore
        val afterArchive = client.read(queueName).getOrThrow()
        assertThat(afterArchive).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    fun `archive multiple messages should work`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgIds = client.send(queueName, listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}"""
        )).getOrThrow()

        val archived = client.archive(queueName, msgIds.take(2)).getOrThrow()
        assertThat(archived).hasSize(2)

        // One message should remain
        val remaining = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(remaining).hasSize(1)

        // Cleanup
        client.drop(queue)
    }

    fun `delete message should remove from queue`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgId = client.send(queueName, """{"test": "value"}""").getOrThrow()
        client.read(queueName).getOrThrow()

        val deleted = client.delete(queueName, msgId).getOrThrow()
        assertThat(deleted).isTrue()

        // Message should not be readable anymore
        val afterDelete = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(afterDelete).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    fun `delete multiple messages should work`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgIds = client.send(queueName, listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}"""
        )).getOrThrow()

        val deleted = client.delete(queueName, msgIds.take(2)).getOrThrow()
        assertThat(deleted).hasSize(2)

        // One message should remain
        val remaining = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(remaining).hasSize(1)

        // Cleanup
        client.drop(queue)
    }

    fun `purge queue should remove all messages`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        client.send(queueName, listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}""",
            """{"msg": "msg4"}""",
            """{"msg": "msg5"}"""
        )).getOrThrow()

        val purgedCount = client.purge(queue).getOrThrow()
        assertThat(purgedCount).isEqualTo(5)

        val remaining = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(remaining).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    fun `setVt should update visibility timeout`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgId = client.send(queueName, """{"test": "value"}""").getOrThrow()
        client.read(queueName, vt = 5.seconds).getOrThrow()

        // Update visibility timeout
        val updatedId = client.setVt(queueName, msgId, vt = 1.seconds).getOrThrow()
        assertThat(updatedId).isEqualTo(msgId)

        // Wait for new VT to expire
        delay(1500.milliseconds)

        // Message should be visible again
        val afterVtExpired = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(afterVtExpired).hasSize(1)

        // Cleanup
        client.drop(queue)
    }

    fun `ack should delete message`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgId = client.send(queueName, """{"test": "value"}""").getOrThrow()
        client.read(queueName).getOrThrow()

        val acked = client.ack(queueName, msgId).getOrThrow()
        assertThat(acked).isTrue()

        val remaining = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(remaining).isEmpty()

        // Cleanup
        client.drop(queue)
    }

    fun `nack should reset visibility timeout`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val msgId = client.send(queueName, """{"test": "value"}""").getOrThrow()
        client.read(queueName, vt = 30.seconds).getOrThrow()

        // Nack with immediate retry (VT = 0)
        val nackedId = client.nack(queueName, msgId, vt = 0.seconds).getOrThrow()
        assertThat(nackedId).isEqualTo(msgId)

        // Message should be immediately visible
        val afterNack = client.read(queueName, quantity = 10).getOrThrow()
        assertThat(afterNack).hasSize(1)

        // Cleanup
        client.drop(queue)
    }

    fun `metrics should return queue statistics`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        // Send some messages
        client.send(queueName, listOf(
            """{"msg": "msg1"}""",
            """{"msg": "msg2"}""",
            """{"msg": "msg3"}"""
        )).getOrThrow()

        val metrics = client.metrics(queueName).getOrThrow()
        assertThat(metrics.queueName).isEqualTo(queueName)
        assertThat(metrics.queueLength).isGreaterThanOrEqualTo(0)

        // Cleanup
        client.drop(queue)
    }

    fun `delayed message should not be visible immediately`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        // Send message with delay
        val msgId = client.send(queueName, """{"message": "delayed"}""", delay = 2.seconds).getOrThrow()

        // Should not be visible immediately
        val immediate = client.read(queueName).getOrThrow()
        assertThat(immediate).isEmpty()

        // Wait for delay to expire
        delay(2500.milliseconds)

        // Now should be visible
        val afterDelay = client.read(queueName).getOrThrow()
        assertThat(afterDelay).hasSize(1)
        assertThat(afterDelay[0].msgId).isEqualTo(msgId)

        // Cleanup
        client.drop(queue)
    }

    fun `drop queue should succeed`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        val dropped = client.drop(queue).getOrThrow()
        assertThat(dropped).isTrue()

        val queues = client.listQueues().getOrThrow()
        assertThat(queues.map { it.name }).doesNotContain(queueName)
    }

    fun `listQueues should return all queues`() = runBlocking {
        val queue1Name = newQueue()
        val queue2Name = newQueue()

        val queue1 = PgMqClient.Queue(queue1Name)
        val queue2 = PgMqClient.Queue(queue2Name)

        client.create(queue1).getOrThrow()
        client.create(queue2).getOrThrow()

        val queues = client.listQueues().getOrThrow()
        val queueNames = queues.map { it.name }

        assertThat(queueNames).contains(queue1Name)
        assertThat(queueNames).contains(queue2Name)

        // Cleanup
        client.drop(queue1)
        client.drop(queue2)
    }

    fun `message readCt should increment on repeated reads`() = runBlocking {
        val queueName = newQueue()
        val queue = PgMqClient.Queue(queueName)

        client.create(queue).getOrThrow()

        client.send(queueName, """{"test": "value"}""").getOrThrow()

        // First read - readCt starts at 1 after first read in PGMQ
        val msg1 = client.read(queueName, vt = 500.milliseconds).getOrThrow()
        assertThat(msg1[0].readCt).isEqualTo(1)

        // Wait for VT to expire
        delay(600.milliseconds)

        // Second read
        val msg2 = client.read(queueName, vt = 500.milliseconds).getOrThrow()
        assertThat(msg2[0].readCt).isEqualTo(2)

        // Cleanup
        client.drop(queue)
    }
}
