package io.github.smyrgeorge.sqlx4k.processor

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepositoryHooks
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository

/**
 * Fully qualified names used by [InMemoryRepositoryProcessor].
 *
 * This is intentionally a small, self-contained copy of the equivalent in the `sqlx4k-codegen` module:
 * keeping it here means `sqlx4k-codegen-test` does not depend on the codegen processor module and thus
 * never pulls the real-impl processors (Table/Repository) onto the KSP classpath.
 *
 * Arrow types are referenced by string to avoid a dependency on `sqlx4k-arrow`.
 */
internal object TypeNames {
    // Annotations
    val REPOSITORY_ANNOTATION = Repository::class.qualifiedName!!
    val ID_ANNOTATION = Id::class.qualifiedName!!
    val QUERY_ANNOTATION = Query::class.qualifiedName!!
    val COLUMN_ANNOTATION = Column::class.qualifiedName!!

    // Repository base interfaces
    val CRUD_REPOSITORY_HOOKS = CrudRepositoryHooks::class.qualifiedName!!
    val CRUD_REPOSITORY = CrudRepository::class.qualifiedName!!
    val CONTEXT_CRUD_REPOSITORY = ContextCrudRepository::class.qualifiedName!!
    const val ARROW_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.arrow.ArrowCrudRepository"
    const val ARROW_CONTEXT_CRUD_REPOSITORY = "io.github.smyrgeorge.sqlx4k.arrow.ArrowContextCrudRepository"

    val REPOSITORY_TYPE_NAMES = setOf(
        CRUD_REPOSITORY,
        CONTEXT_CRUD_REPOSITORY,
        ARROW_CRUD_REPOSITORY,
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

    // Sqlx4k / Arrow types referenced by the generated code
    val QUERY_EXECUTOR = QueryExecutor::class.qualifiedName!!
    val SQL_ERROR = SQLError::class.qualifiedName!!
    val STATEMENT = Statement::class.qualifiedName!!
    const val DB_RESULT = "io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.DbResult"
    const val TO_DB_RESULT = "io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.toDbResult"

    // Kotlin stdlib
    val KOTLIN_LONG = Long::class.qualifiedName!!
    val KOTLIN_INT = Int::class.qualifiedName!!
}
