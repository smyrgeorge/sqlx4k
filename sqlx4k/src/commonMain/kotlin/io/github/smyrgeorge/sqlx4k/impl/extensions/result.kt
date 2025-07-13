package io.github.smyrgeorge.sqlx4k.impl.extensions

import io.github.smyrgeorge.sqlx4k.SQLError

fun Result<*>.errorOrNull(): SQLError? = exceptionOrNull() as? SQLError