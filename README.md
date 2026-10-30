# Cepİndir

Cepİndir is an Android media downloader built with Kotlin and Jetpack Compose. It uses the yt-dlp extraction engine for public media URLs.

## Features
- Paste a link or share a link from another app into Cepİndir.
- Analyze a supported URL and display available video resolutions.
- Choose automatic best quality or a maximum video height.
- Download video or extract audio as MP3 using FFmpeg.
- Run downloads in an Android foreground service with notification progress and a stop action.
- Save completed files to Downloads/CepIndir on Android 10 and newer.
- Update the yt-dlp engine from its stable release channel.

## Supported sources and limitations
yt-dlp includes extractors for a large number of sites, including YouTube, Instagram, Facebook, TikTok, X, Reddit, Pinterest, Vimeo, Twitch and many others. Platform support is not a guarantee that every URL will work: websites may change their APIs, protections or delivery formats. Some sources may require login or may be temporarily unavailable. Private/login-gated content, DRM-protected streams and access-control circumvention are not supported by this app.

Use the app only for media you own, have permission to download, or are otherwise legally entitled to save. Follow the applicable platform terms and copyright law.

## Open-source notices
This project integrates the GPL-3.0-licensed youtubedl-android wrapper and FFmpeg Android package published by the youtubedl-android project. The source for Cepİndir in this repository is licensed under GPL-3.0-only; the complete license text is in LICENSE. yt-dlp and FFmpeg retain their respective upstream notices and licenses. See THIRD_PARTY_NOTICES.md.

## Build
GitHub Actions builds a debug APK on pushes to main. The workflow artifact is named CepIndir-debug-apk.
