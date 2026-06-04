plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.lib")
    id("io.github.smyrgeorge.sqlx4k.rust.jni")
    id("io.github.smyrgeorge.sqlx4k.publish")
    id("io.github.smyrgeorge.sqlx4k.dokka")
    alias(libs.plugins.android)
}

kotlin {
    android {
        namespace = "io.github.smyrgeorge.sqlx4k.sqlite.cipher"
        compileSdk = 36
        minSdk = 26
        withHostTestBuilder {}
    }

    // Shared JNI implementation for the JVM and Android targets. Both consume the same Rust shared
    // library through the same `CipherJni` symbols; only the native-library loader and the
    // `sqliteCipher` factory differ per platform.
    //
    // `androidMain` is always present (the android target is added above); `jvmMain` only exists
    // when the `jvm` target is enabled — it is absent for native-only target subsets such as
    // `-Ptargets=linuxX64`, so it is wired only when that target is registered. Guarding on the
    // target (rather than probing the source set) avoids spuriously creating `jvmMain`.
    val jniMain = sourceSets.create("jniMain") {
        dependsOn(sourceSets.getByName("commonMain"))
    }
    sourceSets.getByName("androidMain").dependsOn(jniMain)
    if (targets.findByName("jvm") != null) {
        sourceSets.getByName("jvmMain").dependsOn(jniMain)
    }

    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        getByName("commonMain").dependencies {
            api(project(":sqlx4k"))
        }
        getByName("commonTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.assertk)
            implementation(libs.kotlinx.io.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.assertk)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.core.ktx)
        }
    }
}

rustJni {
    crateName = "sqlx4k_sqlite_cipher"
}
