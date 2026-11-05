#!/bin/sh
# Descarrega o XcodeGen para /tmp/xcodegen (os workflows correm-no a partir daí), verificando o SHA-256.
set -e
VERSION=2.46.0
URL=https://github.com/yonaskolb/XcodeGen/releases/download/$VERSION/xcodegen.zip
SHA=4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806
curl -fsSL --retry 3 -o /tmp/xcodegen.zip "$URL"
echo "$SHA  /tmp/xcodegen.zip" | shasum -a 256 -c -
unzip -q /tmp/xcodegen.zip -d /tmp/xcodegen
