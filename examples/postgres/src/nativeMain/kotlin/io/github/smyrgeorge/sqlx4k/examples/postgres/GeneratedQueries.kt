package io.github.smyrgeorge.sqlx4k.examples.postgres

import io.github.smyrgeorge.sqlx4k.Statement

expect fun Sqlx4k.insert(): Statement
expect fun Sqlx4k.update(): Statement
expect fun Sqlx4k.delete(): Statement