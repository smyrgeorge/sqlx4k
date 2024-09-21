plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldeligh)
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    macosArm64 { binaries { executable() } }

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
