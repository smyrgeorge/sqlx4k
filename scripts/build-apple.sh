#!/usr/bin/env sh

./gradlew build -x:sqlx4k-mysql:examples:build -x:sqlx4k-postgres:examples:build -x:sqlx4k-sqlite:examples:build -Ptargets=iosArm64
./gradlew build -x:sqlx4k-mysql:examples:build -x:sqlx4k-postgres:examples:build -x:sqlx4k-sqlite:examples:build -Ptargets=macosArm64
./gradlew build -x:sqlx4k-mysql:examples:build -x:sqlx4k-postgres:examples:build -x:sqlx4k-sqlite:examples:build -Ptargets=macosX64
