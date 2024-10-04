plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.examples")
    alias(libs.plugins.sqldeligh)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":sqlx4k-postgres"))
                implementation(project(":sqldelight:sqlx4k-sqldelight"))
            }
        }
    }
}

sqldelight {
    databases.register("Database") {
        generateAsync = true
        packageName = "db.entities"
        dialect(project(":sqldelight:sqlx4k-sqldelight-dialect-postgres"))
    }
}
