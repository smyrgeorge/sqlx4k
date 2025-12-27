package io.github.smyrgeorge.sqlx4k.processor

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Id
import io.github.smyrgeorge.sqlx4k.annotation.Query
import io.github.smyrgeorge.sqlx4k.annotation.Repository
import io.github.smyrgeorge.sqlx4k.annotation.Table
import io.github.smyrgeorge.sqlx4k.arrow.ArrowContextCrudRepository
import io.github.smyrgeorge.sqlx4k.arrow.ArrowCrudRepository

/**
 * Centralized fully qualified names used by codegen processors to avoid magic strings.
 */
object TypeNames {
    // Annotations
    val REPOSITORY_ANNOTATION = Repository::class.qualifiedName!!
    val TABLE_ANNOTATION = Table::class.qualifiedName!!
    val ID_ANNOTATION = Id::class.qualifiedName!!
    val COLUMN_ANNOTATION = Column::class.qualifiedName!!
    val QUERY_ANNOTATION = Query::class.qualifiedName!!

    // Core interfaces/classes
    val CRUD_REPOSITORY = CrudRepository::class.qualifiedName!!

    @OptIn(ExperimentalContextParameters::class)
    val CONTEXT_CRUD_REPOSITORY = ContextCrudRepository::class.qualifiedName!!
    val ARROW_CRUD_REPOSITORY = ArrowCrudRepository::class.qualifiedName!!
    @OptIn(ExperimentalContextParameters::class)
    val ARROW_CONTEXT_CRUD_REPOSITORY = ArrowContextCrudRepository::class.qualifiedName!!

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

    // Sqlx4k types
    val QUERY_EXECUTOR = QueryExecutor::class.qualifiedName!!
    val STATEMENT = Statement::class.qualifiedName!!
    val SQL_ERROR = SQLError::class.qualifiedName!!
    val DB_RESULT = io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.DbResult::class.qualifiedName!!
    const val TO_DB_RESULT = "io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.toDbResult"
    val ROW_MAPPER = RowMapper::class.qualifiedName!!
    val VALUE_ENCODER_REGISTRY = ValueEncoderRegistry::class.qualifiedName!!
    val RESULT_SET = ResultSet::class.qualifiedName!!

    // Kotlin stdlib
    val KOTLIN_RESULT = Result::class.qualifiedName!!
    val KOTLIN_LIST = List::class.qualifiedName!!
    val KOTLIN_LONG = Long::class.qualifiedName!!
    val KOTLIN_INT = Int::class.qualifiedName!!
}
