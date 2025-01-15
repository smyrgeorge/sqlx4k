#!/usr/bin/env sh

./gradlew clean build -Ptargets=all

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
