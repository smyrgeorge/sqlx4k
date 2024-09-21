package io.github.smyrgeorge.sqlx4k.sqldelight.dialect.mysql

import app.cash.sqldelight.dialect.api.RuntimeTypes
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import app.cash.sqldelight.dialects.mysql.MySqlDialect
import app.cash.sqldelight.dialects.mysql.grammar.MySqlParserUtil
import com.alecstrong.sql.psi.core.SqlParserUtil
import com.squareup.kotlinpoet.ClassName

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
}
