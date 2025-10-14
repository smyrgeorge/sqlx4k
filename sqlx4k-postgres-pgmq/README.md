# sqlx4k-postgres-pgmq

PostgreSQL Message Queue (PGMQ) extension for sqlx4k - A Kotlin Multiplatform client for building reliable, asynchronous
message queues using PostgreSQL.

## Features

- Full support for [PGMQ](https://github.com/pgmq/pgmq) operations
- Message queue management (create, drop, list)
- Send and receive messages with optional headers and delays
- Message acknowledgment (ack/nack) with visibility timeout control
- Batch operations for improved performance
- Queue metrics and monitoring
- High-level consumer API with automatic retry logic
- PostgreSQL LISTEN/NOTIFY support for real-time queue notifications
- Kotlin Multiplatform support (JVM, Native)

## Installation

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres-pgmq:x.y.z")
```

This module depends on `sqlx4k-postgres`, so you'll have access to all PostgreSQL driver features.

## Quick Start

### 1. Set up the Client

```kotlin
// Create a PostgreSQL connection
val db = PostgreSQL(
    url = "postgresql://localhost:5432/test",
    username = "postgres",
    password = "postgres"
)

// Create PGMQ client
val pgmq = PgMqClient(
    pg = PgMqDbAdapterImpl(db),
    options = PgMqClient.Options(
        autoInstall = true,          // Auto-install pgmq extension if not present
        verifyInstallation = true    // Verify pgmq is installed on startup
    )
)
```

### 2. Create and Manage Queues

```kotlin
// Create a queue
val queue = PgMqClient.Queue(
    name = "my_queue",
    unlogged = false,                   // Use standard (logged) tables
    enableNotifyInsert = true           // Enable PostgreSQL notifications on insert
)
pgmq.create(queue).getOrThrow()

// List all queues
val queues = pgmq.listQueues().getOrThrow()
queues.forEach { println("Queue: ${it.name}, Created: ${it.createdAt}") }

// Drop a queue when done
pgmq.drop(queue).getOrThrow()
```

### 3. Send Messages

```kotlin
// Send a single message
val messageId = pgmq.send(
    queue = "my_queue",
    message = """{"orderId": 123, "status": "pending"}""",
    headers = mapOf("priority" to "high", "source" to "api"),
    delay = 0.seconds  // Optional delay before a message becomes visible
).getOrThrow()

println("Message sent with ID: $messageId")

// Send multiple messages in a batch
val messages = listOf(
    """{"orderId": 124, "status": "pending"}""",
    """{"orderId": 125, "status": "pending"}"""
)
val messageIds = pgmq.send(
    queue = "my_queue",
    messages = messages,
    headers = mapOf("batch" to "true")
).getOrThrow()
```

### 4. Read and Process Messages

```kotlin
// Pop messages (read and remove)
val messages = pgmq.pop(
    queue = "my_queue",
    quantity = 10
).getOrThrow()

messages.forEach { msg ->
    println("Message ID: ${msg.msgId}, Content: ${msg.message}")
}

// Read messages with visibility timeout (doesn't remove)
val readMessages = pgmq.read(
    queue = "my_queue",
    quantity = 5,
    vt = 30.seconds  // Hide a message for 30 seconds while processing
).getOrThrow()

readMessages.forEach { msg ->
    try {
        // Process the message
        processMessage(msg)

        // Acknowledge successful processing
        pgmq.ack("my_queue", msg.msgId).getOrThrow()
    } catch (e: Exception) {
        // Negative acknowledge - message becomes visible again after delay
        pgmq.nack("my_queue", msg.msgId, vt = 60.seconds).getOrThrow()
    }
}
```

### 5. High-Level Consumer

The consumer provides automatic message fetching, processing, and retry logic:

```kotlin
val consumer = PgMqConsumer(
    pgmq = pgmq,
    options = PgMqConsumer.Options(
        queue = "my_queue",
        prefetch = 10,                           // Number of messages to prefetch
        vt = 30.seconds,                         // Visibility timeout per message
        autoStart = true,                        // Start consuming immediately
        enableNotifyInsert = true,               // Use LISTEN/NOTIFY for instant notifications
        queueMinPullDelay = 50.milliseconds,     // Min delay between polls
        queueMaxPullDelay = 2.seconds,           // Max delay between polls (with backoff)
        messageRetryDelayStep = 500.milliseconds, // Retry delay increment per attempt
        messageMaxRetryDelay = 60.seconds        // Max retry delay
    ),
    onMessage = { message ->
        // Process the message
        println("Processing: ${message.message}")
        processMessage(message)
        // Message is automatically acknowledged on success
    },
    onFaiToRead = { error ->
        println("Error reading from queue: ${error.message}")
    },
    onFailToProcess = { error ->
        println("Error processing message: ${error.message}")
        // Message is automatically nacked with exponential backoff
    },
    onFaiToAck = { error ->
        println("Error acknowledging message: ${error.message}")
    },
    onFaiToNack = { error ->
        println("Error nacking message: ${error.message}")
    }
)

// Consumer is already running if autoStart = true

// Stop the consumer when needed
consumer.stop()

// Get queue metrics
val metrics = consumer.metrics().getOrThrow()
println("Queue length: ${metrics.queueLength}, Visible: ${metrics.queueVisibleLength}")
```

### 6. Advanced Operations

```kotlin
// Archive messages (move to archive table)
pgmq.archive("my_queue", messageId).getOrThrow()
pgmq.archive("my_queue", listOf(id1, id2, id3)).getOrThrow()

// Delete messages permanently
pgmq.delete("my_queue", messageId).getOrThrow()
pgmq.delete("my_queue", listOf(id1, id2, id3)).getOrThrow()

// Purge all messages from queue
val purgedCount = pgmq.purge(queue).getOrThrow()
println("Purged $purgedCount messages")

// Update visibility timeout for a message
pgmq.setVt("my_queue", messageId, vt = 120.seconds).getOrThrow()

// Get queue metrics
val metrics = pgmq.metrics("my_queue").getOrThrow()
println(
    """
    Queue: ${metrics.queueName}
    Length: ${metrics.queueLength}
    Visible: ${metrics.queueVisibleLength}
    Total processed: ${metrics.totalMessages}
    Oldest message age: ${metrics.oldestMsgAgeSec}s
    Newest message age: ${metrics.newestMsgAgeSec}s
""".trimIndent()
)

// Get metrics for all queues
val allMetrics = pgmq.metrics().getOrThrow()
```

## Best Practices

1. **Use Visibility Timeout Wisely**: Set `vt` based on your processing time. If a message takes longer than `vt` to
   process, it may be delivered to another consumer.
2. **Enable Notifications for Real-Time Processing**: Set `enableNotifyInsert = true` on both queue and consumer for
   instant message delivery via PostgreSQL LISTEN/NOTIFY.
3. **Batch Operations**: Use batch send/archive/delete methods for better performance when handling multiple messages.
4. **Handle Errors Gracefully**: Always use `nack` with appropriate retry delay when processing fails, instead of
   letting messages timeout.
5. **Monitor Queue Metrics**: Regularly check queue metrics to detect issues like message buildup or processing delays.
6. **Use Unlogged Queues Carefully**: Unlogged queues (`unlogged = true`) are faster but not crash-safe. Use only for
   non-critical messages.

## Transaction Support

All operations support transactions via sqlx4k's `QueryExecutor`:

```kotlin
db.transaction {
    // Send a message within transaction
    pgmq.send("my_queue", """{"data": "value"}""").getOrThrow()

    // Read and process
    val messages = pgmq.read("my_queue", quantity = 1).getOrThrow()
    messages.forEach { msg ->
        // Process and acknowledge within the same transaction
        processInTransaction(msg)
        pgmq.ack("my_queue", msg.msgId).getOrThrow()
    }
    // Transaction auto-commits on success, rolls back on error
}
```

## Requirements

- PostgreSQL 12+
- PGMQ extension installed (automatically installed if `autoInstall = true`)

## Related Links

- [PGMQ GitHub](https://github.com/tembo-io/pgmq)
- [sqlx4k Documentation](https://smyrgeorge.github.io/sqlx4k/)
- [sqlx4k GitHub](https://github.com/smyrgeorge/sqlx4k)

## License

MIT - see [LICENSE](../LICENSE)