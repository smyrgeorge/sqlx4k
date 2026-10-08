package io.github.smyrgeorge.sqlx4k.sqlite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.smyrgeorge.sqlx4k.ConnectionPool
import java.io.File
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSQLiteErrorTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    private val db: SQLite

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-error-tests.db").apply {
            if (exists()) delete()
        }
        db = SQLite(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            options = options
        )
    }

    private val runner = CommonSQLiteErrorTests(db)

    @Test
    fun `duplicate key should expose the driver error codes`() =
        runner.`duplicate key should expose the driver error codes`()

    @Test
    fun `unmapped database error should expose the codes`() =
        runner.`unmapped database error should expose the codes`()
}
