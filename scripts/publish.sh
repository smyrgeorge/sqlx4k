#!/usr/bin/env sh

cat local.properties >> gradle.properties
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
