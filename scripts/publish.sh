#!/usr/bin/env sh

set -e

./gradlew clean build -Ptargets=all -x :sqlx4k-sqlite:linkDebugTestLinuxX64

./gradlew :dokkaHtmlMultiModule
rm -rf ./docs/*
cp -R ./build/dokka/htmlMultiModule/* ./docs/

version=$(./gradlew properties -q | awk '/^version:/ {print $2}')
git add --all
git commit -m "Added documentation for '$version'."
git push

git tag "$version" -f
git push --tags -f

cat local.properties >> gradle.properties
./gradlew publishAllPublicationsToMavenCentralRepository -Ptargets=all
git checkout .
