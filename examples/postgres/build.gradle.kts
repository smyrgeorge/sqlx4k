plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.examples")
    alias(libs.plugins.ksp) // We need the KSP plugin for code-generation.
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation(project(":sqlx4k-postgres"))
            }
        }
    }
}

ksp {
    arg("output-package", "io.github.smyrgeorge.sqlx4k.examples.postgres")
    arg("output-filename", "GeneratedQueries")
}

dependencies {
    ksp(project(":sqlx4k-codegen")) // Will generate code for all available targets.
//    You can also enable the code generation for specific target
//    add("kspMacosArm64", project(":processor"))
//    add("kspCommonMainMetadata", project(":processor"))
}
