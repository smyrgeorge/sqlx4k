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
    const val ARROW_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.ArrowCrudRepository"
    const val CONTEXT_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.ContextCrudRepository"
    const val ARROW_CONTEXT_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.ArrowContextCrudRepository"

    val REPOSITORY_TYPE_NAMES = setOf(
        CRUD_REPOSITORY,
        ARROW_CRUD_REPOSITORY,
        CONTEXT_CRUD_REPOSITORY,
        ARROW_CONTEXT_CRUD_REPOSITORY,
    )

    val CONTEXT_REPOSITORY_TYPE_NAMES = setOf(
        CONTEXT_CRUD_REPOSITORY,
        ARROW_CONTEXT_CRUD_REPOSITORY,
    )

    val ARROW_REPOSITORY_TYPE_NAMES = setOf(
        ARROW_CRUD_REPOSITORY,
        ARROW_CONTEXT_CRUD_REPOSITORY,
    )

    const val QUERY_EXECUTOR = "io.github.smyrgeorge.sqlx4k.QueryExecutor"
    const val STATEMENT = "io.github.smyrgeorge.sqlx4k.Statement"

    // Sqlx4k types
    const val SQL_ERROR = "io.github.smyrgeorge.sqlx4k.SQLError"
    const val DB_RESULT = "io.github.smyrgeorge.sqlx4k.impl.extensions.DbResult"
    const val TO_DB_RESULT = "io.github.smyrgeorge.sqlx4k.impl.extensions.toDbResult"

    // Kotlin stdlib
    const val KOTLIN_RESULT = "kotlin.Result"
    const val KOTLIN_LIST = "kotlin.collections.List"
    const val KOTLIN_LONG = "kotlin.Long"
    const val KOTLIN_INT = "kotlin.Int"
}
