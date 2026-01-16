package io.github.smyrgeorge.sqlx4k.annotation

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import kotlin.reflect.KClass

/**
 * Annotation to mark a class as a repository and associate it with a specific implementation of a `RowMapper`.
 * This annotation is used to facilitate mapping database rows to objects.
 *
 * @property mapper Specifies the `RowMapper` implementation to be used for mapping rows.
 *        By default, it uses `AutoRowMapper`, which cannot be used directly and will raise an error if invoked.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Repository(val mapper: KClass<out RowMapper<*>> = AutoRowMapper::class) {
    object AutoRowMapper : RowMapper<Any> {
        override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): Any =
            error("AutoRowMapper cannot be used directly. Use a RowMapper<T> instead.")
    }
}
