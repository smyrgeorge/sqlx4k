package io.github.smyrgeorge.sqlx4k.sqldelight.dialect.postgres

import app.cash.sqldelight.dialect.api.DialectType
import app.cash.sqldelight.dialect.api.IntermediateType
import app.cash.sqldelight.dialect.api.PrimitiveType
import app.cash.sqldelight.dialect.api.RuntimeTypes
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import app.cash.sqldelight.dialect.api.TypeResolver
import app.cash.sqldelight.dialects.postgresql.PostgreSqlDialect
import app.cash.sqldelight.dialects.postgresql.PostgreSqlTypeResolver
import app.cash.sqldelight.dialects.postgresql.grammar.PostgreSqlParserUtil
import app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlTypeName
import com.alecstrong.sql.psi.core.SqlParserUtil
import com.alecstrong.sql.psi.core.psi.SqlTypeName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName

public class PostgresNativeDialect : SqlDelightDialect by PostgreSqlDialect() {

    override fun setup() {
        SqlParserUtil.reset()
        PostgreSqlParserUtil.reset()
        PostgreSqlParserUtil.overrideSqlParser()
//        PostgreSqlNativeParserUtil.reset()
//        PostgreSqlNativeParserUtil.overrideSqlParser()
    }

    override val runtimeTypes: RuntimeTypes
        get() = error("Only async driver is supported.")

    override val asyncRuntimeTypes: RuntimeTypes = RuntimeTypes(
        cursorType = ClassName(
            packageName = "io.github.smyrgeorge.sqlx4k.sqldelight",
            "SqlDelightCursor"
        ),
        preparedStatementType = ClassName(
            packageName = "io.github.smyrgeorge.sqlx4k.sqldelight",
            "SqlDelightPreparedStatement"
        )
    )

    override fun typeResolver(parentResolver: TypeResolver): TypeResolver =
        PostgresNativeTypeResolver(parentResolver)
}

private class PostgresNativeTypeResolver(parentResolver: TypeResolver) :
    TypeResolver by PostgreSqlTypeResolver(parentResolver) {
    override fun definitionType(typeName: SqlTypeName): IntermediateType = with(typeName) {
        check(this is PostgreSqlTypeName)
        val type = IntermediateType(
            when {
                smallIntDataType != null -> PostgreSqlType.SMALL_INT
                intDataType != null -> PostgreSqlType.INTEGER
                bigIntDataType != null -> PostgreSqlType.BIG_INT
                approximateNumericDataType != null -> PrimitiveType.REAL
                stringDataType != null -> PrimitiveType.TEXT
                uuidDataType != null -> PostgreSqlType.UUID
                smallSerialDataType != null -> PostgreSqlType.SMALL_INT
                serialDataType != null -> PostgreSqlType.INTEGER
                bigSerialDataType != null -> PostgreSqlType.BIG_INT
                dateDataType != null -> {
                    when (dateDataType!!.firstChild.text) {
                        "DATE" -> PostgreSqlType.DATE
                        "TIME" -> PostgreSqlType.TIME
                        "TIMESTAMP" -> if (dateDataType!!.node.getChildren(null)
                                .any { it.text == "WITH" }
                        ) PostgreSqlType.TIMESTAMP_TIMEZONE else PostgreSqlType.TIMESTAMP

                        "TIMESTAMPTZ" -> PostgreSqlType.TIMESTAMP_TIMEZONE
                        "INTERVAL" -> PostgreSqlType.INTERVAL
                        else -> throw IllegalArgumentException("Unknown date type ${dateDataType!!.text}")
                    }
                }

                jsonDataType != null -> PrimitiveType.TEXT
                booleanDataType != null -> PrimitiveType.BOOLEAN
                blobDataType != null -> PrimitiveType.BLOB
                else -> throw IllegalArgumentException("Unknown kotlin type for sql type ${this.text}")
            }
        )
        return type
    }
}

private enum class PostgreSqlType(override val javaType: TypeName) : DialectType {
    SMALL_INT(SHORT) {
        override fun decode(value: CodeBlock) = CodeBlock.of("%L.toShort()", value)
        override fun encode(value: CodeBlock) = CodeBlock.of("%L.toLong()", value)
    },
    INTEGER(INT) {
        override fun decode(value: CodeBlock) = CodeBlock.of("%L.toInt()", value)
        override fun encode(value: CodeBlock) = CodeBlock.of("%L.toLong()", value)
    },
    BIG_INT(LONG),
    DATE(ClassName("kotlinx.datetime", "LocalDate")),
    TIME(ClassName("kotlinx.datetime", "LocalTime")),
    TIMESTAMP(ClassName("kotlinx.datetime", "LocalDateTime")),
    TIMESTAMP_TIMEZONE(ClassName("kotlinx.datetime", "Instant")),
    INTERVAL(ClassName("kotlinx.datetime", "DateTimePeriod")),
    UUID(ClassName("kotlin.uuid", "Uuid"));

    override fun prepareStatementBinder(columnIndex: CodeBlock, value: CodeBlock): CodeBlock {
        return CodeBlock.builder()
            .add(
                when (this) {
                    SMALL_INT, INTEGER, BIG_INT -> "bindLong"
                    DATE -> "bindDate"
                    TIME -> "bindTime"
                    TIMESTAMP -> "bindLocalTimestamp"
                    TIMESTAMP_TIMEZONE -> "bindTimestamp"
                    INTERVAL -> "bindInterval"
                    UUID -> "bindUuid"
                }
            )
            .add("(%L, %L)\n", columnIndex, value)
            .build()
    }

    override fun cursorGetter(columnIndex: Int, cursorName: String): CodeBlock {
        return CodeBlock.of(
            when (this) {
                SMALL_INT, INTEGER, BIG_INT -> "$cursorName.getLong($columnIndex)"
                DATE -> "$cursorName.getDate($columnIndex)"
                TIME -> "$cursorName.getTime($columnIndex)"
                TIMESTAMP -> "$cursorName.getLocalTimestamp($columnIndex)"
                TIMESTAMP_TIMEZONE -> "$cursorName.getTimestamp($columnIndex)"
                INTERVAL -> "$cursorName.getInterval($columnIndex)"
                UUID -> "$cursorName.getUuid($columnIndex)"
            }
        )
    }
}
