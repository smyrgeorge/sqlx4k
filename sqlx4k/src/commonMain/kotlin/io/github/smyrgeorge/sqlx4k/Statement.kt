package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.statement.SimpleStatement

/**
 * Represents a statement that allows binding of positional and named parameters.
 * Provides methods to bind values to parameters and render the statement as a
 * complete SQL query.
 */
interface Statement {

    /**
     * A set containing the names of all named parameters extracted from the SQL statement.
     * It is populated using a parser that skips over string literals, comments, and
     * PostgreSQL dollar-quoted strings.
     */
    val extractedNamedParameters: Set<String>

    /**
     * The count of positional parameter placeholders ('?') extracted from the SQL query.
     */
    val extractedPositionalParameters: Int

    /**
     * Binds a value to a positional parameter in the statement based on the given index.
     *
     * @param index The zero-based index of the positional parameter to bind the value to.
     * @param value The value to bind to the specified positional parameter.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bind(index: Int, value: Any?): Statement

    /**
     * Binds a value to a named parameter in the statement.
     *
     * @param parameter The name of the parameter to bind the value to.
     * @param value The value to bind to the specified named parameter. Maybe null.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bind(parameter: String, value: Any?): Statement

    /**
     * Renders the SQL statement by replacing placeholders for positional and named parameters
     * with their respective bound values.
     *
     * This function first processes positional parameters, replacing each positional marker
     * with its corresponding value. It subsequently processes named parameters, replacing each
     * named marker (e.g., `:name`) with its corresponding value.
     *
     * @return A string representing the rendered SQL statement with all positional and named
     * parameters substituted by their bound values.
     */
    fun render(encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): String

    companion object {
        /**
         * Creates a new `Statement` instance with the provided SQL string.
         *
         * @param sql The SQL string used to create the statement.
         * @return A new `Statement` instance initialized with the provided SQL string.
         */
        fun create(sql: String): Statement = SimpleStatement(sql)
    }
}
