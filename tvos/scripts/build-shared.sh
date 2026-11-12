#!/bin/sh
# O núcleo partilhado com o Android (módulo shared/ em Kotlin) como GoatShared.xcframework, em GoatCore/Frameworks/
# (o GoatCore é que o liga; ver GoatCore/Package.swift). Só num Mac, com Java 17 ou mais. Corre-se a partir de tvos/.
set -e
cd "$(dirname "$0")/.."
(cd .. && ./gradlew --configure-on-demand --no-configuration-cache -q :shared:assembleGoatSharedReleaseXCFramework)
mkdir -p GoatCore/Frameworks
rm -rf GoatCore/Frameworks/GoatShared.xcframework
cp -R ../shared/build/XCFrameworks/release/GoatShared.xcframework GoatCore/Frameworks/
