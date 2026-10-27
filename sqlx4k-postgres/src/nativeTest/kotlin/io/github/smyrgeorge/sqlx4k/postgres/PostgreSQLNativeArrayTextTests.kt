@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

// r2dbc-postgresql (the JVM driver) fails to read an array of more than one dimension ("Dimensions mismatch").
class PostgreSQLNativeArrayTextTests {

    private val db = testPostgreSQL()

    @AfterTest
    fun tearDown(): Unit = runBlocking { db.close().getOrThrow() }

    @Test
    fun `arrays sqlx cannot decode read as text`(): Unit = runBlocking {
        db.assertText("array[[1, 2], [3, 4]]", "{{1,2},{3,4}}")
        db.assertText("'[0:1]={1,2}'::int[]", "[0:1]={1,2}")
        db.assertText("array[['a b', 'c'], ['d', 'e']]", "{{\"a b\",c},{d,e}}")
        db.assertText("array[[true], [false]]", "{{t},{f}}")
    }
}
