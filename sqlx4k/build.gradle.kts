import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("io.github.smyrgeorge.sqlx4k.multiplatform.rust")
    // https://github.com/vanniktech/gradle-maven-publish-plugin
    id("com.vanniktech.maven.publish") version "0.29.0"
}

repositories {
    mavenCentral()
}

group = rootProject.group
version = rootProject.version

kotlin {
    sourceSets {
        configureEach {
            languageSettings.progressiveMode = true
        }
        val nativeMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }

        val nativeTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.assertk)
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = group as String,
        artifactId = name,
        version = version as String
    )

    pom {
        name = "sqlx4k"
        description = "A high-performance Kotlin Native database driver for PostgreSQL, MySQL, and SQLite."
        url = "https://github.com/smyrgeorge/sqlx4k"

        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/smyrgeorge/sqlx4k/blob/main/LICENSE"
            }
        }

        developers {
            developer {
                id = "smyrgeorge"
                name = "Yorgos S."
                email = "smyrgoerge@gmail.com"
                url = "https://smyrgeorge.github.io/"
            }
        }

        scm {
            url = "https://github.com/smyrgeorge/sqlx4k"
            connection = "scm:git:https://github.com/smyrgeorge/sqlx4k.git"
            developerConnection = "scm:git:git@github.com:smyrgeorge/sqlx4k.git"
        }
    }

    // Configure publishing to Maven Central
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // Enable GPG signing for all publications
    signAllPublications()
}
