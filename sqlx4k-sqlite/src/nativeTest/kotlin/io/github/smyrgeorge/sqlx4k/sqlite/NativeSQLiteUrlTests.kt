package io.github.smyrgeorge.sqlx4k.sqlite

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

@OptIn(ExperimentalNativeApi::class)
class NativeSQLiteUrlTests {

    private val dir: String = Path("./build/tmp/url-tests").let {
        SystemFileSystem.createDirectories(it, mustCreate = false)
        SystemFileSystem.resolve(it).toString()
    }

    private fun options(maxConnections: Int) = ConnectionPool.Options.builder()
        .maxConnections(maxConnections)
        .build()

    private val runner = CommonSQLiteUrlTests(dir) { url, max -> SQLite(url = url, options = options(max)) }

    @Test
    fun `mode=rwc with absolute path creates the file and not one named after the whole query`() {
        runner.`mode=rwc with absolute path creates the file and not one named after the whole query`()
    }

    @Test
    fun `default mode creates the file`() {
        runner.`default mode creates the file`()
    }

    @Test
    fun `mode=ro opens an existing file read-only`() {
        runner.`mode=ro opens an existing file read-only`()
    }

    @Test
    fun `mode=rw opens an existing file read-write`() {
        runner.`mode=rw opens an existing file read-write`()
    }

    @Test
    fun `mode=ro does not create a missing file`() {
        runner.`mode=ro does not create a missing file`()
    }

    @Test
    fun `mode=rw does not create a missing file`() {
        runner.`mode=rw does not create a missing file`()
    }

    @Test
    fun `mode=memory does not touch the file system`() {
        runner.`mode=memory does not touch the file system`()
    }

    @Test
    fun `in-memory database works`() {
        runner.`in-memory database works`()
    }

    @Test
    fun `in-memory database with shared cache works`() {
        runner.`in-memory database with shared cache works`()
    }

    @Test
    fun `in-memory database with pool larger than one shares the database`() {
        runner.`in-memory database with pool larger than one shares the database`()
    }

    @Test
    fun `cache parameter on a file database is accepted`() {
        runner.`cache parameter on a file database is accepted`()
    }

    @Test
    fun `immutable opens an existing file read-only`() {
        runner.`immutable opens an existing file read-only`()
    }

    @Test
    fun `vfs parameter is honoured`() {
        if (Platform.osFamily == OsFamily.WINDOWS) return // the default VFS is `win32` there
        runner.`vfs parameter is honoured`("unix")
    }

    @Test
    fun `unknown vfs is rejected`() {
        runner.`unknown vfs is rejected`()
    }

    @Test
    fun `writable file database uses WAL`() {
        runner.`writable file database uses WAL`()
    }

    @Test
    fun `mode=ro works on a database that is not in WAL mode`() {
        runner.`mode=ro works on a database that is not in WAL mode`()
    }

    @Test
    fun `path with a space works`() {
        runner.`path with a space works`()
    }

    @Test
    fun `relative path is resolved against the working directory`() {
        runner.`relative path is resolved against the working directory`()
    }

    @Test
    fun `invalid mode is rejected`() {
        runner.`invalid mode is rejected`()
    }

    @Test
    fun `unknown parameter is rejected`() {
        runner.`unknown parameter is rejected`()
    }
}
