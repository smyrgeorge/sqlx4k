plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.simple")
    alias(libs.plugins.sqldeligh)
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    applyDefaultHierarchyTemplate()
    sourceSets {
        val nativeMain by getting {
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
