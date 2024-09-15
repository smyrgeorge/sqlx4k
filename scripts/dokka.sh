#!/usr/bin/env sh

./gradlew :dokkaHtmlMultiModule
rm -rf ./docs/*
cp -R ./build/dokka/htmlMultiModule/* ./docs/

version=$(./gradlew properties -q | awk '/^version:/ {print $2}')
git add --all
git commit am "Added documentation for '$version'."
