plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("multiplatform") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformConventions"
        }
        create("multiplatform.lib") {
            id = "io.github.smyrgeorge.sqlx4k.multiplatform.lib"
            implementationClass = "io.github.smyrgeorge.sqlx4k.multiplatform.MultiplatformLibConventions"
        }
        create("multiplatform.binaries") {
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
        create("dokka") {
            id = "io.github.smyrgeorge.sqlx4k.dokka"
            implementationClass = "io.github.smyrgeorge.sqlx4k.dokka.DokkaConventions"
        }
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
    compileOnly(libs.gradle.publish.plugin)
    compileOnly(libs.gradle.dokka.plugin)
}
