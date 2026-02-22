package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.statement.SimpleStatement
import kotlin.reflect.KClass

/**
 * Represents a statement that allows binding of positional and named parameters.
 * Provides methods to bind values to parameters and render the statement as a
 * complete SQL query.
 */
interface Statement {
    val sql: String

    /**
     * A set containing the names of all named parameters extracted from the SQL statement.
     * It is populated using a parser that skips over string literals, comments, and
     * PostgreSQL dollar-quoted strings.
     */
    val extractedNamedParameters: Set<String>

    /**
     * Stores the mapping of named parameters to their corresponding bound values in the SQL statement.
     *
     * This mutable map is used to associate named parameters (keys) with the values (values) to be
     * injected into the SQL query during rendering. Named parameters are placeholders in the form
     * `:name` within the SQL statement. The associated values can be of any type or nullable.
     *
     * The map ensures that all named parameters used in the SQL statement are explicitly bound
     * with their corresponding values prior to rendering or execution of the query.
     */
    val namedParametersValues: MutableMap<String, Any?>

    /**
     * The count of positional parameter placeholders ('?') extracted from the SQL query.
     */
    val extractedPositionalParameters: Int

    /**
     * Represents an array that holds the bound values for positional parameters in a SQL statement.
     *
     * This property is used to store the values that are assigned to positional placeholders in
     * an SQL statement. The positional placeholders are usually denoted by `?` in the SQL query.
     * Each index in this array corresponds to the zero-based index of the positional parameter
     * in the SQL statement.
     *
     * Positional parameter binding allows for dynamic substitution of values into the SQL query
     * at runtime. The values in this array are used when rendering or executing the statement
     * to replace the placeholders with their respective bound values.
     *
     * `null` values in the array indicate that no value has been bound to the corresponding
     * positional parameter.
     */
    val positionalParametersValues: Array<Any?>

    /**
     * Binds a value to a positional parameter in the statement based on the given index.
     *
     * @param index The zero-based index of the positional parameter to bind the value to.
     * @param value The value to bind to the specified positional parameter.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bind(index: Int, value: Any?): Statement

    /**
     * Binds a typed null value to a positional parameter in the statement.
     *
     * This method is used to explicitly bind a `null` value to a positional parameter
     * while specifying the intended SQL type. It is particularly useful when interacting
     * with strongly-typed SQL columns where the type information is required to correctly
     * handle the `null` value (e.g., `uuid`, `timestamptz`, typed arrays).
     *
     * @param index The zero-based index of the positional parameter to bind the null value to.
     * @param type The Kotlin class corresponding to the intended SQL type of the parameter.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bindNull(index: Int, type: KClass<*>): Statement

    /**
     * Binds a value to a named parameter in the statement.
     *
     * @param parameter The name of the parameter to bind the value to.
     * @param value The value to bind to the specified named parameter. Maybe null.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bind(parameter: String, value: Any?): Statement

    /**
     * Binds a typed null value to a named parameter in the statement.
     *
     * This method is used to explicitly bind a `null` value to a named parameter
     * while specifying the intended SQL type. It is beneficial in cases where
     * the type information is required to properly handle the `null` value,
     * such as in the case of strongly-typed SQL columns (e.g., `uuid`, `timestamptz`,
     * typed arrays).
     *
     * @param parameter The name of the parameter to bind the null value to.
     * @param type The Kotlin class representing the intended SQL type of the parameter.
     * @return The current `Statement` instance to allow for method chaining.
     */
    fun bindNull(parameter: String, type: KClass<*>): Statement

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

    /**
     * Renders the SQL statement with all bound parameters and converts it into a `Query` object.
     *
     * This method processes the SQL statement by resolving positional and named parameter placeholders
     * with their respective bound values. The result is a `Query` object that encapsulates the final
     * SQL string and the list of parameter values to be used for execution.
     *
     * @return A `Query` object containing the rendered SQL statement and the corresponding parameter values.
     */
    fun renderNativeQuery(dialect: Dialect, encoders: ValueEncoderRegistry): NativeQuery

    /**
     * Represents a query to be executed against a database.
     *
     * A `Query` consists of an SQL statement and the associated parameter values
     * intended to be bound to the placeholders in the statement.
     * It is typically used to encapsulate the information required for executing
     * parameterized queries in a structured and reusable manner.
     *
     * @property sql The SQL statement to be executed.
     * @property dialect The SQL dialect used for rendering the query.
     * @property values A list of parameter values to bind to placeholders in the SQL statement.
     * The values can include null entries to represent unset parameters.
     */
    data class NativeQuery(val sql: String, val dialect: Dialect, val values: List<Any?>)

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
