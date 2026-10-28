# PostgreSQL Message Queue (PGMQ)

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres-pgmq:x.y.z")
```

A small, focused Kotlin Multiplatform client around the [PGMQ](https://github.com/pgmq/pgmq) PostgreSQL extension.
Build reliable, asynchronous workers on top of PostgreSQL with first-class support for LISTEN/NOTIFY and a simple
consumer API. It requires `sqlx4k-postgres`, so all the [PostgreSQL driver](../databases/postgresql.md) features are
available. With the [Gradle plugin](../getting-started/gradle-plugin.md), add it with `extensions(Pgmq)`.

## Why use it

- PGMQ operations: create/drop/list queues, send/read/pop/ack/nack, purge, archive, metrics
- Send and receive messages with headers and delays
- Visibility timeout and retries with exponential backoff
- PostgreSQL LISTEN/NOTIFY for instant wake-ups (optional)
- Batch operations and lightweight metrics
- Exactly-once processing semantics via transactions (read/process/ack within one DB transaction)
- Works on Kotlin/JVM and Kotlin/Native

## Quick start

```kotlin
// Set up a PostgreSQL connection
val db = PostgreSQL(
    url = "postgresql://localhost:5432/test",
    username = "postgres",
    password = "postgres"
)

// Create client (auto-installs/validates PGMQ)
val pgmq = PgmqClient(
    pg = PgmqDbAdapterImpl(db),
    options = PgmqClient.Options(autoInstall = true, verifyInstallation = true)
)

// Create a queue (enable NOTIFY for near real-time consumers)
val queue = PgmqClient.Queue(name = "my_queue", enableNotifyInsert = true)
pgmq.create(queue).getOrThrow()

// Send
pgmq.send("my_queue", """{"orderId": 1}""").getOrThrow()

// Read + ack
pgmq.read("my_queue", quantity = 1, vt = 30.seconds).getOrThrow().forEach { msg ->
    // process(msg)
    pgmq.ack("my_queue", msg.msgId).getOrThrow()
}
```

## Consumer

`PgmqConsumer` reads messages in the background, acknowledges the ones processed successfully and negatively
acknowledges (with backoff) the ones that fail:

```kotlin
val consumer = PgmqConsumer(
    pgmq = pgmq,
    options = PgmqConsumer.Options(
        queue = "my_queue",
        prefetch = 10,
        vt = 30.seconds,
        autoStart = true,
        enableNotifyInsert = true
    ),
    onMessage = { message ->
        // process(message)
        // success -> auto-ack
    },
    onFailToProcess = { error ->
        // failure -> auto-nack with backoff
    }
)

// Stop when you're done
consumer.stop()
```

## Topics

Topic routing (pub/sub style) is part of the PGMQ extension since 1.11.0: bind wildcard patterns to queues, then
publish with a routing key and every queue whose pattern matches receives the message.

- `*` matches exactly one segment, `#` matches zero or more (`logs.*` matches `logs.error` but not
  `logs.error.fatal`; `logs.#` matches both).
- A pattern without wildcards routes on an exact match, and `#` alone fans out every message.

```sql
-- 1) Create queues and bind topic patterns
SELECT pgmq.create('logs_all');
SELECT pgmq.create('logs_error');
SELECT pgmq.bind_topic('logs.#', 'logs_all');
SELECT pgmq.bind_topic('logs.*.error', 'logs_error');

-- 2) Dry-run to see where a key would route (no send)
SELECT *
FROM pgmq.test_routing('logs.api.error');

-- 3) Publish to all matching queues (returns the number of queues it was sent to)
SELECT pgmq.send_topic('logs.api.error', '{"message":"boom"}'::jsonb); -- 2: logs_all and logs_error
SELECT pgmq.send_topic('logs.api.info', '{"message":"up"}'::jsonb);    -- 1: logs_all
```

The client exposes `bindTopic`, `unbindTopic` and `sendTopic`.

## Requirements

- PostgreSQL 12+
- PGMQ extension installed (1.11.0+ for topics)

## Examples

See a runnable example in
[examples/postgres](https://github.com/smyrgeorge/sqlx4k/blob/main/examples/postgres/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/examples/postgres/Examples.kt).
