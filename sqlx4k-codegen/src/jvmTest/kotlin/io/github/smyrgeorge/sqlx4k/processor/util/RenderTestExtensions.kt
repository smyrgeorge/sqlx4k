package io.github.smyrgeorge.sqlx4k.processor.util

import io.github.smyrgeorge.sqlx4k.Dialect
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

/**
 * Test-only convenience that mirrors the old `Statement.render()` API by returning just the
 * rendered SQL text of the native (PostgreSQL) query.
 *
 * Note: parameters are NOT inlined anymore — they render as positional placeholders (`$1`, `$2`, ...).
 * To assert on the bound values, use [renderValues].
 */
fun Statement.render(encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): String =
    renderNativeQuery(Dialect.PostgreSQL, encoders).sql

/**
 * Test-only convenience that returns the ordered, raw bound values of the native (PostgreSQL) query
 * (i.e. what previous inlined-rendering used to embed into the SQL string as literals).
 */
fun Statement.renderValues(encoders: ValueEncoderRegistry = ValueEncoderRegistry.EMPTY): List<Any?> =
    renderNativeQuery(Dialect.PostgreSQL, encoders).values
