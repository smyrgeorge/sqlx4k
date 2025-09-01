package io.github.smyrgeorge.sqlx4k.impl.migrate

/**
 * Represents a migration file used for database versioning.
 *
 * @property name The name of the migration file, which should follow the pattern `<version>_<description>.sql`.
 * @property path The file system path of the migration file.
 * @property version The version number extracted from the filename.
 * @property description The description of the migration, extracted from the filename after the version number.
 */
internal data class MigrationFile(
    val name: String,
    val path: String,
) {
    val version: Long
    val description: String

    init {
        val (version, description) = parseFileName(name)
        this.version = version
        this.description = description
    }

    companion object {
        private val fileNamePattern = Regex("""^\s*(\d+)_([A-Za-z0-9._-]+)\.sql\s*$""")
        private fun parseFileName(name: String): Pair<Long, String> {
            val name = name.trim()
            val match = fileNamePattern.matchEntire(name)
                ?: error("Migration filename must be <version>_<name>.sql, got $name")
            val (versionStr, cleanName) = match.destructured
            val version = versionStr.toLongOrNull()
                ?: error("Invalid version prefix in migration filename: $name")
            return version to cleanName
        }
    }
}