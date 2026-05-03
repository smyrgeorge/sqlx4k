@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
@file:Suppress("SqlNoDataSourceInspection", "SqlDialectInspection")

package io.github.smyrgeorge.sqlx4k.postgres

import assertk.assertAll
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asBooleanArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asBooleanArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArrayList
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asByteArrayListOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asDoubleArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asDoubleArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asFloatArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asFloatArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asInstantList
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asInstantListOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asIntArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asIntArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asLocalDateTimeList
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asLocalDateTimeListOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asLongArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asLongArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asShortArray
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asShortArrayOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asStringList
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asStringListOrNull
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asUuidList
import io.github.smyrgeorge.sqlx4k.postgres.extensions.asUuidListOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime

class CommonPostgreSQLArrayTests(
    private val db: IPostgresSQL
) {
    // ---- bool[] ----
    fun `bool array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{t,f,t}'::bool[] as a").getOrThrow().first()
        assertThat(row.get(0).asBooleanArray().toList()).isEqualTo(listOf(true, false, true))
    }

    fun `bool array null`() = runBlocking {
        val row = db.fetchAll("select null::bool[] as a").getOrThrow().first()
        assertThat(row.get(0).asBooleanArrayOrNull()).isNull()
    }

    // ---- int2[] ----
    fun `int2 array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{1,2,3}'::int2[] as a").getOrThrow().first()
        assertThat(row.get(0).asShortArray().toList()).isEqualTo(listOf<Short>(1, 2, 3))
    }

    fun `int2 array null`() = runBlocking {
        val row = db.fetchAll("select null::int2[] as a").getOrThrow().first()
        assertThat(row.get(0).asShortArrayOrNull()).isNull()
    }

    // ---- int4[] ----
    fun `int4 array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{10,20,30}'::int4[] as a").getOrThrow().first()
        assertThat(row.get(0).asIntArray().toList()).isEqualTo(listOf(10, 20, 30))
    }

    fun `int4 array null`() = runBlocking {
        val row = db.fetchAll("select null::int4[] as a").getOrThrow().first()
        assertThat(row.get(0).asIntArrayOrNull()).isNull()
    }

    // ---- int8[] ----
    fun `int8 array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{10000000000,20000000000}'::int8[] as a").getOrThrow().first()
        assertThat(row.get(0).asLongArray().toList()).isEqualTo(listOf(10_000_000_000L, 20_000_000_000L))
    }

    fun `int8 array null`() = runBlocking {
        val row = db.fetchAll("select null::int8[] as a").getOrThrow().first()
        assertThat(row.get(0).asLongArrayOrNull()).isNull()
    }

    // ---- float4[] ----
    fun `float4 array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{1.5,2.5}'::float4[] as a").getOrThrow().first()
        assertThat(row.get(0).asFloatArray().toList()).isEqualTo(listOf(1.5f, 2.5f))
    }

    fun `float4 array null`() = runBlocking {
        val row = db.fetchAll("select null::float4[] as a").getOrThrow().first()
        assertThat(row.get(0).asFloatArrayOrNull()).isNull()
    }

    // ---- float8[] ----
    fun `float8 array roundtrip`() = runBlocking {
        val row = db.fetchAll("select '{1.5,2.5,3.5}'::float8[] as a").getOrThrow().first()
        assertThat(row.get(0).asDoubleArray().toList()).isEqualTo(listOf(1.5, 2.5, 3.5))
    }

    fun `float8 array null`() = runBlocking {
        val row = db.fetchAll("select null::float8[] as a").getOrThrow().first()
        assertThat(row.get(0).asDoubleArrayOrNull()).isNull()
    }

    // ---- text[] ----
    fun `text array roundtrip`() = runBlocking {
        // Cast the array to TEXT so the column is read as a scalar text — works
        // around an R2DBC limitation on JVM where its TEXT[] codec refuses to
        // coerce to `String` ("Dimensions mismatch"). Native handles either form.
        val row = db.fetchAll("select ('{hello,world}'::text[])::text as a").getOrThrow().first()
        assertThat(row.get(0).asStringList()).isEqualTo(listOf("hello", "world"))
    }

    fun `text array null`() = runBlocking {
        val row = db.fetchAll("select null::text[] as a").getOrThrow().first()
        assertThat(row.get(0).asStringListOrNull()).isNull()
    }

    // ---- uuid[] ----
    fun `uuid array roundtrip`() = runBlocking {
        val a = "22d64ef8-f6b3-43da-8869-2ee9d31be9d5"
        val b = "11d64ef8-f6b3-43da-8869-2ee9d31be9d5"
        val row = db.fetchAll("select '{$a,$b}'::uuid[] as a").getOrThrow().first()
        assertThat(row.get(0).asUuidList()).isEqualTo(listOf(Uuid.parse(a), Uuid.parse(b)))
    }

    fun `uuid array null`() = runBlocking {
        val row = db.fetchAll("select null::uuid[] as a").getOrThrow().first()
        assertThat(row.get(0).asUuidListOrNull()).isNull()
    }

    // ---- timestamp[] ----
    fun `timestamp array roundtrip`() = runBlocking {
        val row = db.fetchAll(
            "select '{2025-03-25 07:31:43.330068, 2025-03-26 07:31:43.330068}'::timestamp[] as a"
        ).getOrThrow().first()
        assertThat(row.get(0).asLocalDateTimeList()).isEqualTo(
            listOf(
                LocalDateTime.parse("2025-03-25T07:31:43.330068"),
                LocalDateTime.parse("2025-03-26T07:31:43.330068")
            )
        )
    }

    fun `timestamp array null`() = runBlocking {
        val row = db.fetchAll("select null::timestamp[] as a").getOrThrow().first()
        assertThat(row.get(0).asLocalDateTimeListOrNull()).isNull()
    }

    // ---- timestamptz[] ----
    fun `timestamptz array roundtrip`() = runBlocking {
        val row = db.fetchAll(
            "select '{2025-03-25 07:31:43.330068+00, 2025-03-26 07:31:43.330068+00}'::timestamptz[] as a"
        ).getOrThrow().first()
        assertThat(row.get(0).asInstantList()).isEqualTo(
            listOf(
                Instant.parse("2025-03-25T07:31:43.330068Z"),
                Instant.parse("2025-03-26T07:31:43.330068Z")
            )
        )
    }

    fun `timestamptz array null`() = runBlocking {
        val row = db.fetchAll("select null::timestamptz[] as a").getOrThrow().first()
        assertThat(row.get(0).asInstantListOrNull()).isNull()
    }

    // ---- bytea[] ----
    fun `bytea array roundtrip`() = runBlocking {
        // `decode('hex', 'hex')` constructs a bytea from hex text — easier than
        // wrestling with the array-literal escaping for `\x` bytes.
        val row = db.fetchAll(
            "select array[decode('deadbeef', 'hex'), decode('ff00', 'hex')]::bytea[] as a"
        ).getOrThrow().first()
        val decoded = row.get(0).asByteArrayList()
        assertAll {
            assertThat(decoded.size).isEqualTo(2)
            assertThat(decoded[0].toList()).isEqualTo(
                listOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            )
            assertThat(decoded[1].toList()).isEqualTo(listOf(0xFF.toByte(), 0x00.toByte()))
        }
    }

    fun `bytea array null`() = runBlocking {
        val row = db.fetchAll("select null::bytea[] as a").getOrThrow().first()
        assertThat(row.get(0).asByteArrayListOrNull()).isNull()
    }
}
