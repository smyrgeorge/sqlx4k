plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api(libs.sqldelight.postgresql.dialect)
    api(libs.sqldelight.dialect.api)
    compileOnly(libs.sqldelight.compiler.env)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
    }
}
