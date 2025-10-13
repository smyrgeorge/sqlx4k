package io.github.smyrgeorge.sqlx4k.impl.types

import kotlin.jvm.JvmInline

/**
 * A wrapper class that represents a collection of values without additional wrapping or transformation.
 *
 * This class is useful in cases where an iterable structure needs to be encapsulated
 * without modifying its contents or applying any additional processing.
 *
 * @property value The iterable collection encapsulated by this class.
 */
@JvmInline
value class NoWrappingTuple(val value: Iterable<*>)