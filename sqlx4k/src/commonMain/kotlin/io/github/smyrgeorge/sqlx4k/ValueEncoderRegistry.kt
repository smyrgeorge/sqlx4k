package io.github.smyrgeorge.sqlx4k

import kotlin.reflect.KClass

/**
 * A registry for managing a collection of `ValueEncoder` instances.
 * Each encoder is associated with a specific data type and is used to convert that type
 * into a format suitable for use in database statements.
 *
 * Note: This class is not thread-safe. If concurrent access is required, external
 * synchronization should be applied.
 */
class ValueEncoderRegistry {
    private val encoders: MutableMap<KClass<*>, ValueEncoder<*>> = mutableMapOf()

    /**
     * Retrieves a `ValueEncoder` associated with the specified type.
     *
     * @param type The `KClass` of the type for which to get the encoder.
     * @return The `ValueEncoder` instance associated with the specified type, or null if none is found.
     */
    @Suppress("UNCHECKED_CAST")
    fun get(type: KClass<*>): ValueEncoder<Any>? =
        encoders[type] as ValueEncoder<Any>?

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getTyped(type: KClass<T>): ValueEncoder<T>? =
        encoders[type] as ValueEncoder<T>?

    /**
     * Retrieves a `ValueEncoder` associated with the reified type `T`.
     *
     * @return The `ValueEncoder` instance associated with type `T`, or null if none is found.
     */
    inline fun <reified T : Any> get(): ValueEncoder<T>? = getTyped(T::class)

    /**
     * Registers a `ValueEncoder` for a specific type within the `ValueEncoderRegistry`.
     *
     * @param type The `KClass` of the type for which the encoder is being registered.
     * @param encoder The `ValueEncoder` instance to associate with the specified type.
     * @return The `ValueEncoderRegistry` instance after the encoder has been registered.
     */
    fun register(type: KClass<*>, encoder: ValueEncoder<*>): ValueEncoderRegistry {
        encoders[type] = encoder
        return this
    }

    /**
     * Registers a `ValueEncoder` for the reified type `T`.
     *
     * @param encoder The `ValueEncoder` instance to associate with type `T`.
     * @return The `ValueEncoderRegistry` instance after the encoder has been registered.
     */
    inline fun <reified T : Any> register(encoder: ValueEncoder<T>): ValueEncoderRegistry =
        register(T::class, encoder)

    /**
     * Combines the mappings from two `ValueEncoderRegistry` instances into a new registry.
     *
     * This operator function creates a new `ValueEncoderRegistry` by merging the encoders
     * of the current registry and the encoders from the provided registry. If there are
     * overlapping encoders (i.e., encoders for the same type), the encoders from the
     * provided registry will override the existing ones.
     *
     * @param other The `ValueEncoderRegistry` whose encoders are to be merged.
     * @return A new `ValueEncoderRegistry` containing the combined encoders from both registries.
     */
    operator fun plus(other: ValueEncoderRegistry): ValueEncoderRegistry {
        val result = ValueEncoderRegistry()
        result.encoders.putAll(encoders)
        result.encoders.putAll(other.encoders)
        return result
    }

    companion object {
        /**
         * A pre-initialized empty instance of `ValueEncoderRegistry`.
         * This instance contains no registered `ValueEncoder` instances and
         * can be used as a default or placeholder.
         */
        val EMPTY = ValueEncoderRegistry()
    }
}
