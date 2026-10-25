# GoatTV for Apple TV

The GoatTV IPTV player for tvOS. It uses the same GOAT account as GoatTV for Android, so playlists, favourites,
recent channels and settings are shared across every TV.

- `GoatCore/`: playlists (M3U, Xtream), XMLTV guide, account API and settings. Plain Swift that also builds on Linux.
- `GoatTV/`: the tvOS app (SwiftUI). Video playback uses [TVVLCKit](https://code.videolan.org/videolan/VLCKit) (LGPL 2.1).

## Building

The Xcode project is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen):

```sh
scripts/fetch-vlckit.sh
xcodegen generate
open GoatTV.xcodeproj
```

GitHub Actions (`.github/workflows/tvos.yml`) builds an unsigned `GoatTV.ipa` whenever `tvos/` changes. Each GoatTV
release ships it next to the Android APK: https://github.com/GOAThings/GoatTV/releases/latest/download/GoatTV.ipa
