plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("multiplatform.rust") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.rust"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformRustConventions"
        }
        create("multiplatform.examples") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.binaries"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformBinariesConventions"
        }
        create("multiplatform.jvm") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.jvm"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformJvmConventions"
        }
        create("publish") {
            id = "io.github.smyrgeorge.sqlx4k.publish"
            implementationClass = "io.github.smyrgeorge.sqlx4k.publish.PublishConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
    compileOnly(libs.gradle.publish.plugin)
}
