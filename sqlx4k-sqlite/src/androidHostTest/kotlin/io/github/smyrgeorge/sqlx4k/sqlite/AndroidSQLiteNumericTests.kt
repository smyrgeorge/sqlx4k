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
class AndroidSQLiteNumericTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db: SQLite

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-numeric-tests.db").apply {
            if (exists()) delete()
        }
        db = SQLite(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            options = options
        )
    }

    private val runner = CommonSQLiteNumericTests(db)

    @Test
    fun `numeric values should decode through prepared statements`() = runner.`numeric values should decode through prepared statements`()

    @Test
    fun `numeric literals render identically`() = runner.`numeric literals render identically`()

    @Test
    fun `numeric extremes render identically`() = runner.`numeric extremes render identically`()

    @Test
    fun `numeric null decodes as null`() = runner.`numeric null decodes as null`()

    @Test
    fun `numeric aggregates render identically`() = runner.`numeric aggregates render identically`()

    @Test
    fun `numeric converts to kotlin numbers`() = runner.`numeric converts to kotlin numbers`()

    @Test
    fun `numeric column round-trips bound values`() = runner.`numeric column round-trips bound values`()
}
