plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
    alias(libs.plugins.sqlx4k)
}

sqlx4k {
    driver = MySQL
    generatedCodePackage = "io.github.smyrgeorge.sqlx4k.examples.mysql"
    args = mapOf(
        "validate-sql-schema" to "false",
        "schema-migrations-path" to "./db/migrations",
    )
}
