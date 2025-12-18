@file:Suppress("SqlNoDataSourceInspection")

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.migrate.MigrationFile
import io.github.smyrgeorge.sqlx4k.impl.migrate.Migrator
import io.github.smyrgeorge.sqlx4k.impl.migrate.utils.listFilesWithContent
import io.github.smyrgeorge.sqlx4k.impl.types.NoWrappingTuple
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.extensions.toJsonString
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.*
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.BooleanRowMapper.toSingleBooleanResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.LongRowMapper.toSingleLongResult
import io.github.smyrgeorge.sqlx4k.postgres.pgmq.impl.mappers.UnitRowMapper.toSingleUnitResult
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PgMqClient is a client for interfacing with the pgmq system, providing various messaging queue operations.
 * This client makes use of PostgreSQL as a message queue backend.
 *
 * @param pg The PgMqDbAdapter instance used for executing database queries.
 * @param options Configuration options for the client, including installation verification and auto-install behavior.
 *
 * Features:
 * - Installation verification of the pgmq extension in the database.
 * - Creating and managing queues with optional notification on message insertion.
 * - Listing all existing queues.
 * - Sending messages to a queue, with support for headers and optional delay.
 * - Batch sending of messages to a queue.
 * - Popping messages from a queue.
 * - Reading messages from a queue with optional visibility timeout.
 * - Archiving and deleting messages by single or bulk identifiers.
 * - Purging all messages from a queue.
 */
