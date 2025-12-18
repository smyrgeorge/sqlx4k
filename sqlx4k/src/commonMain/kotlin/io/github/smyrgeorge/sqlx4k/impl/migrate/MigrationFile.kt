package io.github.smyrgeorge.sqlx4k.impl.migrate

/**
 * Represents a migration file typically used in database migrations. Each migration file
 * contains a name, content, and checksum while parsing and extracting the version and description
 * from the file name.
 *
 * @property name The name of the migration file. It must follow the format `<version>_<name>.sql`.
 * @property content The SQL content of the migration file as a string.
 * @property checksum A string representing the checksum of the file for validation purposes.
 * @property version The version extracted from the filename, determined by the prefix in the file name.
 * @property description The description extracted from the filename, determined by the suffix after the underscore.
 */
data class MigrationFile(
    val name: String,
    val content: String,
) {
    val checksum: String
    val version: Long
    val description: String

    init {
        val (version, description) = parseFileName(name)
        this.version = version
        this.description = description
        this.checksum = content.hashCode().toString()
    }

    companion object {
        private val fileNamePattern = Regex("""^\s*(\d+)_([A-Za-z0-9._-]+)\.sql\s*$""")
        private fun parseFileName(name: String): Pair<Long, String> {
            val name = name.trim()
            val match = fileNamePattern.matchEntire(name)
                ?: error("Migration filename must be <version>_<name>.sql, got $name")
            val (versionStr, description) = match.destructured
            val version = versionStr.toLongOrNull()
                ?: error("Invalid version prefix in migration filename: $name")
            return version to description
        }
    }
}