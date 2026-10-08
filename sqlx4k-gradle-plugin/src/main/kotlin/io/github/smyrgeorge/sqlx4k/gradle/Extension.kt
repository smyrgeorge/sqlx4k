package io.github.smyrgeorge.sqlx4k.gradle

/**
 * An sqlx4k extension library, enabled through `sqlx4k { extensions(...) }` (see [Sqlx4kExtension.extensions]).
 *
 * Parameterless extensions are Kotlin objects (e.g. [Pgmq]); extensions requiring configuration
 * are added as classes whose constructor takes the options.
 */
public sealed interface Extension {

    /** The PGMQ client (`sqlx4k-postgres-pgmq`): message queueing on PostgreSQL. PostgreSQL only. */
    public object Pgmq : Extension {
        override fun toString(): String = "Pgmq"
    }

    /** The Arrow integration (`sqlx4k-arrow`): `Either`-based result handling. */
    public object Arrow : Extension {
        override fun toString(): String = "Arrow"
    }
}

/** The sqlx4k artifact that an [Extension] adds. */
internal val Extension.artifact: String
    get() = when (this) {
        Extension.Pgmq -> "sqlx4k-postgres-pgmq"
        Extension.Arrow -> "sqlx4k-arrow"
    }

/** The driver an [Extension] requires, or `null` when it works with every driver. */
internal val Extension.requiredDriver: Driver?
    get() = when (this) {
        Extension.Pgmq -> Driver.PostgreSQL
        Extension.Arrow -> null
    }
