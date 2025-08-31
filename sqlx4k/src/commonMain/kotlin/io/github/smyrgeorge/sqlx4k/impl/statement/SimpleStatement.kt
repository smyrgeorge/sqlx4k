package io.github.smyrgeorge.sqlx4k.impl.statement

/**
 * Represents a simple SQL statement.
 *
 * This class extends the functionality of `AbstractStatement`
 * to handle straightforward SQL statements without additional
 * parameters or complex operations.
 *
 * @constructor Initializes the statement with the given SQL string.
 * @param sql The SQL query to be executed.
 */
class SimpleStatement(private val sql: String) : AbstractStatement(sql) {
    override fun toString(): String = "SimpleStatement(sql='$sql')"
}
