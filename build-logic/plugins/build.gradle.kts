plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
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
    }
}

dependencies {
    compileOnly(libs.gradle.kotlin.plugin)
    compileOnly(libs.gradle.publish.plugin)
}
