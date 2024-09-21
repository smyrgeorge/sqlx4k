package io.github.smyrgeorge.sqlx4k.sqldelight.dialect.postgres

import app.cash.sqldelight.dialect.api.RuntimeTypes
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import app.cash.sqldelight.dialects.postgresql.PostgreSqlDialect
import app.cash.sqldelight.dialects.postgresql.grammar.PostgreSqlParserUtil
import com.alecstrong.sql.psi.core.SqlParserUtil
import com.squareup.kotlinpoet.ClassName

public open class PostgresSqlSqlx4kDialect : SqlDelightDialect by PostgreSqlDialect() {

    override fun setup() {
        SqlParserUtil.reset()
        PostgreSqlParserUtil.reset()
        PostgreSqlParserUtil.overrideSqlParser()
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
}
