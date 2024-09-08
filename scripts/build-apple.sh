#!/usr/bin/env sh

./gradlew build -Ptargets=iosArm64
./gradlew build -Ptargets=macosArm64
./gradlew build -Ptargets=macosX64
