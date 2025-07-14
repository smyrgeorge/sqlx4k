package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.Driver

interface IPostgresSQL : Driver, Driver.Pool, Driver.Transactional, Driver.Migrate {
    suspend fun listen(channel: String, f: (Notification) -> Unit)
    suspend fun listen(channels: List<String>, f: (Notification) -> Unit)
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