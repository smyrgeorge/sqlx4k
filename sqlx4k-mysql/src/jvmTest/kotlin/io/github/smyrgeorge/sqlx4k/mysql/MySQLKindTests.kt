package io.github.smyrgeorge.sqlx4k.mysql

import assertk.assertThat
import assertk.assertions.isEqualTo
import io.github.smyrgeorge.sqlx4k.SQLError
import kotlin.test.Test

/**
 * The JVM mapping must agree with the Rust one (`sqlx4k_mysql_kind_of`).
 */
class MySQLKindTests {

    @Test
    fun `constraint violations by error number`() {
        assertThat(mysqlKindOf(1062, "23000")).isEqualTo(SQLError.Kind.UniqueViolation)
        assertThat(mysqlKindOf(1452, "23000")).isEqualTo(SQLError.Kind.ForeignKeyViolation)
        assertThat(mysqlKindOf(1048, "23000")).isEqualTo(SQLError.Kind.NotNullViolation)
        assertThat(mysqlKindOf(3819, "HY000")).isEqualTo(SQLError.Kind.CheckViolation)
        // MariaDB ER_CONSTRAINT_FAILED vs. the unrelated MySQL error with the same number.
        assertThat(mysqlKindOf(4025, "23000")).isEqualTo(SQLError.Kind.CheckViolation)
        assertThat(mysqlKindOf(4025, "HY000")).isEqualTo(SQLError.Kind.Other)
    }

    @Test
    fun `concurrency failures by error number`() {
        assertThat(mysqlKindOf(1213, "40001")).isEqualTo(SQLError.Kind.Deadlock)
        assertThat(mysqlKindOf(1205, "HY000")).isEqualTo(SQLError.Kind.LockTimeout)
    }

    @Test
    fun `everything else is Other`() {
        assertThat(mysqlKindOf(1146, "42S02")).isEqualTo(SQLError.Kind.Other)
        assertThat(mysqlKindOf(1064, "42000")).isEqualTo(SQLError.Kind.Other)
        assertThat(mysqlKindOf(null, null)).isEqualTo(SQLError.Kind.Other)
    }
}
