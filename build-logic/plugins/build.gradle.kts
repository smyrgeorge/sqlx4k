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
        create("multiplatform.examples") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.examples"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformExamplesConventions"
        }
        create("multiplatform.jvm") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.jvm"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformJvmConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
}
