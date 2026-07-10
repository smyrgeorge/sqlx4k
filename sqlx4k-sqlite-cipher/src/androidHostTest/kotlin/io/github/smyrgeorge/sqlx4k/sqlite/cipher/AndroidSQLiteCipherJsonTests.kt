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
class AndroidSQLiteCipherJsonTests {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val options = ConnectionPool.Options.builder()
        .maxConnections(2)
        .build()

    private val db: ISQLiteCipher

    init {
        // Use a fresh database file per test class to avoid schema leakage between runs.
        val dbFile = File(context.cacheDir, "sqlx4k-json-tests.db").apply {
            if (exists()) delete()
        }
        db = sqliteCipher(
            context = context,
            url = "sqlite:${dbFile.absolutePath}",
            password = "test-passphrase",
            options = options
        )
    }

    private val runner = CommonSQLiteCipherJsonTests(db)

    @Test
    fun `json minifies whitespace and preserves key order`() = runner.`json minifies whitespace and preserves key order`()

    @Test
    fun `json minifies an array`() = runner.`json minifies an array`()

    @Test
    fun `json_extract reads a top-level field`() = runner.`json_extract reads a top-level field`()

    @Test
    fun `json_extract reads a nested path`() = runner.`json_extract reads a nested path`()

    @Test
    fun `json_type reports the value type`() = runner.`json_type reports the value type`()

    @Test
    fun `json_valid distinguishes valid and invalid json`() = runner.`json_valid distinguishes valid and invalid json`()

    @Test
    fun `json_array_length counts elements`() = runner.`json_array_length counts elements`()

    @Test
    fun `jsonb produces a blob`() = runner.`jsonb produces a blob`()

    @Test
    fun `json of jsonb round-trips to canonical text`() = runner.`json of jsonb round-trips to canonical text`()

    @Test
    fun `json_extract reads a field from a jsonb blob`() = runner.`json_extract reads a field from a jsonb blob`()

    @Test
    fun `jsonb_object builds canonical json`() = runner.`jsonb_object builds canonical json`()

    @Test
    fun `jsonb preserves a nested structure`() = runner.`jsonb preserves a nested structure`()

    @Test
    fun `jsonb preserves escaped quotes and unicode`() = runner.`jsonb preserves escaped quotes and unicode`()

    @Test
    fun `json text column round-trips through a table`() = runner.`json text column round-trips through a table`()

    @Test
    fun `jsonb blob column round-trips through a table`() = runner.`jsonb blob column round-trips through a table`()

    @Test
    fun `null json column reads as null`() = runner.`null json column reads as null`()
}
