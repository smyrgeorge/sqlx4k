#!/usr/bin/env sh

cat local.properties >> gradle.properties

./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=iosArm64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=androidNativeX64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=androidNativeArm64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=macosArm64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=macosX64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=linuxArm64
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=linuxX64


git checkout .
