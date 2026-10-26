package io.github.smyrgeorge.sqlx4k.arrow.impl.extensions

import arrow.core.Either
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EitherTests {

    @Test
    fun `success becomes Right`() {
        assertThat(Result.success(1).toDbResult()).isEqualTo(Either.Right(1))
    }

    @Test
    fun `an SQLError becomes Left as is`() {
        val error = SQLError(SQLError.Code.Database, "boom")
        val left = Result.failure<Int>(error).toDbResult() as Either.Left
        assertThat(left.value).isSameInstanceAs(error)
    }

    @Test
    fun `another failure becomes an UnknownError keeping its cause`() {
        val cause = IllegalStateException("boom")
        val left = Result.failure<Int>(cause).toDbResult() as Either.Left
        assertThat(left.value.code).isEqualTo(SQLError.Code.UnknownError)
        assertThat(left.value.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `a cancellation is rethrown instead of turned into Left`() {
        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> { Result.failure<Int>(cancellation).toDbResult() }
        assertThat(thrown).isInstanceOf<CancellationException>()
        assertThat(thrown).isSameInstanceAs(cancellation)
    }
}
