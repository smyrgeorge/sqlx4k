rootProject.name = "sqlx4k"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // The examples apply the sqlx4k Gradle plugin by id, published to mavenLocal by scripts/bootstrap.sh.
        mavenLocal()
    }

    includeBuild("build-logic")
}

include("sqlx4k")
include("sqlx4k-arrow")
include("sqlx4k-codegen")
include("sqlx4k-codegen-test")
include("sqlx4k-gradle-plugin")
include("sqlx4k-mysql")
include("sqlx4k-postgres")
include("sqlx4k-postgres-pgmq")
include("sqlx4k-sqlite")
include("sqlx4k-sqlite-cipher")

include("dokka")
include("examples:mysql")
include("examples:postgres")
include("examples:sqlite")

include("bench:postgres-sqlx4k")
include("bench:postgres-spring-boot-r2dbc")
