package io.github.smyrgeorge.sqlx4k.postgres.pgmq

interface PgMqDbJsonSerializer {
    fun serialize(payload: Any): String

    object Default : PgMqDbJsonSerializer {
        override fun serialize(payload: Any): String =
            when (payload) {
                is String -> payload
                else -> error("Unsupported payload type: ${payload::class}. Please provide a custom serializer.")
            }
    }
}