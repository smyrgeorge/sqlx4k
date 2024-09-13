#!/usr/bin/env sh

cat local.properties >> gradle.properties
./gradlew clean publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
