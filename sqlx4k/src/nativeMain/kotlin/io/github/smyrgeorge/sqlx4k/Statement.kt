package io.github.smyrgeorge.sqlx4k

import io.github.smyrgeorge.sqlx4k.impl.statement.SimpleStatement
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
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
    fun render(encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): String

    /**
     * Converts the value of the receiver to a string representation suitable for database operations.
     *
     * This method handles various types:
     * - `null` is represented as the string "null".
     * - `String` values are wrapped in single quotes and any single quotes within the string are escaped.
     * - Numeric and boolean values are converted to their string representation using `toString()`.
     * - For other types, it attempts to use a custom renderer. If no renderer is found, it throws a [SQLError].
     *
     * @param encoders A map of encoders for specific types, used to encode values into a SQL-compatible format.
     * @return A string representation of the receiver suitable for database operations.
     * @throws SQLError if the type of the receiver is unsupported and no appropriate renderer is found.
     */
    fun Any?.encodeValue(encoders: ValueEncoderRegistry): String {
        return when (this) {
            null -> "null"
            is String -> {
                // https://stackoverflow.com/questions/12316953/insert-text-with-single-quotes-in-postgresql
                // https://stackoverflow.com/questions/9596652/how-to-escape-apostrophe-a-single-quote-in-mysql
                // https://stackoverflow.com/questions/603572/escape-single-quote-character-for-use-in-an-sqlite-query
                "'${replace("'", "''")}'"
            }

            is Boolean, is Number -> toString()
            is LocalDate, is LocalTime, is LocalDateTime, is Instant -> toString()
            else -> {
                val error = SQLError(
                    code = SQLError.Code.NamedParameterTypeNotSupported,
                    message = "Could not map named parameter of type ${this::class.simpleName}"
                )

                val encoder = encoders.get(this::class) ?: error.ex()
                encoder.encode(this).encodeValue(encoders)
            }
        }
    }

    /**
     * An interface for encoding values of type `T` into a format suitable for
     * usage in database statements. Implementations of this interface will define
     * how to convert a value of type `T` into a type that can be safely and
     * correctly used within a SQL statement.
     *
     * @param T The type of the value to be rendered.
     */
    interface ValueEncoder<T> {
        fun encode(value: T): Any
    }

    /**
     * A singleton class responsible for managing a collection of `ValueEncoder` instances.
     * Each renderer is associated with a specific data type and is used to convert that type
     * into a format suitable for use in database statements.
     */
    @Suppress("unused", "UNCHECKED_CAST")
    class ValueEncoderRegistry {
        private val encoders: MutableMap<KClass<*>, ValueEncoder<*>> = mutableMapOf()

        /**
         * Retrieves a `ValueRenderer` associated with the specified type.
         *
         * @param type The `KClass` of the type for which to get the renderer.
         * @return The `ValueRenderer` instance associated with the specified type, or null if none is found.
         */
        fun get(type: KClass<*>): ValueEncoder<Any>? =
            encoders[type] as ValueEncoder<Any>?

        /**
         * Registers a `ValueEncoder` for a specific type within the `ValueEncoderRegistry`.
         *
         * @param type The `KClass` of the type for which the encoder is being registered.
         * @param renderer The `ValueEncoder` instance to associate with the specified type.
         * @return The `ValueEncoderRegistry` instance after the encoder has been registered.
         */
        fun register(type: KClass<*>, renderer: ValueEncoder<*>): ValueEncoderRegistry {
            encoders[type] = renderer
            return this
        }

        /**
         * Unregisters a `ValueEncoder` for the specified type.
         *
         * @param type The `KClass` of the type for which the encoder should be unregistered.
         * @return The `ValueEncoderRegistry` instance after the encoder has been removed.
         */
        fun unregister(type: KClass<*>): ValueEncoderRegistry {
            encoders.remove(type)
            return this
        }

        companion object {
            val EMPTY = ValueEncoderRegistry()
        }
    }

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
