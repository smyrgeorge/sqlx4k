package io.github.smyrgeorge.sqlx4k.postgres.pgmq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    private val onFaiToDelete: suspend (Throwable) -> Unit = {},
    private val onFailToProcess: suspend (Throwable) -> Unit = {},
) {
    private var delay: Duration = Duration.ZERO
    private var job: Job? = null

    init {
        if (options.autoStart) start()
    }

    fun start() {
        job = PgChannelScope.launch {
            while (true) {
                val messages = pgmq.read(options.queue, options.prefetch, options.vtBias).getOrElse {
                    onFaiToRead(it)
                    emptyList()
                }
                if (messages.isNotEmpty()) {
                    // Process messages immediately.
                    messages.forEach { msg ->
                        val res = runCatching {
                            // Process message in a timeout.
                            withTimeout(options.vt) { onMessage(msg) }
                        }

                        res.onFailure { onFailToProcess(it) }
                        res.onSuccess { pgmq.delete(options.queue, msg.msgId).onFailure { onFaiToDelete(it) } }
                    }

                    // reset delay since queue is active
                    delay = Duration.ZERO
                } else {
                    // no messages — back off
                    if (delay == Duration.ZERO) delay = options.queueMinPullDelay
                    delay = (delay * 2).coerceAtMost(options.queueMaxPullDelay)
                }
                delay(delay)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    data class Options(
        val queue: String,
        val prefetch: Int = 250,
        val vt: Duration = 10.seconds,
        val autoStart: Boolean = true,
        val queueMinPullDelay: Duration = 50.milliseconds,
        val queueMaxPullDelay: Duration = 5.seconds,
    ) {
        val vtBias = vt * 2

        init {
            require(queue.isNotEmpty()) { "Queue name must not be empty" }
            require(queue.isNotBlank()) { "Queue name must not be blank" }
            require(prefetch > 0) { "Prefetch must be greater than 0" }
            require(vt.inWholeSeconds >= 1) { "VT must be greater (or equals) than 1 second" }
            require(queueMinPullDelay.isPositive()) { "QueueMinPullDelay must be greater than 0" }
            require(queueMaxPullDelay.isPositive()) { "QueueMaxPullDelay must be greater than 0" }
            require(queueMinPullDelay < queueMaxPullDelay) { "QueueMinPullDelay must be less than QueueMaxPullDelay" }
        }
    }

    companion object {
        private object PgChannelScope : CoroutineScope {
            override val coroutineContext: CoroutineContext
                get() = EmptyCoroutineContext
        }
    }
}