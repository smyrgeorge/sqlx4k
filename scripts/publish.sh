#!/usr/bin/env sh

./gradlew clean

./gradlew :sqlx4k-mysql:build -Ptargets=iosArm64
./gradlew :sqlx4k-mysql:build -Ptargets=androidNativeX64
./gradlew :sqlx4k-mysql:build -Ptargets=androidNativeArm64
./gradlew :sqlx4k-mysql:build -Ptargets=macosArm64
./gradlew :sqlx4k-mysql:build -Ptargets=macosX64
./gradlew :sqlx4k-mysql:build -Ptargets=linuxArm64
./gradlew :sqlx4k-mysql:build -Ptargets=linuxX64

./gradlew :sqlx4k-postgres:build -Ptargets=iosArm64
./gradlew :sqlx4k-postgres:build -Ptargets=androidNativeX64
./gradlew :sqlx4k-postgres:build -Ptargets=androidNativeArm64
./gradlew :sqlx4k-postgres:build -Ptargets=macosArm64
./gradlew :sqlx4k-postgres:build -Ptargets=macosX64
./gradlew :sqlx4k-postgres:build -Ptargets=linuxArm64
./gradlew :sqlx4k-postgres:build -Ptargets=linuxX64

./gradlew :sqlx4k-sqlite:build -Ptargets=iosArm64
./gradlew :sqlx4k-sqlite:build -Ptargets=androidNativeX64
./gradlew :sqlx4k-sqlite:build -Ptargets=androidNativeArm64
./gradlew :sqlx4k-sqlite:build -Ptargets=macosArm64
./gradlew :sqlx4k-sqlite:build -Ptargets=macosX64
./gradlew :sqlx4k-sqlite:build -Ptargets=linuxArm64
./gradlew :sqlx4k-sqlite:build -Ptargets=linuxX64

cat local.properties >> gradle.properties
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
