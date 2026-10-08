plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.binaries")
    alias(libs.plugins.sqlx4k)
}

sqlx4k {
    driver = PostgreSQL
    generatedCodePackage = "io.github.smyrgeorge.sqlx4k.examples.postgres"
    extensions(Pgmq)
    args = mapOf(
        "validate-sql-schema" to "false",
        "schema-migrations-path" to "./db/migrations",
    )
}
