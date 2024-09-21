package io.github.smyrgeorge.sqlx4k.sqldelight

import app.cash.sqldelight.db.SqlPreparedStatement
import io.github.smyrgeorge.sqlx4k.impl.ExtendedStatement

class SqlDelightPreparedStatement(sql: String) : SqlPreparedStatement {
    var statement = ExtendedStatement(sql)

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        statement = statement.bind(index, boolean)
    }

    override fun bindBytes(index: Int, bytes: ByteArray?) {
        TODO("Not yet implemented")
    }

    override fun bindDouble(index: Int, double: Double?) {
        statement = statement.bind(index, double)
    }

    override fun bindLong(index: Int, long: Long?) {
        statement = statement.bind(index, long)
    }

    override fun bindString(index: Int, string: String?) {
        statement = statement.bind(index, string)
    }
}
