#!/bin/sh
# Descarrega o MobileVLCKit (VLC para iOS, LGPL 2.1) para Frameworks/, verificando o SHA-256.
set -e
URL=https://download.videolan.org/cocoapods/prod/MobileVLCKit-3.7.5-a213fd11-6de05adc.tar.xz
SHA=a76f7a6cfcde4209b56f943e6e18400659d8a7a93ff482f8b1d059468d11e6be
cd "$(dirname "$0")/.."
if [ -d Frameworks/MobileVLCKit.xcframework ]; then exit 0; fi
mkdir -p Frameworks build
[ -f build/mobilevlckit.tar.xz ] || curl -fL --retry 3 -o build/mobilevlckit.tar.xz "$URL"
echo "$SHA  build/mobilevlckit.tar.xz" | shasum -a 256 -c -
tar -xJf build/mobilevlckit.tar.xz -C build
mv build/MobileVLCKit-binary/MobileVLCKit.xcframework Frameworks/
cp build/MobileVLCKit-binary/COPYING.txt Frameworks/MobileVLCKit-COPYING.txt
