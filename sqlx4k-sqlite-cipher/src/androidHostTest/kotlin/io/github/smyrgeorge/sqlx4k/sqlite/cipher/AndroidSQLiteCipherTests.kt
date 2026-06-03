package io.github.smyrgeorge.sqlx4k.sqlite.cipher

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
class AndroidSQLiteCipherTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(10)
        .build()

    private val db: ISQLiteCipher

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-basic-tests.db").apply {
            if (exists()) delete()
        }
        db = sqliteCipher(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            password = "test-passphrase",
            options = options
        )
    }

    private val runner = CommonSQLiteCipherTests(db)

    @Test
    fun `Test basic type mappings`() = runner.`Test basic type mappings`()

    @Test
    fun `execute and fetchAll should work`() = runner.`execute and fetchAll should work`()

    @Test
    fun `execute and fetchAll with prepared statements should work`() =
        runner.`execute and fetchAll with prepared statements should work`()
}
