plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.jvm")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        jvmMain {
            dependencies {
                api(project(":sqlx4k"))
                implementation(libs.ksp)
                implementation(libs.jsqlparser)
                implementation(libs.calcite.core)
                implementation(libs.log4k.slf4j)
            }
        }
    }
}
