#!/bin/sh
# Descarrega o TVVLCKit (VLC para tvOS, LGPL 2.1) para Frameworks/, verificando o SHA-256.
set -e
VERSION=3.7.4
URL=https://download.videolan.org/cocoapods/prod/TVVLCKit-3.0-20260930-0854.tar.xz
SHA=07892cae8e59e95a23f1fcf11692f802eae106312aa5ad9782faa2556b2d65d5
cd "$(dirname "$0")/.."
if [ -d Frameworks/TVVLCKit.xcframework ]; then echo "TVVLCKit $VERSION já está em Frameworks/"; exit 0; fi
mkdir -p Frameworks build
[ -f build/tvvlckit.tar.xz ] || curl -fL --retry 3 -o build/tvvlckit.tar.xz "$URL"
echo "$SHA  build/tvvlckit.tar.xz" | shasum -a 256 -c -
tar -xJf build/tvvlckit.tar.xz -C build
mv build/TVVLCKit-binary/TVVLCKit.xcframework Frameworks/
cp build/TVVLCKit-binary/COPYING.txt Frameworks/TVVLCKit-COPYING.txt
echo "TVVLCKit $VERSION pronto"