class PgMqClient(
    val pg: PgMqDbAdapter,
    private val options: Options = Options()
) {
    init {
        // Ensure the pgmq extensions is installed.
        if (options.verifyInstallation) runBlocking { install() }
    }

    private suspend fun install() {
        suspend fun installed(): Boolean {
            // language=SQL
            val sql = "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = 'pgmq')"
            return pg.fetchAll(Statement.create(sql), BooleanRowMapper).toSingleBooleanResult().getOrThrow()
        }

        suspend fun installExtension() {
            // language=SQL
            val sql = "CREATE EXTENSION IF NOT EXISTS pgmq"
            pg.execute(sql).getOrElse { error("Could not create the 'pgmq' extension (${it.message}).") }
        }

        suspend fun installFromFiles() {
            val files: List<MigrationFile> = listFilesWithContent(options.intallFilesPath)
                .filter { it.first != "pgmq.sql" }
                .sortedBy { it.first }
                .mapIndexed { i, p ->
                    val name = "$i-${p.first}"
                    val content = p.second
                    name to content
                }.map { MigrationFile(it.first, it.second) }

            Migrator.migrate(
                db = pg,
                files = files,
                table = "migrations",
                schema = "pgmq",
                createSchema = true,
                dialect = Dialect.PostgreSQL,
                afterStatementExecution = { _, _ -> },
                afterFileMigration = { m, d -> println("[pgmq] Migrated file: $m, duration: ${d}ms") },
            )
        }

        if (!installed()) {
            if (!options.autoInstall) error("Could not verify the 'pgmq' installation.")
            if (options.installFromFiles) installFromFiles() else installExtension()
        }

        // Recheck the installation.
        if (!installed()) error("Could not verify the 'pgmq' installation.")
    }

    /**
     * Creates a queue in the system with the specified configuration.
     *
     * This method ensures the creation of a queue, considering its name, whether it should be unlogged,
     * and whether notifications for insert events are enabled. If a queue with an identical name already exists,
     * it validates its compatibility with the current configuration.
     *
     * @param queue The configuration of the queue to be created, including its name, `unlogged` flag,
     * and whether to enable notifications for inserts.
     * @return A [Result] containing [Unit] on successful creation of the queue, or an error if the operation fails.
     */
    suspend fun create(queue: Queue): Result<Unit> = runCatching {
        context(tx: Transaction)
        suspend fun create(queue: String, unlogged: Boolean): Result<Unit> {
            // language=SQL
            val sql = if (unlogged) "SELECT pgmq.create_unlogged(queue_name := ?)"
            else "SELECT pgmq.create(queue_name := ?)"
            val statement = Statement.create(sql).bind(0, queue)
            return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
        }

        context(tx: Transaction)
        suspend fun enableNotifyInsert(queue: String, throttleNotifyInterval: Duration): Result<Unit> {
            // language=SQL
            val sql = "SELECT pgmq.enable_notify_insert(queue_name := ?, throttle_interval_ms := ?)"
            val statement = Statement.create(sql).bind(0, queue).bind(1, throttleNotifyInterval.inWholeMilliseconds)
            return tx.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
        }

        return pg.transaction {
            val existing = listQueues().getOrThrow().find { it.name == queue.name }
            existing?.let {
                check(it.unlogged == queue.unlogged) { "Queue '${queue.name}' already exists with a different unlogged flag." }
                check(!it.partitioned) { "Queue '${queue.name}' already exists with a partitioned flag (partitioning is not yet supported by this client)." }
                return@transaction Result.success(Unit)
            }

            create(queue.name, queue.unlogged).getOrThrow()
            if (queue.enableNotifyInsert) enableNotifyInsert(queue.name, queue.throttleNotifyInterval).getOrThrow()
            Result.success(Unit)
        }
    }

    /**
     * Retrieves the list of all available queues in the system.
     *
     * This method queries the underlying database to fetch metadata about all queues,
     * including their names, whether they are partitioned or unlogged,
     * and their creation timestamps.
     *
     * @return A [Result] containing a list of [QueueRecord] objects representing the queues.
     *         The result is successful if the operation completes without errors,
     *         otherwise it contains an exception.
     */
    suspend fun listQueues(): Result<List<QueueRecord>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.list_queues()"
        return pg.fetchAll(sql, QueueRecordRowMapper)
    }

    /**
     * Drops the specified queue from the system.
     *
     * This method removes all metadata and references associated with the provided queue name.
     * If the operation is successful, the system will no longer recognize the specified queue.
     *
     * @param queue The queue to be dropped, represented by its configuration.
     * @return A [Result] containing `true` if the queue was successfully dropped, or `false` if the operation fails.
     */
    suspend fun drop(queue: Queue): Result<Boolean> = drop(queue.name)
    private suspend fun drop(queue: String): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.drop_queue(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    /**
     * Removes all messages from the specified queue.
     *
     * This function purges the queue by deleting all messages it contains. The number of
     * messages removed as a result of the operation is returned.
     *
     * @param queue The queue to be purged, represented by its configuration.
     * @return A [Result] containing the number of messages purged from the queue,
     *         or an error if the operation fails.
     */
    suspend fun purge(queue: Queue): Result<Long> = purge(queue.name)
    private suspend fun purge(queue: String): Result<Long> {
        // language=SQL
        val sql = "SELECT pgmq.purge_queue(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, LongRowMapper).toSingleLongResult() // returns the number of messages purged.
    }

    /**
     * Sends a message to the specified queue with optional headers and delay.
     *
     * The message can include additional metadata in the form of headers, and its delivery
     * can be delayed by the specified duration. This method returns the unique identifier
     * of the enqueued message upon successful delivery.
     *
     * @param queue The name of the queue to which the message should be sent.
     * @param message The content of the message to be sent to the queue.
     * @param headers Optional metadata to include with the message, represented as a map of key-value pairs.
     * @param delay The duration to delay the message delivery, with a default value of 0 seconds.
     * @return A [Result] containing the unique ID of the enqueued message if the operation is successful.
     *         Otherwise, it contains an error indicating the failure reason.
     */
    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = with(pg) { send(queue, message, headers, delay) }

    /**
     * Sends a message to the specified queue with optional headers and delay.
     *
     * @param queue The name of the queue to which the message will be sent.
     * @param message The message object to be sent to the queue.
     * @param headers Optional metadata to include with the message, provided as a map of key-value pairs.
     * @param delay The delay duration before the message is processed. Default is 0 seconds.
     * @return A [Result] object containing the message ID as a [Long] if the operation is successful.
     */
    suspend fun send(
        queue: String,
        message: Any,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, options.json.serialize(message), headers, delay)

    /**
     * Sends a message to the specified queue with optional headers and delay.
     *
     * @param queue The name of the target queue to send the message to.
     * @param message The message to be sent. It can be any serializable object.
     * @param headers Optional headers to include with the message. Defaults to an empty map.
     * @param delay Optional delay before the message is sent, specified as a Duration. Defaults to zero seconds.
     * @return A Result containing the message ID as a Long if the operation is successful, or an error if it fails.
     */
    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: Any,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = send(queue, options.json.serialize(message), headers, delay)

    /**
     * Sends a message to the specified queue with optional headers and delay.
     *
     * This method enqueues a message into the designated queue, optionally including metadata
     * (headers) and specifying a delay before the message becomes available for processing.
     * The method returns the unique ID of the message upon successful delivery.
     *
     * @param queue The name of the queue where the message should be sent.
     * @param message The actual content of the message to be sent to the queue.
     * @param headers Optional metadata associated with the message, represented as a map of key-value pairs. Defaults to an empty map.
     * @param delay The delay before the message becomes available, specified as a duration. Defaults to 0 seconds.
     * @return A [Result] containing the unique identifier of the enqueued message on success, or an error if the operation fails.
     */
    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> {
        // language=SQL
        val sql = "SELECT pgmq.send(queue_name := ?, msg := ?, headers := ?, delay := ?)"
        val statement = Statement.create(sql)
            .bind(0, queue)
            .bind(1, message)
            .bind(2, headers.toJsonString())
            .bind(3, delay.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper).toSingleLongResult() // returns the message-id.
    }

    /**
     * Sends multiple messages to the specified queue with optional headers and delay.
     *
     * This method enqueues a batch of messages into the designated queue, optionally including metadata
     * (headers) and specifying a delay before the messages become available for processing. Each message
     * in the batch will be associated with a unique ID upon successful delivery.
     *
     * @param queue The name of the queue where the messages should be sent.
     * @param messages A list of message contents that should be sent to the queue.
     * @param headers Optional metadata to associate with the messages, represented as a map of key-value pairs. Defaults to an empty map.
     * @param delay The duration to delay the messages before they become available, specified as a [Duration]. Defaults to 0 seconds.
     * @return A [Result] containing a list of unique identifiers (IDs) for the enqueued messages on success, or an error if the operation fails.
     */
    suspend fun send(
        queue: String,
        messages: List<String>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<List<Long>> = with(pg) { send(queue, messages, headers, delay) }

    /**
     * Sends a list of messages to the specified queue with optional headers and delay.
     *
     * @param queue The name of the target queue to which the messages will be sent.
     * @param messages A list of objects representing the messages to be sent.
     * @param headers A map of key-value pairs representing optional headers to include with the messages. Defaults to an empty map.
     * @param delay The delay duration before sending the messages. Defaults to 0 seconds.
     * @return A Result encapsulating a list of unique identifiers for the sent messages.
     */
    suspend fun send(
        queue: String,
        messages: List<Any>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<List<Long>> = send(queue, messages.map { options.json.serialize(it) }, headers, delay)

    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        messages: List<Any>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<List<Long>> = send(queue, messages.map { options.json.serialize(it) }, headers, delay)

    /**
     * Sends a batch of messages to the specified queue with optional headers and a delay.
     *
     * @param queue The name of the queue to which the messages should be sent.
     * @param messages A list of messages to send to the queue.
     * @param headers An optional map of headers to include for the messages. Defaults to an empty map.
     * @param delay An optional delay duration before messages are sent. Defaults to 0 seconds.
     * @return A [Result] containing a list of message IDs that were successfully sent.
     */
    context(db: QueryExecutor)
    suspend fun send(
        queue: String,
        messages: List<String>,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.send_batch(queue_name := ?, msgs := ARRAY[?]::jsonb[], headers := ?, delay := ?)"
        val statement = Statement.create(sql)
            .bind(0, queue)
            .bind(1, NoWrappingTuple(messages))
            .bind(2, headers.toJsonString())
            .bind(3, delay.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper) // returns the message-ids.
    }

    /**
     * Removes and retrieves messages from the specified queue.
     *
     * @param queue The name of the queue from which messages will be popped.
     * @param quantity The number of messages to pop from the queue. Defaults to 1.
     * @return A Result containing a list of messages removed from the queue.
     */
    suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> = with(pg) { pop(queue, quantity) }

    /**
     * Removes and retrieves messages from the specified queue.
     *
     * @param queue The name of the queue from which messages are to be popped.
     * @param quantity The number of messages to pop from the queue; defaults to 1 if not specified.
     * @return A `Result` containing a list of messages retrieved from the queue.
     */
    context(db: QueryExecutor)
    suspend fun pop(queue: String, quantity: Int = 1): Result<List<Message>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.pop(queue_name := ?, qty := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, quantity)
        return db.fetchAll(statement, MessageRowMapper)
    }

    /**
     * Reads messages from a specified queue.
     *
     * @param queue The name of the queue to read messages from.
     * @param quantity The number of messages to read. Defaults to 1 if not provided.
     * @param vt The visibility timeout duration for the messages. Defaults to 30 seconds if not provided.
     * @return A [Result] containing a list of messages read from the queue.
     */
    suspend fun read(queue: String, quantity: Int = 1, vt: Duration = 30.seconds): Result<List<Message>> =
        with(pg) { read(queue, quantity, vt) }

    /**
     * Reads messages from a specified queue with the given parameters.
     *
     * @param queue The name of the queue from which messages are to be read.
     * @param quantity The number of messages to read from the queue. Defaults to 1 if not specified.
     * @param vt The visibility timeout duration for the messages. Defaults to 30 seconds if not specified.
     * @return A `Result` containing a list of read messages.
     */
    context(db: QueryExecutor)
    suspend fun read(queue: String, quantity: Int = 1, vt: Duration = 30.seconds): Result<List<Message>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.read(queue_name := ?, qty := ?, vt := ?)"
        val statement = Statement.create(sql)
            .bind(0, queue)
            .bind(1, quantity)
            .bind(2, vt.inWholeSeconds)
        return db.fetchAll(statement, MessageRowMapper)
    }

    /**
     * Archives an item identified by its ID from a specified queue.
     *
     * @param queue The name of the queue from which the item will be archived.
     * @param id The unique identifier of the item to be archived.
     * @return A [Result] containing a boolean which indicates whether the archiving process was successful.
     */
    suspend fun archive(queue: String, id: Long): Result<Boolean> = with(pg) { archive(queue, id) }

    /**
     * Archives the specified items identified by their IDs from the given queue.
     *
     * @param queue The name of the queue from which the items should be archived.
     * @param ids A list of unique identifiers representing the items to be archived.
     * @return A [Result] containing a list of successfully archived item IDs or an error if the operation fails.
     */
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { archive(queue, ids) }

    /**
     * Archives a message in the specified queue by calling the `pgmq.archive` function.
     *
     * @param queue The name of the queue where the message resides.
     * @param id The unique identifier of the message to archive.
     * @return A result encapsulating a boolean value, where `true` indicates
     *         successful archival and `false` indicates failure or no action taken.
     */
    context(db: QueryExecutor)
    suspend fun archive(queue: String, id: Long): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.archive(queue_name := ?, msg_id := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id)
        return db.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    /**
     * Archives messages identified by their IDs from a specified queue.
     *
     * @param queue The name of the queue from which messages are to be archived.
     * @param ids A list of message IDs to archive.
     * @return A [Result] containing a list of IDs that were successfully archived.
     */
    context(db: QueryExecutor)
    suspend fun archive(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.archive(queue_name := ?, msg_ids := ARRAY[?])"
        val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val archived = it.all { id -> id in ids }
            check(archived) { "Some of the given ids could not be archived." }
            it
        }
    }

    /**
     * Deletes an item with the specified ID from the given queue.
     *
     * @param queue The name of the queue from which the item will be deleted.
     * @param id The unique identifier of the item to be deleted.
     * @return A [Result] indicating whether the deletion was successful as a Boolean.
     */
    suspend fun delete(queue: String, id: Long): Result<Boolean> = with(pg) { delete(queue, id) }

    /**
     * Deletes the specified items from the given queue.
     *
     * @param queue The name of the queue from which items will be deleted.
     * @param ids A list of identifiers representing the items to be deleted from the queue.
     * @return A [Result] containing a list of successfully deleted item identifiers.
     */
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> = with(pg) { delete(queue, ids) }

    /**
     * Deletes a message from the specified queue based on its unique identifier.
     *
     * @param queue the name of the queue from which the message should be deleted
     * @param id the unique identifier of the message to delete
     * @return a Result wrapping a Boolean indicating whether the deletion was successful
     */
    context(db: QueryExecutor)
    suspend fun delete(queue: String, id: Long): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.delete(queue_name := ?, msg_id := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id)
        return db.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    /**
     * Deletes messages from the specified queue based on the provided list of IDs.
     *
     * @param queue The name of the queue from which messages should be deleted.
     * @param ids A list of message IDs to be deleted.
     * @return A Result containing a list of IDs that were successfully deleted. If not all IDs
     *         could be deleted, the operation will fail with an exception.
     */
    context(db: QueryExecutor)
    suspend fun delete(queue: String, ids: List<Long>): Result<List<Long>> {
        // language=SQL
        val sql = "SELECT pgmq.delete(queue_name := ?, msg_ids := ARRAY[?])"
        val statement = Statement.create(sql).bind(0, queue).bind(1, NoWrappingTuple(ids))
        return db.fetchAll(statement, LongRowMapper).mapCatching {
            val deleted = it.all { id -> id in ids }
            check(deleted) { "Some of the given ids could not be deleted." }
            it
        }
    }

    /**
     * Updates the visibility timeout (VT) for a specific item in the given queue.
     *
     * @param queue The name of the queue containing the item.
     * @param id The identifier of the item whose visibility timeout should be updated.
     * @param vt The new visibility timeout value as a Duration.
     * @return A Result wrapping the updated identifier of the item or an error if the operation fails.
     */
    suspend fun setVt(queue: String, id: Long, vt: Duration): Result<Long> = with(pg) { setVt(queue, id, vt) }

    /**
     * Updates the visibility timeout (VT) for a message in the specified queue.
     *
     * This method interacts with the database to set a new visibility timeout for a given message ID
     * within the specified queue, where the timeout is defined in seconds.
     *
     * @param queue The name of the queue where the message exists.
     * @param id The unique identifier of the message for which the visibility timeout is being updated.
     * @param vt The new visibility timeout represented as a Duration.
     * @return A Result containing the message ID of the message whose visibility timeout was updated.
     */
    context(db: QueryExecutor)
    suspend fun setVt(queue: String, id: Long, vt: Duration): Result<Long> {
        // language=SQL
        val sql = "SELECT msg_id FROM pgmq.set_vt(queue_name := ?, msg_id := ?, vt := ?)"
        val statement = Statement.create(sql).bind(0, queue).bind(1, id).bind(2, vt.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper).toSingleLongResult()
    }

    /**
     * Retrieves metrics for the specified queue.
     *
     * @param queue The name of the queue for which to retrieve metrics.
     * @return A [Result] containing the metrics for the specified queue. If no metrics are found, or if multiple metrics
     *         are retrieved for the queue, an error is returned.
     */
    suspend fun metrics(queue: String): Result<Metrics> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.metrics(queue_name := ?)"
        val statement = Statement.create(sql).bind(0, queue)
        return pg.fetchAll(statement, MetricsRowMapper).mapCatching {
            check(it.isNotEmpty()) { "No metrics found for queue '$queue'." }
            check(it.size == 1) { "Multiple metrics found for queue '$queue'." }
            it.first()
        }
    }

    /**
     * Retrieves a list of metrics from the database using the `pgmq.metrics()` query.
     *
     * @return a [Result] containing a list of [Metrics] objects if the retrieval is successful, or an error result otherwise.
     */
    suspend fun metrics(): Result<List<Metrics>> {
        // language=SQL
        val sql = "SELECT * FROM pgmq.metrics()"
        return pg.fetchAll(sql, MetricsRowMapper)
    }

    /**
     * Acknowledges a message in the specified queue with the given ID by performing a delete operation.
     *
     * @param queue The name of the queue containing the message to be acknowledged.
     * @param id The unique identifier of the message to be acknowledged.
     * @return A [Result] containing `true` if the operation was successful, or `false` otherwise.
     */
    suspend fun ack(queue: String, id: Long): Result<Boolean> = delete(queue, id)

    /**
     * Acknowledges the provided list of message IDs for the specified queue.
     *
     * @param queue The name of the queue where the messages are acknowledged.
     * @param ids A list of message IDs to acknowledge within the given queue.
     * @return A [Result] containing the list of successfully acknowledged message IDs.
     */
    suspend fun ack(queue: String, ids: List<Long>): Result<List<Long>> = delete(queue, ids)

    /**
     * Sends a negative acknowledgment (nack) to the specified queue for the given message ID, optionally resetting
     * the visibility timeout (VT) to a new duration.
     *
     * @param queue The name of the queue to which the negative acknowledgment is sent.
     * @param id The unique identifier of the message to be negatively acknowledged.
     * @param vt The duration to set for the new visibility timeout after the nack. Defaults to zero duration.
     * @return A [Result] containing the new visibility timeout, wrapped in a success or failure state.
     */
    suspend fun nack(queue: String, id: Long, vt: Duration = Duration.ZERO): Result<Long> = setVt(queue, id, vt)

    /**
     * Creates a topic binding between a pattern and a queue.
     *
     * Topic bindings use AMQP-style wildcard patterns to route messages:
     * - `*` (star) matches exactly ONE segment between dots
     * - `#` (hash) matches ZERO or MORE segments
     *
     * The pattern is automatically validated before insertion, and the operation
     * is idempotent (safe to call multiple times with the same arguments).
     *
     * @param pattern The AMQP-style wildcard pattern for routing key matching.
     *                Examples: "logs.*", "logs.#", "*.error", "#.critical"
     * @param queueName The name of the queue that will receive matching messages.
     * @return A [Result] containing [Unit] on success, or an error if validation fails.
     */
    suspend fun bindTopic(pattern: String, queueName: String): Result<Unit> = with(pg) { bindTopic(pattern, queueName) }

    /**
     * Creates a topic binding between a pattern and a queue.
     *
     * This is the context version that can be used within a transaction or query executor.
     *
     * @param pattern The AMQP-style wildcard pattern for routing key matching.
     * @param queueName The name of the queue that will receive matching messages.
     * @return A [Result] containing [Unit] on success, or an error if validation fails.
     */
    context(db: QueryExecutor)
    suspend fun bindTopic(pattern: String, queueName: String): Result<Unit> {
        // language=SQL
        val sql = "SELECT pgmq.bind_topic(pattern := ?, queue_name := ?)"
        val statement = Statement.create(sql).bind(0, pattern).bind(1, queueName)
        return db.fetchAll(statement, UnitRowMapper).toSingleUnitResult()
    }

    /**
     * Creates topic bindings for multiple patterns to a single queue.
     *
     * This operation is performed atomically within a transaction. Either all bindings
     * are created successfully, or none are.
     *
     * @param patterns A list of AMQP-style wildcard patterns for routing key matching.
     *                 Examples: listOf("logs.*", "logs.#", "*.error", "#.critical")
     * @param queueName The name of the queue that will receive matching messages.
     * @return A [Result] containing [Unit] on success, or an error if any binding fails.
     */
    suspend fun bindTopic(patterns: List<String>, queueName: String): Result<Unit> {
        return pg.transaction {
            patterns.forEach { pattern -> bindTopic(pattern, queueName).getOrThrow() }
            Result.success(Unit)
        }
    }

    /**
     * Removes a topic binding between a pattern and a queue.
     *
     * This operation is idempotent (safe to call multiple times with the same arguments).
     *
     * @param pattern The pattern to unbind from the queue.
     * @param queueName The name of the queue to unbind from the pattern.
     * @return A [Result] containing `true` if a binding was removed, `false` if no matching binding was found.
     */
    suspend fun unbindTopic(pattern: String, queueName: String): Result<Boolean> {
        // language=SQL
        val sql = "SELECT pgmq.unbind_topic(pattern := ?, queue_name := ?)"
        val statement = Statement.create(sql).bind(0, pattern).bind(1, queueName)
        return pg.fetchAll(statement, BooleanRowMapper).toSingleBooleanResult()
    }

    /**
     * Sends a message to all queues that match the routing key pattern.
     *
     * Uses AMQP-style topic routing with wildcards:
     * - The routing_key is matched against all patterns in the topic_bindings table
     * - Messages are sent to ALL matching queues
     * - The operation is atomic: either all matching queues receive the message or none do
     *
     * @param routingKey The routing key for the message (e.g., "logs.error", "app.user.created").
     *                   Must not contain wildcards (* or #).
     * @param message The content of the message to be sent.
     * @param headers Optional metadata to include with the message. Defaults to an empty map.
     * @param delay The duration to delay the message delivery. Defaults to 0 seconds.
     * @return A [Result] containing the number of queues that received the message.
     */
    suspend fun sendTopic(
        routingKey: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = with(pg) { sendTopic(routingKey, message, headers, delay) }

    /**
     * Sends a message to the specified topic using the provided routing key and additional configurations.
     *
     * @param routingKey The routing key used to route the message to the appropriate topic.
     * @param message The message to be sent. The message is serialized before sending.
     * @param headers Optional headers to include with the message in the form of key-value pairs. Defaults to an empty map.
     * @param delay The delay duration before the message is sent. Defaults to no delay.
     * @return A result containing the unique identifier of the sent message if successful.
     */
    suspend fun sendTopic(
        routingKey: String,
        message: Any,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = sendTopic(routingKey, options.json.serialize(message), headers, delay)

    /**
     * Publishes a message to a topic using the provided routing key.
     *
     * @param routingKey The routing key used to route the message.
     * @param message The message to be sent to the topic.
     * @param headers Optional headers to include with the message. Defaults to an empty map.
     * @param delay An optional delay before sending the message. Defaults to 0 seconds.
     * @return A [Result] containing the message ID on successful publishing, or an error if the operation fails.
     */
    context(db: QueryExecutor)
    suspend fun sendTopic(
        routingKey: String,
        message: Any,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> = sendTopic(routingKey, options.json.serialize(message), headers, delay)

    /**
     * Sends a message to all queues that match the routing key pattern.
     *
     * This is the context version that can be used within a transaction or query executor.
     *
     * @param routingKey The routing key for the message.
     * @param message The content of the message to be sent.
     * @param headers Optional metadata to include with the message.
     * @param delay The duration to delay the message delivery.
     * @return A [Result] containing the number of queues that received the message.
     */
    context(db: QueryExecutor)
    suspend fun sendTopic(
        routingKey: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
        delay: Duration = 0.seconds
    ): Result<Long> {
        // language=SQL
        val sql = "SELECT pgmq.send_topic(routing_key := ?, msg := ?, headers := ?, delay := ?)"
        val statement = Statement.create(sql)
            .bind(0, routingKey)
            .bind(1, message)
            .bind(2, headers.toJsonString())
            .bind(3, delay.inWholeSeconds)
        return db.fetchAll(statement, LongRowMapper).toSingleLongResult()
    }

    /**
     * Represents a queue configuration with specific attributes.
     *
     * @property name The name of the queue. It must be a non-empty and non-blank string.
     * @property unlogged A flag indicating whether the queue operates in an unlogged mode. Defaults to `false`.
     * @property enableNotifyInsert A flag specifying if notifications should be enabled for insert operations. Defaults to `false`.
     * @property throttleNotifyInterval The interval between consecutive insertion notification throttling checks. Must be positive
     *                                   and less than 1 second. Defaults to 100 milliseconds.
     * @throws IllegalArgumentException if the `name` is empty or blank.
     */
    data class Queue(
        val name: String,
        val unlogged: Boolean = false,
        val enableNotifyInsert: Boolean = false,
        val throttleNotifyInterval: Duration = 250.milliseconds,
    ) {
        init {
            require(name.isNotEmpty()) { "Queue name must not be empty" }
            require(name.isNotBlank()) { "Queue name must not be blank" }
            require(throttleNotifyInterval >= Duration.ZERO) { "ThrottleNotifyInterval must not be negative" }
            require(throttleNotifyInterval.inWholeMilliseconds <= 1000) { "ThrottleNotifyInterval must not be greater than 1 second" }
        }
    }

    /**
     * A data class that represents configuration options for an installation process.
     *
     * You can download the migration files from here:
     * https://github.com/pgmq/pgmq/tree/main/pgmq-extension/sql
     *
     * @property autoInstall Determines whether the installation process should proceed automatically.
     * @property verifyInstallation Indicates whether the installation should be verified post-process.
     * @property installFromFiles Indicates whether the installation should be performed from SQL files.
     * @property intallFilesPath Path to the directory containing SQL files for installation.
     * @property json Serializer for JSON payloads.
     */
    data class Options(
        val autoInstall: Boolean = true,
        val verifyInstallation: Boolean = true,
        val installFromFiles: Boolean = false,
        val intallFilesPath: String = "./pgmq",
        val json: PgMqDbJsonSerializer = PgMqDbJsonSerializer.Default
    )
}
