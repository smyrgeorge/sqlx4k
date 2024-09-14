plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("multiplatform") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
}
