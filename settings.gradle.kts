rootProject.name = "sqlx4k"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    includeBuild("build-logic")
}

include("sqlx4k")
include("sqlx4k-codegen")
include("sqlx4k-mysql")
include("sqlx4k-postgres")
include("sqlx4k-postgres-pgmq")
include("sqlx4k-sqlite")

include("dokka")
include("examples:mysql")
include("examples:postgres")
include("examples:sqlite")

include("bench:postgres-sqlx4k")
include("bench:postgres-spring-boot-r2dbc")
