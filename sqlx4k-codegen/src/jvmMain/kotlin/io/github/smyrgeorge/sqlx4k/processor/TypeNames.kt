package io.github.smyrgeorge.sqlx4k.processor

import io.github.smyrgeorge.sqlx4k.ContextCrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepository
import io.github.smyrgeorge.sqlx4k.CrudRepositoryHooks
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoder
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry
import io.github.smyrgeorge.sqlx4k.annotation.Column
import io.github.smyrgeorge.sqlx4k.annotation.Converter
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
    val CONVERTER_ANNOTATION = Converter::class.qualifiedName!!
    val QUERY_ANNOTATION = Query::class.qualifiedName!!

    // Core interfaces/classes
    val CRUD_REPOSITORY_HOOKS = CrudRepositoryHooks::class.qualifiedName!!
    val CRUD_REPOSITORY = CrudRepository::class.qualifiedName!!
    val CONTEXT_CRUD_REPOSITORY = ContextCrudRepository::class.qualifiedName!!
    val ARROW_CRUD_REPOSITORY = ArrowCrudRepository::class.qualifiedName!!
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
    const val DB_RESULT = "io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.DbResult"
    const val TO_DB_RESULT = "io.github.smyrgeorge.sqlx4k.arrow.impl.extensions.toDbResult"
    val ROW_MAPPER = RowMapper::class.qualifiedName!!
    val AUTO_ROW_MAPPER = Repository.AutoRowMapper::class.qualifiedName!!
    val VALUE_ENCODER = ValueEncoder::class.qualifiedName!!
    val VALUE_ENCODER_REGISTRY = ValueEncoderRegistry::class.qualifiedName!!
    val RESULT_SET = ResultSet::class.qualifiedName!!

    // Kotlin stdlib
    val KOTLIN_RESULT = Result::class.qualifiedName!!
    val KOTLIN_LIST = List::class.qualifiedName!!
    val KOTLIN_LONG = Long::class.qualifiedName!!
    val KOTLIN_INT = Int::class.qualifiedName!!

    /**
     * Set of built-in types that have native decoder support and should not use @Converter.
     */
    val BUILT_IN_TYPES = setOf(
        "kotlin.String",
        "kotlin.Char",
        "kotlin.Int",
        "kotlin.UInt",
        "kotlin.Long",
        "kotlin.ULong",
        "kotlin.Short",
        "kotlin.UShort",
        "kotlin.Float",
        "kotlin.Double",
        "kotlin.Boolean",
        "kotlin.uuid.Uuid",
        "kotlinx.datetime.LocalDate",
        "kotlinx.datetime.LocalTime",
        "kotlinx.datetime.LocalDateTime",
        "kotlin.time.Instant",
        "kotlin.ByteArray",
        "kotlin.BooleanArray",
        "kotlin.ShortArray",
        "kotlin.IntArray",
        "kotlin.LongArray",
        "kotlin.FloatArray",
        "kotlin.DoubleArray"
    )
}
