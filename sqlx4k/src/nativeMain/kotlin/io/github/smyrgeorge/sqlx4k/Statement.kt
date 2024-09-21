package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.SimpleStatement
import kotlin.reflect.KClass

/**
 * Represents a statement that allows binding of positional and named parameters.
 * Provides methods to bind values to parameters and render the statement as a
 * complete SQL query.
 */
interface Statement {

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
     * @param value The value to bind to the specified named parameter. May be null.
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
    fun render(): String

    /**
     * Converts the value of the receiver to a string representation suitable for database operations.
     *
     * This method handles various types:
     * - `null` is represented as the string "null".
     * - `String` values are wrapped in single quotes and any single quotes within the string are escaped.
     * - Numeric and boolean values are converted to their string representation using `toString()`.
     * - For other types, it attempts to use a custom renderer. If no renderer is found, it throws a `DbError`.
     *
     * @return A string representation of the receiver suitable for database operations.
     * @throws DbError if the type of the receiver is unsupported and no appropriate renderer is found.
     */
    fun Any?.renderValue(): String {
        return when (this) {
            null -> "null"
            is String -> {
                // https://stackoverflow.com/questions/12316953/insert-text-with-single-quotes-in-postgresql
                // https://stackoverflow.com/questions/9596652/how-to-escape-apostrophe-a-single-quote-in-mysql
                // https://stackoverflow.com/questions/603572/escape-single-quote-character-for-use-in-an-sqlite-query
                "'${replace("'", "''")}'"
            }

            is Byte, is Boolean, is Int, is Long, is Short, is Double, is Float -> toString()
            else -> {
                val error = DbError(
                    code = DbError.Code.NamedParameterTypeNotSupported,
                    message = "Could not map named parameter of type ${this::class.simpleName}"
                )

                val renderer = Statement.ValueRenderers.get(this::class) ?: error.ex()
                renderer.render(this).renderValue()
            }
        }
    }

    /**
     * An interface for rendering values of type `T` into a format suitable for
     * usage in database statements. Implementations of this interface will define
     * how to convert a value of type `T` into a type that can be safely and
     * correctly used within a SQL statement.
     *
     * @param T The type of the value to be rendered.
     */
    interface ValueRenderer<T> {
        fun render(value: T): Any
    }

    /**
     * A singleton class responsible for managing a collection of `ValueRenderer` instances.
     * Each renderer is associated with a specific data type and is used to convert that type
     * into a format suitable for use in database statements.
     */
    @Suppress("unused", "UNCHECKED_CAST")
    class ValueRenderers {
        companion object {
            private val renderers: MutableMap<KClass<*>, ValueRenderer<*>> = mutableMapOf()

            /**
             * Retrieves a `ValueRenderer` associated with the specified type.
             *
             * @param type The `KClass` of the type for which to get the renderer.
             * @return The `ValueRenderer` instance associated with the specified type, or null if none is found.
             */
            fun get(type: KClass<*>): ValueRenderer<Any>? =
                renderers[type] as ValueRenderer<Any>?

            /**
             * Registers a `ValueRenderer` for a specified type.
             *
             * @param type The `KClass` of the type for which to register the renderer.
             * @param renderer The `ValueRenderer` instance to be associated with the specified type.
             */
            fun register(type: KClass<*>, renderer: ValueRenderer<*>) {
                renderers[type] = renderer
            }

            /**
             * Unregisters the `ValueRenderer` associated with a specified type.
             *
             * @param type The `KClass` of the type for which to unregister the renderer.
             */
            fun unregister(type: KClass<*>) {
                renderers.remove(type)
            }
        }
    }

    companion object {
        /**
         * Creates and returns a new `SimpleStatement` based on the provided SQL string.
         *
         * @param sql The SQL statement as a string.
         * @return The constructed `SimpleStatement` instance.
         */
        fun create(sql: String): Statement = SimpleStatement(sql)
    }
}
