package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PgMqConsumer(
    private val pgmq: PgMqClient,
    private val options: Options,
    private val onMessage: suspend (Message) -> Unit,
    private val onFaiToRead: suspend (Throwable) -> Unit = {},
    private val onFailToProcess: suspend (Throwable) -> Unit = {},
    private val onFaiToAck: suspend (Throwable) -> Unit = {},
    private val onFaiToNack: suspend (Throwable) -> Unit = {},
) {
    private var notifyJob: Job? = null
    private var fetchJob: Job? = null
    private var fetchDelay = Duration.ZERO
    private var fetchDelayJob: Job? = null
    private var consumeJob: Job? = null
    private lateinit var consumeChannel: Channel<Message>

    init {
        if (options.autoStart) start()
    }

    fun start() {
        if (notifyJob == null && options.enableNotifyInsert) startNotify()
        if (consumeJob == null) startConsume()
        if (fetchJob == null) startFetch()
    }

    private fun startNotify() {
        notifyJob = PgChannelScope.launch {
            pgmq.pg.listen(options.listenChannel) {
                fetchDelay = Duration.ZERO
                fetchDelayJob?.cancel()
            }
        }
    }

    private fun startConsume() {
        consumeChannel = Channel(capacity = options.prefetch)
        consumeJob = PgChannelScope.launch {
            consumeChannel.consumeEach { msg ->
                val res = runCatching {
                    // Process message in a timeout.
                    withTimeout(options.vt) { onMessage(msg) }
                }

                res.onFailure { f ->
                    runCatching { onFailToProcess(f) }
                    val step = options.messageRetryDelayStep * msg.readCt
                    val vt = step.coerceAtMost(options.messageMaxRetryDelay)
                    pgmq.nack(options.queue, msg.msgId, vt).onFailure { runCatching { onFaiToNack(it) } }
                }
                res.onSuccess {
                    pgmq.ack(options.queue, msg.msgId).onFailure { runCatching { onFaiToAck(it) } }
                }
            }
        }
    }

    private fun startFetch() {
        fetchJob = PgChannelScope.launch {
            while (true) {
                val messages = pgmq.read(options.queue, options.prefetch, options.vtBias).getOrElse {
                    onFaiToRead(it)
                    emptyList()
                }
                if (messages.isNotEmpty()) {
                    // Process messages immediately.
                    messages.forEach { consumeChannel.send(it) }
                    // reset delay since queue is active
                    fetchDelay = Duration.ZERO
                } else {
                    // no messages — back off
                    if (fetchDelay == Duration.ZERO) fetchDelay = options.queueMinPullDelay
                    fetchDelay = (fetchDelay * 2).coerceAtMost(options.queueMaxPullDelay)
                }

                fetchDelayJob = launch { runCatching { delay(fetchDelay) } }
                fetchDelayJob?.join()
            }
        }
    }

    fun stop() {
        PgChannelScope.launch {
            consumeChannel.cancel()
            delay(500.milliseconds)
            consumeJob?.cancel()
            consumeJob = null
            fetchDelayJob?.cancel()
            fetchDelayJob = null
            fetchDelay = Duration.ZERO
            delay(500.milliseconds)
            fetchJob?.cancel()
            fetchJob = null
        }
    }

    suspend fun metrics(): Result<Metrics> = pgmq.metrics(options.queue)

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

    companion object {
        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }
    }
}