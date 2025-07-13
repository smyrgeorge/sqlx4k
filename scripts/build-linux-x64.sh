#!/usr/bin/env sh

set -e

./gradlew build -x:sqlx4k-mysql:examples:build -x:sqlx4k-postgres:examples:build -x:sqlx4k-sqlite:examples:build -Ptargets=androidNativeX64
./gradlew build -x:sqlx4k-mysql:examples:build -x:sqlx4k-postgres:examples:build -x:sqlx4k-sqlite:examples:build -Ptargets=linuxX64
