plugins {
    kotlin("jvm")
    kotlin("plugin.spring") version "2.1.0"
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

java.sourceCompatibility = JavaVersion.VERSION_21

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    // Persistent
    // https://github.com/pgjdbc/r2dbc-postgresql
    implementation("org.postgresql:r2dbc-postgresql")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xwhen-guards")
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Disables the build of '**-plain.jar'
tasks.getByName<Jar>("jar") {
    enabled = false
}