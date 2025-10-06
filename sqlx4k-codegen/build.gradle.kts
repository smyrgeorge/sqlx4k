plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.jvm")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
}

kotlin {
    @Suppress("unused")
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val jvmMain by getting {
            dependencies {
                api(project(":sqlx4k"))
                implementation(libs.ksp)
                implementation(libs.jsqlparser)
                implementation(libs.calcite.core)
//                implementation(libs.log4k.slf4j)
            }
        }
    }
}
