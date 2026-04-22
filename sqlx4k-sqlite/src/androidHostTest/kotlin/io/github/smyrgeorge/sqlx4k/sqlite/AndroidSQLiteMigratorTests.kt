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
class AndroidSQLiteMigratorTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db: SQLite

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-migrator-tests.db").apply {
            if (exists()) delete()
        }
        db = SQLite(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            options = options
        )
    }

    private val runner = CommonSQLiteMigratorTests(db)

    @Test
    fun `migrate happy path and idempotent`() {
        runner.`migrate happy path and idempotent`()
    }

    @Test
    fun `duplicate version files should fail`() {
        runner.`duplicate version files should fail`()
    }

    @Test
    fun `non-monotonic versions should fail`() {
        runner.`non-monotonic versions should fail`()
    }

    @Test
    fun `empty migration file should fail`() {
        runner.`empty migration file should fail`()
    }

    @Test
    fun `checksum mismatch should fail on re-run`() {
        runner.`checksum mismatch should fail on re-run`()
    }
}
