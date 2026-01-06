# sqlx4k-postgres-pgmq

A small, focused Kotlin Multiplatform client around the PostgreSQL Message Queue (PGMQ) extension. Build reliable,
asynchronous workers on top of PostgreSQL with first-class support for LISTEN/NOTIFY and a simple consumer API.

## Why use it

- PGMQ operations: create/drop/list queues, send/read/pop/ack/nack, purge, archive, metrics
- Visibility timeout and retries with exponential backoff
- PostgreSQL LISTEN/NOTIFY for instant wake-ups (optional)
- Batch operations and lightweight metrics
- Exactly-once processing semantics via transactions (read/process/ack within one DB transaction)
- Works on Kotlin/JVM and Kotlin/Native. R2DBC support on JVM

## Installation

```kotlin
implementation("io.github.smyrgeorge:sqlx4k-postgres-pgmq:x.y.z")
```

Requires sqlx4k-postgres; you’ll have all PostgreSQL driver features available.

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
pgmq.create().getOrThrow()

// Send
pgmq.send("my_queue", """{"orderId": 1}""").getOrThrow()

// Read + ack
pgmq.read("my_queue", quantity = 1, vt = 30.seconds).getOrThrow().forEach { msg ->
    // process(msg)
    pgmq.ack("my_queue", msg.msgId).getOrThrow()
}
```

## Lightweight consumer

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

// Stop when you’re done
consumer.stop()
```

## Topics

Optional: topic-like routing (pub/sub style)

- First, run sql/topics.sql in your database (creates helper functions and a bindings table).
- Then you can bind patterns to queues and publish with a routing key:

```sql
-- 1) Create a queue and bind a topic pattern
SELECT pgmq.create('logs_all');
SELECT pgmq.bind_topic('logs.#', 'logs_all');
-- '#' matches zero or more segments

-- 2) Dry-run to see where a key would route (no send)
SELECT *
FROM pgmq.test_routing('logs.api.error');

-- 3) Publish to all matching queues
SELECT pgmq.send_topic('logs.api.error', '{"message":"boom"}'::jsonb);
```

See sql/topics-examples.sql for more patterns (exact match, fanout, wildcard differences).

## Examples

See a runnable
example [here](../examples/postgres/src/commonMain/kotlin/io/github/smyrgeorge/sqlx4k/examples/postgres/Examples.kt)

## Requirements

- PostgreSQL 12+
- PGMQ extension installed

## Links

- PGMQ: https://github.com/pgmq/pgmq
- Docs: https://smyrgeorge.github.io/sqlx4k/
- Repository: https://github.com/smyrgeorge/sqlx4k

## License

MIT — see [LICENSE](../LICENSE)
