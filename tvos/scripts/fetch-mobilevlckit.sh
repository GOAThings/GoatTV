#!/bin/sh
# Descarrega o MobileVLCKit (VLC para iOS, LGPL 2.1) para Frameworks/.
set -e
URL=https://download.videolan.org/cocoapods/prod/MobileVLCKit-3.7.5-a213fd11-6de05adc.tar.xz
cd "$(dirname "$0")/.."
if [ -d Frameworks/MobileVLCKit.xcframework ]; then exit 0; fi
mkdir -p Frameworks build
[ -f build/mobilevlckit.tar.xz ] || curl -fL --retry 3 -o build/mobilevlckit.tar.xz "$URL"
tar -xJf build/mobilevlckit.tar.xz -C build
mv build/MobileVLCKit-binary/MobileVLCKit.xcframework Frameworks/
cp build/MobileVLCKit-binary/COPYING.txt Frameworks/MobileVLCKit-COPYING.txt
