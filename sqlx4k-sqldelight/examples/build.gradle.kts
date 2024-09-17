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
                implementation(project(":sqlx4k-sqldelight"))
            }
        }
    }
}

sqldelight {
    databases.register("Database") {
        generateAsync = true
        dialect(libs.sqldelight.postgresql.dialect)
        packageName = "io.github.smyrgeorge.sqlx4k.sqldelight.example"
    }
}
