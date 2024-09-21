plugins {
    kotlin("jvm")
    // https://github.com/vanniktech/gradle-maven-publish-plugin
//    id("com.vanniktech.maven.publish") version "0.29.0"
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

//mavenPublishing {
//    coordinates(
//        groupId = group as String,
//        artifactId = name,
//        version = version as String
//    )
//
//    pom {
//        name = "sqlx4k-sqldelight-dialect-postgres"
//        description = "Sqldelight support for sqlx4k (PostgreSQL dialect)."
//        url = "https://github.com/smyrgeorge/sqlx4k"
//
//        licenses {
//            license {
//                name = "MIT License"
//                url = "https://github.com/smyrgeorge/sqlx4k/blob/main/LICENSE"
//            }
//        }
//
//        developers {
//            developer {
//                id = "smyrgeorge"
//                name = "Yorgos S."
//                email = "smyrgoerge@gmail.com"
//                url = "https://smyrgeorge.github.io/"
//            }
//        }
//
//        scm {
//            url = "https://github.com/smyrgeorge/sqlx4k"
//            connection = "scm:git:https://github.com/smyrgeorge/sqlx4k.git"
//            developerConnection = "scm:git:git@github.com:smyrgeorge/sqlx4k.git"
//        }
//    }
//
//    // Configure publishing to Maven Central
//    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
//
//    // Enable GPG signing for all publications
//    signAllPublications()
//}
