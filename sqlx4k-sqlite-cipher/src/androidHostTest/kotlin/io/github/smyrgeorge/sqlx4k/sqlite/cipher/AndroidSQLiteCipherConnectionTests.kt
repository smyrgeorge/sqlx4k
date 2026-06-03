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
class AndroidSQLiteCipherConnectionTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db: ISQLiteCipher

    init {
        val dbFile = File(context.cacheDir, "sqlx4k-connection-tests.db").apply {
            if (exists()) delete()
        }
        db = sqliteCipher(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            password = "test-passphrase",
            options = options
        )
    }

    private val runner = CommonSQLiteCipherConnectionTests(db)

    @Test
    fun `acquire-release should allow operations then forbid after release`() {
        runner.`acquire-release should allow operations then forbid after release`()
    }

    @Test
    fun `close should be idempotent`() {
        runner.`close should be idempotent`()
    }

    @Test
    fun `connection begin-commit and rollback should work`() {
        runner.`connection begin-commit and rollback should work`()
    }

    @Test
    fun `fetchAll and execute should work while acquired`() {
        runner.`fetchAll and execute should work while acquired`()
    }

    @Test
    fun `status should be Acquired then Released`() {
        runner.`status should be Acquired then Released`()
    }
}
