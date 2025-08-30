package io.github.smyrgeorge.sqlx4k.annotation

/**
 * Marker annotation for repository interfaces to be processed by sqlx4k codegen.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Repository
