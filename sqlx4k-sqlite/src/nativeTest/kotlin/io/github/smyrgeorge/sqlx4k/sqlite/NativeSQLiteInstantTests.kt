package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.test.Test

class NativeSQLiteInstantTests {

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db = SQLite(
        url = "sqlite://test.db",
        options = options
    )

    private val runner = CommonSQLiteInstantTests(db)

    @Test
    fun `instant positional param`() = runner.`instant positional param`()

    @Test
    fun `instant named param`() = runner.`instant named param`()

    @Test
    fun `instant null positional binding`() = runner.`instant null positional binding`()

    @Test
    fun `instant null named binding`() = runner.`instant null named binding`()

    @Test
    fun `instant epoch zero`() = runner.`instant epoch zero`()

    @Test
    fun `instant with microsecond precision`() = runner.`instant with microsecond precision`()

    @Test
    fun `instant past date 1985`() = runner.`instant past date 1985`()

    @Test
    fun `instant far future 2100`() = runner.`instant far future 2100`()

    @Test
    fun `reused named instant param`() = runner.`reused named instant param`()

    @Test
    fun `instant equality filter with prepared statement`() = runner.`instant equality filter with prepared statement`()

    @Test
    fun `instant range filter`() = runner.`instant range filter`()

    @Test
    fun `update instant via named param`() = runner.`update instant via named param`()

    @Test
    fun `instant mixed with other params`() = runner.`instant mixed with other params`()

    @Test
    fun `bindNull with Instant type`() = runner.`bindNull with Instant type`()

    @Test
    fun `batch insert instants and filter by id`() = runner.`batch insert instants and filter by id`()

    @Test
    fun `instant ordering`() = runner.`instant ordering`()
}
