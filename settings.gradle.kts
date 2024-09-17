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
include("sqlx4k-mysql:examples")
include("sqlx4k-postgres")
include("sqlx4k-postgres:examples")
include("sqlx4k-sqlite")
include("sqlx4k-sqlite:examples")

include("sqlx4k-sqldelight")
include("sqlx4k-sqldelight:examples")
