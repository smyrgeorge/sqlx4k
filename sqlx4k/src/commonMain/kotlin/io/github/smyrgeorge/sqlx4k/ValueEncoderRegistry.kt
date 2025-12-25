package io.github.smyrgeorge.sqlx4k

import kotlin.reflect.KClass

/**
 * A singleton class responsible for managing a collection of `ValueEncoder` instances.
 * Each encoder is associated with a specific data type and is used to convert that type
 * into a format suitable for use in database statements.
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
        /**
         * A pre-initialized empty instance of `ValueEncoderRegistry`.
         * This instance contains no registered `ValueEncoder` instances and
         * can be used as a default or placeholder.
         */
        val EMPTY = ValueEncoderRegistry()
    }
}
