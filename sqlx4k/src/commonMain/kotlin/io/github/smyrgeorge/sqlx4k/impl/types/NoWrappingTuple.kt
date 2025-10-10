package io.github.smyrgeorge.sqlx4k.impl.types

import kotlin.jvm.JvmInline

@JvmInline
value class NoWrappingTuple(val value: Iterable<*>)