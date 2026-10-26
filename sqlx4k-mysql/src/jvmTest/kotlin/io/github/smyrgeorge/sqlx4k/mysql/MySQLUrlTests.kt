package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/** The URL parameters of the JVM driver: the ones the native driver (sqlx) reads, the rest rejected. */
class MySQLUrlTests {

    private val options = ConnectionPool.Options.builder().maxConnections(1).acquireTimeout(2.seconds).build()
    private val opened = mutableListOf<IMySQL>()

    private fun connect(url: String): IMySQL = testMySQL(url = url, options = options).also { opened += it }

    @AfterTest
    fun tearDown(): Unit = runBlocking { opened.forEach { it.close() } }

    private fun IMySQL.single(sql: String): String? =
        runBlocking { fetchAll(sql).getOrThrow().firstOrNull()?.get(1)?.asStringOrNull() }

    // The TLS cipher of the session, empty without TLS.
    private fun IMySQL.cipher(): String? = single("show session status like 'Ssl_cipher'")

    @Test
    fun `a url without a database connects`() {
        val db = connect("mysql://localhost:13306")
        assertThat(runBlocking { db.fetchAll("select 1").getOrThrow().size }).isEqualTo(1)
    }

    @Test
    fun `a url without a port uses the default port`() {
        // Nothing is expected on localhost:3306: the URL must be accepted, and the failure reported as an SQLError.
        val res = runBlocking { runCatching { connect("mysql://localhost/test").fetchAll("select 1").getOrThrow() } }
        res.exceptionOrNull()?.let { assertThat(it).isInstanceOf(SQLError::class) }
    }

    @Test
    fun `ssl given as text is applied`() {
        assertThat(connect("mysql://localhost:13306/test?ssl=true").cipher().orEmpty()).isNotEmpty()
        assertThat(connect("mysql://localhost:13306/test?ssl=false").cipher().orEmpty()).isEqualTo("")
    }

    @Test
    fun `ssl-mode is applied`() {
        assertThat(connect("mysql://localhost:13306/test?ssl-mode=required").cipher().orEmpty()).isNotEmpty()
        assertThat(connect("mysql://localhost:13306/test?sslmode=disabled").cipher().orEmpty()).isEqualTo("")
        // The test server's certificate is self-signed: verifying it fails.
        val res = runBlocking {
            runCatching { connect("mysql://localhost:13306/test?ssl-mode=verify_identity").fetchAll("select 1").getOrThrow() }
        }
        assertThat(res).isFailure()
    }

    @Test
    fun `timezone sets the session time zone`() {
        assertThat(connect("mysql://localhost:13306/test?timezone=%2B02:00").single("show session variables like 'time_zone'"))
            .isEqualTo("+02:00")
    }

    @Test
    fun `an unknown parameter is rejected`() {
        val res = runCatching { connect("mysql://localhost:13306/test?sslMod=required") }
        assertThat(res).isFailure()
        val error = res.exceptionOrNull() as SQLError
        assertThat(error.message.orEmpty()).contains("Unknown parameter `sslMod`")
    }
}
