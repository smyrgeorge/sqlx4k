plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("multiplatform") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.rust"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformRustConventions"
        }
        create("multiplatform.simple") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.simple"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformSimpleConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
}
