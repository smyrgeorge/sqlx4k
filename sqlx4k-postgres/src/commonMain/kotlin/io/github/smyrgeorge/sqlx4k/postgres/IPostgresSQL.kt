package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver

interface IPostgresSQL : Driver {

    /**
     * Listens for notifications on a specified PostgreSQL channel and executes a callback for each received notification.
     *
     * @param channel The name of the PostgreSQL channel to listen on. The channel name must be valid according to PostgreSQL's naming rules.
     * @param f The callback function to be executed whenever a notification is received on the specified channel.
     *          The callback receives a `Notification` object containing details of the notification.
     */
    suspend fun listen(channel: String, f: (Notification) -> Unit)

    /**
     * Listens for notifications on specified PostgreSQL channels and executes a callback for each received notification.
     *
     * @param channels A list of PostgreSQL channel names to listen on. Each channel name must be valid according to PostgreSQL's naming rules.
     * @param f The callback function to execute whenever a notification is received on any of the specified channels.
     *          The callback receives a `Notification` object containing details of the notification.
     */
    suspend fun listen(channels: List<String>, f: (Notification) -> Unit)

    /**
     * Publishes a notification to a specified PostgreSQL channel.
     *
     * This method sends a notification with a given payload to a PostgreSQL channel,
     * allowing clients listening to the channel to receive the notification.
     *
     * @param channel The name of the PostgreSQL channel to which the notification will be sent.
     *                The channel name must conform to PostgreSQL's naming rules.
     * @param value The payload of the notification to be sent. This value will be delivered
     *              to clients listening on the specified channel.
     */
    suspend fun notify(channel: String, value: String)

    /**
     * Validates the name of a PostgreSQL channel to ensure it adheres to the naming rules.
     *
     * A valid PostgreSQL channel name:
     * - Must start with a letter or an underscore.
     * - May be followed by letters, digits, or underscores.
     * - Has a maximum length of 63 characters.
     *
     * @param channel The name of the PostgreSQL channel to be validated.
     * @throws IllegalArgumentException If the channel name is invalid or does not conform to the rules.
     */
    fun validateChannelName(channel: String) {
        require(channel.isNotBlank()) { "Channel cannot be blank." }
        require(channel.matches(CHANNEL_NAME_PATTERN)) {
            """Invalid channel name: $channel. Channel names must start with a letter or underscore,
                    |followed by letters, digits, or underscores, with a maximum length of 63 characters.""".trimMargin()
        }
    }

    companion object {
        /**
         * Regular expression pattern for validating PostgreSQL channel names.
         * Channel names must start with a letter or underscore, followed by letters,
         * digits, or underscores, with a maximum length of 63 characters.
         */
        private val CHANNEL_NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]{0,62}$".toRegex()
    }
}