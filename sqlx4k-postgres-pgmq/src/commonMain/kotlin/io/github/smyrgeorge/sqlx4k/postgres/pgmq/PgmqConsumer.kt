@file:OptIn(ExperimentalAtomicApi::class)

package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PgMqConsumer is responsible for consuming messages from a PostgreSQL-based
 * message queue (PgMq). It provides configurable options for queue management
 * and handles message processing, acknowledging, and retrying logic.
 *
 * @constructor
 * Creates an instance of PgMqConsumer with the provided PgMq client, configuration options,
 * and callbacks for message processing and error handling.
 *
 * @property pgmq An instance of the PgMqClient used for accessing the message queue.
 * @property options Configuration options for the consumer behavior, such as queue name,
 * prefetch size, visibility timeout, and retry delays.
 * @property onMessage A suspendable callback invoked to process each message received from the queue.
 * @property onFailToRead A suspendable callback invoked when a failure occurs while reading messages from the queue.
 * @property onFailToProcess A suspendable callback invoked when a failure occurs while processing a message.
 * @property onFailToAck A suspendable callback invoked when a failure occurs while acknowledging (ack) a message.
 * @property onFailToNack A suspendable callback invoked when a failure occurs while negative acknowledging (nack) a message.
 */
class PgmqConsumer(
    private val pgmq: PgmqClient,
    private val options: Options,
    private val onMessage: suspend (Message) -> Unit,
    private val onFailToRead: suspend (Throwable) -> Unit = {},
    private val onFailToProcess: suspend (Throwable) -> Unit = {},
    private val onFailToAck: suspend (Throwable) -> Unit = {},
    private val onFailToNack: suspend (Throwable) -> Unit = {},
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("PgmqConsumer(${options.queue})")
    )
    private var notifyJob: Job? = null
    private var fetchJob: Job? = null
    private val fetchDelay = AtomicReference(Duration.ZERO)

    // Conflated wake-up signal: an insert notification offers to it, the fetch loop waits on it.
    // Conflation makes the hand-off race-free — a signal sent while the loop is not waiting is
    // retained and consumed by the next wait, so notifications are never lost.
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    private var consumeJob: Job? = null
    private lateinit var consumeChannel: Channel<Message>

    init {
        if (options.autoStart) start()
    }

    /**
     * Starts the message queue consumer by initializing and launching processes for notification,
     * consumption, and fetching, if they are not already running.
     *
     * - The notification process is responsible for handling insertion notifications.
     * - The consumption process manages message processing from the queue.
     * - The fetching process retrieves messages from the queue for processing.
     *
     * This method ensures that all required jobs (notify, consume, fetch) are properly started
     * to maintain the functionality of the consumer.
     */
    fun start() {
        if (notifyJob == null && options.enableNotifyInsert) startNotify()
        if (consumeJob == null) startConsume()
        if (fetchJob == null) startFetch()
    }

    private fun startNotify() {
        notifyJob = scope.launch {
            pgmq.pg.listen(options.listenChannel) {
                // Reset the backoff and wake the fetch loop immediately (if it is currently waiting).
                fetchDelay.store(Duration.ZERO)
                wakeup.trySend(Unit)
            }
        }
    }

    private fun startConsume() {
        consumeChannel = Channel(capacity = options.prefetch)
        consumeJob = scope.launch {
            consumeChannel.consumeEach { msg ->
                // Process the message within a timeout. Only a genuine processing timeout or
                // failure should trigger a nack; a CancellationException means the consumer is
                // shutting down, so it must propagate instead of being treated as a failure.
                val res = try {
                    withTimeout(options.vt) { onMessage(msg) }
                    Result.success(Unit)
                } catch (e: TimeoutCancellationException) {
                    Result.failure(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }

                res.onFailure { f ->
                    runCatching { onFailToProcess(f) }
                    val step = options.messageRetryDelayStep * msg.readCt
                    // pgmq visibility timeout has whole-second granularity; round up so sub-second
                    // retry delays don't truncate to 0 (which would cause immediate redelivery).
                    val vt = step.coerceAtMost(options.messageMaxRetryDelay).ceilToWholeSeconds()
                    pgmq.nack(options.queue, msg.msgId, vt).onFailure { runCatching { onFailToNack(it) } }
                }
                res.onSuccess {
                    pgmq.ack(options.queue, msg.msgId).onFailure { runCatching { onFailToAck(it) } }
                }
            }
        }
    }

    private fun startFetch() {
        fetchJob = scope.launch {
            while (true) {
                val messages = pgmq.read(options.queue, options.prefetch, options.vtBias).getOrElse {
                    runCatching { onFailToRead(it) }
                    emptyList()
                }
                // Forward whatever we read for processing (applies natural backpressure via the channel).
                messages.forEach { consumeChannel.send(it) }

                // Decide how long to wait before the next read:
                // - full batch   -> more messages are likely waiting, poll again immediately
                // - partial read -> queue is (almost) drained, pause briefly so a slow trickle
                //                   doesn't turn into a tight read loop that hammers the database
                // - empty read   -> exponential backoff from queueMinPullDelay up to queueMaxPullDelay
                val next = when {
                    messages.size >= options.prefetch -> Duration.ZERO
                    messages.isNotEmpty() -> options.queueMinPullDelay
                    else -> {
                        val current = fetchDelay.load()
                        if (current == Duration.ZERO) options.queueMinPullDelay
                        else (current * 2).coerceAtMost(options.queueMaxPullDelay)
                    }
                }
                fetchDelay.store(next)

                // Wait for the backoff to elapse, unless woken early by an insert notification.
                // No coroutine is allocated on the hot path where next == ZERO.
                if (next > Duration.ZERO) withTimeoutOrNull(next) { wakeup.receive() }
            }
        }
    }

    /**
     * Stops the message queue consumer, gracefully shutting down all of its jobs and awaiting their completion.
     *
     * The shutdown proceeds in the following order:
     * - The notification job (`notifyJob`) is cancelled so no further insert notifications are processed.
     * - The fetching job (`fetchJob`) is cancelled first, stopping the consumer from pulling in new work,
     *   and the fetch delay is reset to zero.
     * - The consume channel is closed so any already-buffered messages are drained (rather than dropped)
     *   and the consume job (`consumeJob`) is awaited until it finishes processing them.
     *
     * This method suspends until the shutdown has fully completed, so the caller can rely on the consumer
     * being stopped once it returns. The consumer can be started again afterwards via [start].
     */
    suspend fun stop() {
        notifyJob?.cancelAndJoin()
        notifyJob = null
        fetchJob?.cancelAndJoin()
        fetchJob = null
        fetchDelay.store(Duration.ZERO)
        if (::consumeChannel.isInitialized) consumeChannel.close()
        consumeJob?.join()
        consumeJob = null
    }

    private fun Duration.ceilToWholeSeconds(): Duration {
        val seconds = inWholeSeconds
        return if (this == seconds.seconds) this else (seconds + 1).seconds
    }

    /**
     * Retrieves metrics for the current queue managed by the consumer.
     *
     * @return A [Result] containing a [Metrics] object with detailed metrics of the queue.
     *         The result may contain an error if metrics retrieval fails or is inconsistent.
     */
    suspend fun metrics(): Result<Metrics> = pgmq.metrics(options.queue)

    /**
     * Configuration options for a message queue consumer.
     *
     * @property queue The name of the queue. Must be non-empty and non-blank.
     * @property prefetch The maximum number of messages to prefetch at a time. Must be greater than 0. Defaults to 250.
     * @property vt The visibility timeout duration for a message. Specifies the period after which a message becomes
     *             visible for processing again if it has not been acknowledged. Must be at least 1 second. Defaults to 10 seconds.
     * @property autoStart Indicates if the consumer should automatically start processing messages. Defaults to true.
     * @property enableNotifyInsert Determines whether insertion notifications are enabled for the queue. Defaults to false.
     * @property queueMinPullDelay The minimum delay between consecutive pull operations from the queue. Must be positive
     *                             and less than `queueMaxPullDelay`. Defaults to 50 milliseconds.
     * @property queueMaxPullDelay The maximum delay between consecutive pull operations from the queue. Must be positive
     *                             and greater than `queueMinPullDelay`. Defaults to 2 seconds.
     * @property messageRetryDelayStep The incremental delay applied before retrying a message. Must be positive and less
     *                                 than `messageMaxRetryDelay`. Defaults to 500 milliseconds.
     * @property messageMaxRetryDelay The maximum retry delay for a message. Must be positive. Defaults to 60 seconds.
     * @property vtBias A derived value calculated as `vt * 2`. Represents an adjusted visibility timeout bias.
     * @property listenChannel The channel used for listening to insertion notifications. Derived from the queue name
     *                         and formatted as "pgmq.q_<queue>.INSERT".
     *
     * @throws IllegalArgumentException Thrown if any of the validation conditions for the properties are violated:
     *                                  - `queue` must not be empty or blank.
     *                                  - `prefetch` must be greater than 0.
     *                                  - `vt` duration must be at least 1 second.
     *                                  - `queueMinPullDelay` and `queueMaxPullDelay` must be positive, and
     *                                    `queueMinPullDelay` must be less than `queueMaxPullDelay`.
     *                                  - `messageRetryDelayStep` and `messageMaxRetryDelay` must be positive, and
     *                                    `messageRetryDelayStep` must be less than `messageMaxRetryDelay`.
     */
    data class Options(
        val queue: String,
        val prefetch: Int = 250,
        val vt: Duration = 10.seconds,
        val autoStart: Boolean = true,
        val enableNotifyInsert: Boolean = false,
        val queueMinPullDelay: Duration = 50.milliseconds,
        val queueMaxPullDelay: Duration = 2.seconds,
        val messageRetryDelayStep: Duration = 500.milliseconds,
        val messageMaxRetryDelay: Duration = 60.seconds,
    ) {
        val vtBias: Duration = vt * 2
        val listenChannel: String = "pgmq.q_${queue}.INSERT"

        init {
            require(queue.isNotEmpty()) { "Queue name must not be empty" }
            require(queue.isNotBlank()) { "Queue name must not be blank" }
            require(prefetch > 0) { "Prefetch must be greater than 0" }
            require(vt.inWholeSeconds >= 1) { "VT must be greater (or equals) than 1 second" }
            require(queueMinPullDelay.isPositive()) { "QueueMinPullDelay must be greater than 0" }
            require(queueMaxPullDelay.isPositive()) { "QueueMaxPullDelay must be greater than 0" }
            require(queueMinPullDelay < queueMaxPullDelay) { "QueueMinPullDelay must be less than QueueMaxPullDelay" }
            require(messageRetryDelayStep.isPositive()) { "MessageRetryDelayStep must be greater than 0" }
            require(messageMaxRetryDelay.isPositive()) { "MessageMaxRetryDelay must be greater than 0" }
            require(messageRetryDelayStep < messageMaxRetryDelay) { "MessageRetryDelayStep must be less than MessageMaxRetryDelay" }
        }
    }

    override fun toString(): String = "PgMqConsumer(queue='${options.queue}')"
}
