package io.github.smyrgeorge.sqlx4k.annotation

import io.github.smyrgeorge.sqlx4k.RowMapper
import kotlin.reflect.KClass

/**
 * Annotation for repository interfaces to be processed by sqlx4k codegen.
 *
 * @param mapper The RowMapper to be used for mapping rows returned by repository methods.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Repository(
    val mapper: KClass<out RowMapper<*>>
)
