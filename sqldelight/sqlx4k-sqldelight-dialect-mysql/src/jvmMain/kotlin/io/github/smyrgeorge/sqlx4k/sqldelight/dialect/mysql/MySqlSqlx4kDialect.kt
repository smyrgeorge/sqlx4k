package io.github.smyrgeorge.sqlx4k.sqldelight.dialect.mysql

import app.cash.sqldelight.dialect.api.DialectType
import app.cash.sqldelight.dialect.api.IntermediateType
import app.cash.sqldelight.dialect.api.PrimitiveType
import app.cash.sqldelight.dialect.api.RuntimeTypes
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import app.cash.sqldelight.dialect.api.TypeResolver
import app.cash.sqldelight.dialects.mysql.MySqlDialect
import app.cash.sqldelight.dialects.mysql.MySqlTypeResolver
import app.cash.sqldelight.dialects.mysql.grammar.MySqlParserUtil
import app.cash.sqldelight.dialects.mysql.grammar.psi.MySqlTypeName
import com.alecstrong.sql.psi.core.SqlParserUtil
import com.alecstrong.sql.psi.core.psi.SqlTypeName
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.SHORT
import com.squareup.kotlinpoet.TypeName

/**
 * A custom dialect for MySQL using the Sqlx4k library with SqlDelight.
 * This class extends `SqlDelightDialect` and is implemented through the `MySqlDialect()`.
 * It provides specific configurations and type resolvers for MySQL.
 *
 * Original implementation found here:
 * https://github.com/joepeding/mysql-native-sqldelight/blob/main/mysql-native-dialect/src/main/kotlin/nl/joepeding/sqldelight/mysql/native/dialect/MysqlNativeDialect.kt
 */
public open class MySqlSqlx4kDialect : SqlDelightDialect by MySqlDialect() {

    override fun setup() {
        SqlParserUtil.reset()
        MySqlParserUtil.reset()
        MySqlParserUtil.overrideSqlParser()
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
        MySqlSqlx4kTypeResolver(parentResolver)

    private class MySqlSqlx4kTypeResolver(
        parentResolver: TypeResolver
    ) : TypeResolver by MySqlTypeResolver(parentResolver) {
        override fun definitionType(typeName: SqlTypeName): IntermediateType = with(typeName) {
            check(this is MySqlTypeName)
            val type = IntermediateType(
                when {
                    smallIntDataType != null -> MySqlType.SMALL_INT
                    intDataType != null -> MySqlType.INTEGER
                    bigIntDataType != null -> MySqlType.BIG_INT
                    approximateNumericDataType != null -> PrimitiveType.REAL
                    tinyIntDataType != null -> if (tinyIntDataType!!.text == "BOOLEAN") {
                        MySqlType.TINY_INT_BOOL
                    } else {
                        MySqlType.TINY_INT
                    }

                    mediumIntDataType != null -> MySqlType.INTEGER
                    dateDataType != null -> {
                        when (dateDataType!!.firstChild.text.uppercase()) {
                            "DATE" -> MySqlType.DATE
                            "TIME" -> MySqlType.TIME
                            "DATETIME" -> MySqlType.DATETIME
                            "TIMESTAMP" -> MySqlType.TIMESTAMP
                            "YEAR" -> PrimitiveType.TEXT
                            else -> throw IllegalArgumentException("Unknown date type ${dateDataType!!.text}")
                        }
                    }

                    bitDataType != null -> MySqlType.BIT
                    enumSetType != null -> PrimitiveType.TEXT
                    characterType != null -> PrimitiveType.TEXT
                    jsonDataType != null -> PrimitiveType.TEXT
                    binaryDataType != null -> PrimitiveType.BLOB
                    else -> throw IllegalArgumentException("Unknown kotlin type for sql type $text")
                }
            )
            return type
        }
    }

    private enum class MySqlType(override val javaType: TypeName) : DialectType {
        TINY_INT_BOOL(BOOLEAN) {
            override fun decode(value: CodeBlock) = CodeBlock.of("%L == 1L", value)
            override fun encode(value: CodeBlock) = CodeBlock.of("if (%L) 1L else 0L", value)
        },

        TINY_INT(SHORT),
        SMALL_INT(SHORT),
        INTEGER(INT),
        BIG_INT(LONG),
        BIT(BOOLEAN) {
            override fun decode(value: CodeBlock) = CodeBlock.of("%L == 1L", value)
            override fun encode(value: CodeBlock) = CodeBlock.of("if (%L) 1L else 0L", value)
        },
        DATE(ClassName("kotlinx.datetime", "LocalDate")),
        TIME(ClassName("kotlinx.datetime", "LocalTime")),
        TIMESTAMP(ClassName("kotlinx.datetime", "LocalDateTime")),
        DATETIME(ClassName("kotlinx.datetime", "Instant")),
        UUID(ClassName("kotlin.uuid", "Uuid"));

        override fun prepareStatementBinder(columnIndex: CodeBlock, value: CodeBlock): CodeBlock {
            return CodeBlock.builder()
                .add(
                    when (this) {
                        SMALL_INT -> "bindShort"
                        INTEGER -> "bindInt"
                        BIG_INT -> "bindLong"
                        DATE -> "bindDate"
                        TIME -> "bindTime"
                        DATETIME, TIMESTAMP -> "bindLocalTimestamp"
                        UUID -> "bindUuid"
                        TINY_INT -> "bindShort"
                        TINY_INT_BOOL -> "bindLong"
                        BIT -> "bindLong"
                    }
                )
                .add("(%L, %L)\n", columnIndex, value)
                .build()
        }

        override fun cursorGetter(columnIndex: Int, cursorName: String): CodeBlock {
            return CodeBlock.of(
                when (this) {
                    SMALL_INT -> "$cursorName.getShort($columnIndex)"
                    INTEGER -> "$cursorName.getInt($columnIndex)"
                    BIG_INT -> "$cursorName.getLong($columnIndex)"
                    DATE -> "$cursorName.getDate($columnIndex)"
                    TIME -> "$cursorName.getTime($columnIndex)"
                    TIMESTAMP, DATETIME -> "$cursorName.getLocalTimestamp($columnIndex)"
                    UUID -> "$cursorName.getUuid($columnIndex)"
                    TINY_INT -> "$cursorName.getShort($columnIndex)"
                    TINY_INT_BOOL -> "$cursorName.getLong($columnIndex)"
                    BIT -> "$cursorName.getLong($columnIndex)"
                }
            )
        }
    }

}
