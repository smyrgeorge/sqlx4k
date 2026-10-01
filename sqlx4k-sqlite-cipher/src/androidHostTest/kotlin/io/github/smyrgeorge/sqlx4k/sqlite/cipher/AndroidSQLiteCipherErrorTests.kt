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
class AndroidSQLiteCipherErrorTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(1)
        .build()

    private val dbFile = File(context.cacheDir, "sqlx4k-error-tests.db").apply {
        if (exists()) delete()
    }

    private fun open(query: String): ISQLiteCipher = sqliteCipher(
        context = context,
        url = "sqlite:${dbFile.absolutePath}?$query",
        password = "test-passphrase",
        options = options
    )

    private val runner = CommonSQLiteCipherErrorTests(open("mode=rwc"), ::open)

    @Test
    fun `invalid URL should be returned as a Pool error`() = runner.`invalid URL should be returned as a Pool error`()

    @Test
    fun `protocol error should be returned as a Database error`() =
        runner.`protocol error should be returned as a Database error`()
}
