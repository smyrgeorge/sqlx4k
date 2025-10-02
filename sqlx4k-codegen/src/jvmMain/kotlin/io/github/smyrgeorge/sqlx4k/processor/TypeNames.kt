package io.github.smyrgeorge.sqlx4k.processor

/**
 * Centralized fully qualified names used by codegen processors to avoid magic strings.
 */
object TypeNames {
    // Annotations
    const val REPOSITORY_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Repository"
    const val TABLE_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Table"
    const val ID_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Id"
    const val COLUMN_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Column"
    const val QUERY_ANNOTATION = "io.github.smyrgeorge.sqlx4k.annotation.Query"

    // Core interfaces/classes
    const val CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.CrudRepository"
    const val CONTEXT_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.ContextCrudRepository"
    const val QUERY_EXECUTOR = "io.github.smyrgeorge.sqlx4k.QueryExecutor"
    const val STATEMENT = "io.github.smyrgeorge.sqlx4k.Statement"

    // Kotlin stdlib
    const val KOTLIN_RESULT = "kotlin.Result"
    const val KOTLIN_LIST = "kotlin.collections.List"
    const val KOTLIN_LONG = "kotlin.Long"
    const val KOTLIN_INT = "kotlin.Int"
}
