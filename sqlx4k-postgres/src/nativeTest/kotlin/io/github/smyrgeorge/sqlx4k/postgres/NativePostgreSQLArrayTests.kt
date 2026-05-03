package io.github.smyrgeorge.sqlx4k.postgres

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativePostgreSQLArrayTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = postgreSQL(
        url = "postgresql://localhost:15432/test",
        username = "postgres",
        password = "postgres",
        options = options
    )

    private val runner = CommonPostgreSQLArrayTests(db)

    @Test
    fun `bool array roundtrip`() = runner.`bool array roundtrip`()

    @Test
    fun `bool array null`() = runner.`bool array null`()

    @Test
    fun `int2 array roundtrip`() = runner.`int2 array roundtrip`()

    @Test
    fun `int2 array null`() = runner.`int2 array null`()

    @Test
    fun `int4 array roundtrip`() = runner.`int4 array roundtrip`()

    @Test
    fun `int4 array null`() = runner.`int4 array null`()

    @Test
    fun `int8 array roundtrip`() = runner.`int8 array roundtrip`()

    @Test
    fun `int8 array null`() = runner.`int8 array null`()

    @Test
    fun `float4 array roundtrip`() = runner.`float4 array roundtrip`()

    @Test
    fun `float4 array null`() = runner.`float4 array null`()

    @Test
    fun `float8 array roundtrip`() = runner.`float8 array roundtrip`()

    @Test
    fun `float8 array null`() = runner.`float8 array null`()

    @Test
    fun `text array roundtrip`() = runner.`text array roundtrip`()

    @Test
    fun `text array null`() = runner.`text array null`()

    @Test
    fun `uuid array roundtrip`() = runner.`uuid array roundtrip`()

    @Test
    fun `uuid array null`() = runner.`uuid array null`()

    @Test
    fun `timestamp array roundtrip`() = runner.`timestamp array roundtrip`()

    @Test
    fun `timestamp array null`() = runner.`timestamp array null`()

    @Test
    fun `timestamptz array roundtrip`() = runner.`timestamptz array roundtrip`()

    @Test
    fun `timestamptz array null`() = runner.`timestamptz array null`()

    @Test
    fun `bytea array roundtrip`() = runner.`bytea array roundtrip`()

    @Test
    fun `bytea array null`() = runner.`bytea array null`()
}
