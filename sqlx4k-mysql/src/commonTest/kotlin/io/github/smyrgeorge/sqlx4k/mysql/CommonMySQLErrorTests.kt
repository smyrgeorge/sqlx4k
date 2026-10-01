package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlinx.coroutines.runBlocking

/**
 * Driver failures that are not database errors (TLS, refused connections, ...) must be returned as an [SQLError].
 * The native driver is built with `panic = "abort"`, so a panic on any of them would terminate the process.
 *
 * Native and JVM report these failures with different codes, so each platform passes the code it expects.
 * Native creates the pool eagerly and fails at construction, while the JVM fails on the first query.
 *
 * @param connect creates a driver for the given URL.
 */
class CommonMySQLErrorTests(
    private val connect: (url: String) -> IMySQL
) {

    private fun fails(url: String): Result<*> = runBlocking {
        runCatching { connect(url).fetchAll("select 1;").getOrThrow() }
    }

    fun `TLS failure should be returned as an SQLError`(expected: SQLError.Code) {
        // The native driver has no TLS support, so requiring TLS fails.
        val res = fails("mysql://localhost:13306/test?ssl-mode=required")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(expected)
    }

    fun `refused connection should be returned as an SQLError`(expected: SQLError.Code) {
        // Nothing listens on port 1.
        val res = fails("mysql://localhost:1/test")
        assertThat(res).isFailure()
        assertThat((res.exceptionOrNull() as SQLError).code).isEqualTo(expected)
    }
}
