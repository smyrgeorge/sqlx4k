package io.github.smyrgeorge.sqlx4k

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ConnectionPoolOptionsTests {
    @Test
    fun `Valid options with all parameters set`() {
        val options = ConnectionPool.Options(
            minConnections = 5,
            maxConnections = 10,
            acquireTimeout = 30.seconds,
            idleTimeout = 5.minutes,
            maxLifetime = 10.minutes
        )

        assertThat(options.minConnections).isEqualTo(5)
        assertThat(options.maxConnections).isEqualTo(10)
        assertThat(options.acquireTimeout).isEqualTo(30.seconds)
        assertThat(options.idleTimeout).isEqualTo(5.minutes)
        assertThat(options.maxLifetime).isEqualTo(10.minutes)
    }

    @Test
    fun `Valid options with defaults`() {
        val options = ConnectionPool.Options()

        assertThat(options.minConnections).isEqualTo(null)
        assertThat(options.maxConnections).isEqualTo(10)
        assertThat(options.acquireTimeout).isEqualTo(null)
        assertThat(options.idleTimeout).isEqualTo(null)
        assertThat(options.maxLifetime).isEqualTo(null)
    }

    @Test
    fun `Valid options with null optional parameters`() {
        val options = ConnectionPool.Options(
            minConnections = null,
            maxConnections = 5,
            acquireTimeout = null,
            idleTimeout = null,
            maxLifetime = null
        )

        assertThat(options.minConnections).isEqualTo(null)
        assertThat(options.maxConnections).isEqualTo(5)
    }

    @Test
    fun `Valid options with minConnections equal to maxConnections`() {
        val options = ConnectionPool.Options(
            minConnections = 10,
            maxConnections = 10
        )

        assertThat(options.minConnections).isEqualTo(10)
        assertThat(options.maxConnections).isEqualTo(10)
    }

    @Test
    fun `Valid options with idleTimeout equal to maxLifetime`() {
        val options = ConnectionPool.Options(
            maxConnections = 5,
            idleTimeout = 10.minutes,
            maxLifetime = 10.minutes
        )

        assertThat(options.idleTimeout).isEqualTo(10.minutes)
        assertThat(options.maxLifetime).isEqualTo(10.minutes)
    }

    @Test
    fun `Valid options with minimum positive values`() {
        val options = ConnectionPool.Options(
            minConnections = 1,
            maxConnections = 1,
            acquireTimeout = 1.milliseconds,
            idleTimeout = 1.milliseconds,
            maxLifetime = 1.milliseconds
        )

        assertThat(options.minConnections).isEqualTo(1)
        assertThat(options.maxConnections).isEqualTo(1)
    }

    // ==================== Builder Tests ====================

    @Test
    fun `Builder creates valid options with all parameters`() {
        val options = ConnectionPool.Options.builder()
            .minConnections(5)
            .maxConnections(10)
            .acquireTimeout(30.seconds)
            .idleTimeout(5.minutes)
            .maxLifetime(10.minutes)
            .build()

        assertThat(options.minConnections).isEqualTo(5)
        assertThat(options.maxConnections).isEqualTo(10)
        assertThat(options.acquireTimeout).isEqualTo(30.seconds)
        assertThat(options.idleTimeout).isEqualTo(5.minutes)
        assertThat(options.maxLifetime).isEqualTo(10.minutes)
    }

    @Test
    fun `Builder creates valid options with defaults`() {
        val options = ConnectionPool.Options.builder().build()

        assertThat(options.minConnections).isEqualTo(null)
        assertThat(options.maxConnections).isEqualTo(10)
        assertThat(options.acquireTimeout).isEqualTo(null)
        assertThat(options.idleTimeout).isEqualTo(null)
        assertThat(options.maxLifetime).isEqualTo(null)
    }

    @Test
    fun `Builder creates valid options with partial parameters`() {
        val options = ConnectionPool.Options.builder()
            .maxConnections(20)
            .acquireTimeout(1.minutes)
            .build()

        assertThat(options.minConnections).isEqualTo(null)
        assertThat(options.maxConnections).isEqualTo(20)
        assertThat(options.acquireTimeout).isEqualTo(1.minutes)
        assertThat(options.idleTimeout).isEqualTo(null)
        assertThat(options.maxLifetime).isEqualTo(null)
    }

    // ==================== Invalid minConnections ====================

    @Test
    fun `Fails when minConnections is zero`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                minConnections = 0,
                maxConnections = 10
            )
        }
        assertThat(exception.message ?: "").contains("minConnections must be greater than 0")
    }

    @Test
    fun `Fails when minConnections is negative`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                minConnections = -1,
                maxConnections = 10
            )
        }
        assertThat(exception.message ?: "").contains("minConnections must be greater than 0")
    }

    @Test
    fun `Fails when minConnections is negative using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .minConnections(-5)
                .maxConnections(10)
                .build()
        }
        assertThat(exception.message ?: "").contains("minConnections must be greater than 0")
    }

    // ==================== Invalid maxConnections ====================

    @Test
    fun `Fails when maxConnections is zero`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(maxConnections = 0)
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than 0")
    }

    @Test
    fun `Fails when maxConnections is negative`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(maxConnections = -1)
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than 0")
    }

    @Test
    fun `Fails when maxConnections is negative using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .maxConnections(-10)
                .build()
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than 0")
    }

    // ==================== Invalid idleTimeout ====================

    @Test
    fun `Fails when idleTimeout is zero`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                idleTimeout = 0.seconds
            )
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be greater than 0")
    }

    @Test
    fun `Fails when idleTimeout is negative`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                idleTimeout = (-1).seconds
            )
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be greater than 0")
    }

    @Test
    fun `Fails when idleTimeout is negative using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .idleTimeout((-5).seconds)
                .build()
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be greater than 0")
    }

    // ==================== Invalid maxLifetime ====================

    @Test
    fun `Fails when maxLifetime is zero`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                maxLifetime = 0.seconds
            )
        }
        assertThat(exception.message ?: "").contains("maxLifetime must be greater than 0")
    }

    @Test
    fun `Fails when maxLifetime is negative`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                maxLifetime = (-1).seconds
            )
        }
        assertThat(exception.message ?: "").contains("maxLifetime must be greater than 0")
    }

    @Test
    fun `Fails when maxLifetime is negative using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .maxLifetime((-10).seconds)
                .build()
        }
        assertThat(exception.message ?: "").contains("maxLifetime must be greater than 0")
    }

    // ==================== Invalid acquireTimeout ====================

    @Test
    fun `Fails when acquireTimeout is zero`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                acquireTimeout = 0.seconds
            )
        }
        assertThat(exception.message ?: "").contains("acquireTimeout must be greater than 0")
    }

    @Test
    fun `Fails when acquireTimeout is negative`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                acquireTimeout = (-1).seconds
            )
        }
        assertThat(exception.message ?: "").contains("acquireTimeout must be greater than 0")
    }

    @Test
    fun `Fails when acquireTimeout is negative using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .acquireTimeout((-30).seconds)
                .build()
        }
        assertThat(exception.message ?: "").contains("acquireTimeout must be greater than 0")
    }

    // ==================== Invalid maxConnections vs minConnections ====================

    @Test
    fun `Fails when maxConnections is less than minConnections`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                minConnections = 10,
                maxConnections = 5
            )
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than or equal to minConnections")
    }

    @Test
    fun `Fails when maxConnections is less than minConnections using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .minConnections(15)
                .maxConnections(10)
                .build()
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than or equal to minConnections")
    }

    @Test
    fun `Fails when maxConnections is 1 and minConnections is 2`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                minConnections = 2,
                maxConnections = 1
            )
        }
        assertThat(exception.message ?: "").contains("maxConnections must be greater than or equal to minConnections")
    }

    // ==================== Invalid idleTimeout vs maxLifetime ====================

    @Test
    fun `Fails when idleTimeout is greater than maxLifetime`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                idleTimeout = 10.minutes,
                maxLifetime = 5.minutes
            )
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be less than or equal to maxLifetime")
    }

    @Test
    fun `Fails when idleTimeout is greater than maxLifetime using builder`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options.builder()
                .idleTimeout(20.minutes)
                .maxLifetime(10.minutes)
                .build()
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be less than or equal to maxLifetime")
    }

    @Test
    fun `Fails when idleTimeout is slightly greater than maxLifetime`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                maxConnections = 10,
                idleTimeout = 5001.milliseconds,
                maxLifetime = 5000.milliseconds
            )
        }
        assertThat(exception.message ?: "").contains("idleTimeout must be less than or equal to maxLifetime")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `Valid with very large maxConnections`() {
        val options = ConnectionPool.Options(
            maxConnections = 10000
        )

        assertThat(options.maxConnections).isEqualTo(10000)
    }

    @Test
    fun `Valid with very large timeouts`() {
        val options = ConnectionPool.Options(
            maxConnections = 10,
            acquireTimeout = 999999.seconds,
            idleTimeout = 999999.seconds,
            maxLifetime = 999999.seconds
        )

        assertThat(options.acquireTimeout).isEqualTo(999999.seconds)
        assertThat(options.idleTimeout).isEqualTo(999999.seconds)
        assertThat(options.maxLifetime).isEqualTo(999999.seconds)
    }

    @Test
    fun `Valid with minConnections as 1 and maxConnections as large number`() {
        val options = ConnectionPool.Options(
            minConnections = 1,
            maxConnections = 1000
        )

        assertThat(options.minConnections).isEqualTo(1)
        assertThat(options.maxConnections).isEqualTo(1000)
    }

    @Test
    fun `Fails with multiple validation errors - shows first error`() {
        // This tests that validation fails fast (first error is shown)
        assertFailsWith<IllegalArgumentException> {
            ConnectionPool.Options(
                minConnections = -1,  // Invalid
                maxConnections = -1   // Also invalid
            )
        }
    }

    @Test
    fun `Valid options when only idleTimeout is set without maxLifetime`() {
        val options = ConnectionPool.Options(
            maxConnections = 10,
            idleTimeout = 5.minutes,
            maxLifetime = null
        )

        assertThat(options.idleTimeout).isEqualTo(5.minutes)
        assertThat(options.maxLifetime).isEqualTo(null)
    }

    @Test
    fun `Valid options when only maxLifetime is set without idleTimeout`() {
        val options = ConnectionPool.Options(
            maxConnections = 10,
            idleTimeout = null,
            maxLifetime = 10.minutes
        )

        assertThat(options.idleTimeout).isEqualTo(null)
        assertThat(options.maxLifetime).isEqualTo(10.minutes)
    }
}