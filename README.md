# Cobalt Clip Downloader

**English** | [Русский](README.ru.md)

[![License](https://img.shields.io/github/license/billwet75/CobaltClipDownloader)](LICENSE)
[![Release](https://img.shields.io/github/v/release/billwet75/CobaltClipDownloader)](https://github.com/billwet75/CobaltClipDownloader/releases)
[![Stars](https://img.shields.io/github/stars/billwet75/CobaltClipDownloader)](https://github.com/billwet75/CobaltClipDownloader/stargazers)
[![Forks](https://img.shields.io/github/forks/billwet75/CobaltClipDownloader)](https://github.com/billwet75/CobaltClipDownloader/forks)
[![Contributors](https://img.shields.io/github/contributors/billwet75/CobaltClipDownloader)](https://github.com/billwet75/CobaltClipDownloader/graphs/contributors)

An Android app built with Kotlin and Jetpack Compose for saving authorized
content from services supported by cobalt through your own cobalt API instance.

## Important limitations

- On Android 10 (API 29) and newer, a regular app **cannot read the clipboard
  in the background**, even when a foreground service is running. The app
  watches the clipboard only while its window is visible. For a reliable
  background workflow, use **Share → Cobalt Clip** in YouTube, Instagram, or
  another supported app.
- The public `api.cobalt.tools` instance is protected against bots and is not
  intended for third-party applications. Deploy your own cobalt API instance
  and enter its HTTPS address in the app settings.
- The app does not bypass DRM, authentication, or access restrictions. Use it
  only for your own content, openly licensed material, or content you have
  permission to save. Follow the terms of the source platform.

## Features

- recognizes links from YouTube, Instagram, TikTok, Facebook, X/Twitter,
  Vimeo, Reddit, Pinterest, Twitch, SoundCloud, and other cobalt services;
- adds multiple links as a batch and imports link lists from TXT files;
- receives links from the Android Share menu;
- automatically starts a detected link while the app is open;
- uses a `dataSync` foreground service with progress and cancellation controls
  in the notification;
- automatically retries temporary network failures and HTTP 429/5xx responses
  with exponential backoff and `Retry-After` support;
- downloads to a temporary `.part` file so unfinished media does not appear in
  the gallery and removes temporary files after success, failure, or
  cancellation;
- resumes interrupted downloads with HTTP Range when supported by the server;
- keeps a persistent Room queue across process restarts;
- schedules delayed downloads through WorkManager;
- retries, cancels, and deletes individual tasks;
- provides Economy, 1080p, Maximum, and Audio quick profiles;
- offers an incognito mode that does not retain completed tasks or errors;
- copies detailed errors from history;
- selects a custom output folder through the Storage Access Framework;
- selects video quality from 360p up to maximum;
- supports separate video and audio download modes;
- saves audio to `Music/CobaltClip`, images to `Pictures/CobaltClip`, and video
  to `Movies/CobaltClip`;
- retries failed downloads and shares completed files from history;
- displays speed, downloaded size, and estimated remaining time in the
  notification;
- supports API keys for private cobalt instances;
- handles cobalt `tunnel`, `redirect`, `picker`, and `error` responses;
- saves through MediaStore so completed files are available in the gallery;
- keeps a local history of task states and errors.

The `local-processing` response is intentionally unsupported because it
requires embedded FFmpeg/remux functionality and would significantly increase
the app size. The client sends `localProcessing: disabled`, so a correctly
configured server should return a ready-to-use tunnel or redirect response.

## Build

Android Studio, JDK 17+, and Android SDK 34 are required. The minimum supported
Android version is Android 10 (API 29).

1. Open the project directory in Android Studio.
2. Wait for Gradle synchronization to finish.
3. Select **Build → Build APK(s)**.

Alternatively, run:

```bash
gradle :app:assembleDebug
```

The APK will be created at
`app/build/outputs/apk/debug/app-debug.apk`.

For a public release, configure the permanent signing key in a local
`keystore.properties` file, which is excluded from Git, and run:

```bash
gradle :app:assembleRelease
```

The signed APK will be created at
`app/build/outputs/apk/release/app-release.apk`. Keep secure backups of both
the keystore and its passwords. Without the original key, future versions
cannot be installed as updates over the published app.

## Installation

Allow installation from the selected source and open the APK, or run:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

On Android 13 and newer, allow notifications. Files are saved through
MediaStore without requesting broad storage access.

## Configuration

### Domain and VPS

1. Register a domain or use one you already own.
2. Create a DNS subdomain for the API, such as `cobalt.example.com`.
3. Add an `A` record that points the subdomain to the VPS public IPv4 address.
   If the VPS supports IPv6, add an `AAAA` record as well.
4. Prepare a Linux VPS with a public IP address and open ports `80` and `443`.
   Wait for DNS propagation and verify that the subdomain resolves to the VPS.

### Installing cobalt

1. Install Docker Engine and the Docker Compose plugin on the VPS.
2. Create a dedicated cobalt directory and a `docker-compose.yml` file.
3. Copy the current Compose example from the
   [official cobalt guide](https://github.com/imputnet/cobalt/blob/main/docs/run-an-instance.md).
4. Replace the sample `API_URL` with the full HTTPS address of your subdomain,
   including the trailing slash, for example
   `https://cobalt.example.com/`.
5. Start the container with `docker compose up -d`.
6. Configure Nginx, Caddy, or another reverse proxy to accept HTTPS requests
   for the subdomain and forward them to cobalt on local port `9000`. Obtain a
   TLS certificate, for example through Let's Encrypt.
7. If the instance is exposed to the internet, enable abuse protection with
   API keys, Turnstile, or both by following the
   [instance protection guide](https://github.com/imputnet/cobalt/blob/main/docs/protect-an-instance.md).
8. Open `https://cobalt.example.com/` in a browser. The server should return
   JSON containing information about the instance.

See the current
[cobalt environment variables](https://github.com/imputnet/cobalt/blob/main/docs/api-env-variables.md)
and the
[cobalt API documentation](https://github.com/imputnet/cobalt/blob/main/docs/api.md)
for additional configuration details.

### Connecting the app

1. Open the **Settings** tab.
2. Enter the base HTTPS API address without an additional path, for example
   `https://cobalt.example.com`.
3. If API-key authentication is enabled on the server, enter the key created
   during server configuration.
4. Save the server settings, choose a quality, and optionally enable automatic
   downloads.

The API key is stored locally in the app's DataStore. For production
deployments with stronger security requirements, use encrypted storage or
short-lived Bearer tokens.

## API

The client sends `POST /` with `Accept: application/json`,
`Content-Type: application/json`, and the following fields:

```json
{
  "url": "https://...",
  "downloadMode": "auto",
  "videoQuality": "1080",
  "youtubeVideoCodec": "h264",
  "youtubeVideoContainer": "mp4",
  "filenameStyle": "pretty",
  "localProcessing": "disabled"
}
```

## Project structure

- `MainActivity.kt` — Compose UI, share intents, and clipboard monitoring;
- `DownloadService.kt` — foreground service, streaming downloads, and
  MediaStore integration;
- `CobaltClient.kt` — cobalt API calls and response parsing;
- `ScheduledDownloadWorker.kt` — scheduled task launcher;
- `data/` — persistent Room queue, task history, and DataStore settings.
