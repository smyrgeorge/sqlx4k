rootProject.name = "sqlx4k"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include("sqlx4k")
include("sqlx4k-mysql")
include("sqlx4k-postgres")
include("sqlx4k-sqlite")

include("sqldelight:sqlx4k-sqldelight")
include("sqldelight:sqlx4k-sqldelight-dialect-mysql")
include("sqldelight:sqlx4k-sqldelight-dialect-postgres")

include("examples:mysql")
include("examples:postgres")
include("examples:postgres-sqldelight")
include("examples:sqlite")
