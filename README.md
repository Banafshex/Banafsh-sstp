<div align="center">

# Banafsh-sstp

**A simple, Persian-friendly Android app for connecting to free SSTP servers from VPN Gate.**

English | [فارسی](README.fa.md)

[![Telegram](https://img.shields.io/badge/Telegram-Banafsh__sstp-7352CC?logo=telegram&logoColor=white)](https://t.me/Banafsh_sstp)
[![License: MIT](https://img.shields.io/badge/License-MIT-7352CC.svg)](LICENSE)

</div>

<p align="center">
  <img src="docs/screenshots/home-connected.png" width="22%" alt="Connected">
  <img src="docs/screenshots/home-disconnected.png" width="22%" alt="Disconnected">
  <img src="docs/screenshots/servers.png" width="22%" alt="Free servers">
  <img src="docs/screenshots/settings.png" width="22%" alt="Settings">
</p>

## What is it?

Banafsh-sstp is built for everyday users, not network experts. Pick a free server, tap **Connect**, and you are done. No accounts, no config files, no technical knowledge needed.

It is a fork of [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) by kittoku, redesigned with a friendlier interface and extra features.

## Features

- **One-tap connection**: a big Connect / Disconnect button on the home screen.
- **Free servers list**: servers from VPN Gate, refreshed automatically, with a country flag for each one.
- **Automatic server testing**: ping and bandwidth are shown for every server. Working servers appear first, unreachable ones are dimmed at the end.
- **Iranian apps without VPN**: banks and Iranian apps connect directly, even while the VPN is on. The app list is kept up to date automatically.
- **Auto reconnect**: the connection is restored when it drops.
- **Share via QR code**: send your current server to a friend, who scans it inside the app. The QR is not readable by a regular camera app.
- **In-app updates and news**: new versions are checked daily and can be installed from inside the app.
- **Persian and English**: follows your system language by default; the layout direction adapts automatically.
- **Status notification**: shows Connecting / Getting IP / Connected and your IP, with a Disconnect button.
- **Advanced settings** for power users, hidden by default so beginners are not overwhelmed.

## Installation

1. Open the [Releases](../../releases) page and download the latest `.apk` file.
2. Open the file on your phone. If Android asks, allow installing from this source.
3. If **Play Protect** shows a warning, tap **Install anyway**. This happens because the app is not published on Google Play.

Requires Android 8.0 or newer.

## How to use

1. Tap **Select free server** and choose one with a green indicator (fast and reachable).
2. Tap **Connect**.
3. Keep **Iranian apps without VPN** on if you want banks and Iranian apps to keep working directly.

If a server does not connect, just pick another one. Free servers are run by volunteers and come and go.

## Privacy and safety

The servers are run by volunteers around the world. Volunteer servers may keep connection logs for at least two weeks, and VPN Gate itself keeps connection logs for at least three months.

- Do **not** use this service for sensitive logins such as banking.
- Iranian banks will not open with a non-Iranian IP anyway.
- Use it at your own responsibility.

## Build from source

Builds run on GitHub Actions:

- Every push builds a debug APK, available under the **Actions** tab as an artifact.
- The **Release APK** workflow builds a signed release. It needs three repository secrets holding your signing key (keystore, key alias and passwords).

To build locally, open the project in Android Studio and run the `assembleDebug` Gradle task.

## Credits and license

- Original project: [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) by **kittoku** (MIT).
- Banafsh-sstp by **BANAFSHX**.
- Free servers: [VPN Gate](https://www.vpngate.net/), University of Tsukuba, Japan.
- Font: [Vazirmatn](https://github.com/rastikerdar/vazirmatn).

Released under the [MIT License](LICENSE). Follow updates on Telegram: [@Banafsh_sstp](https://t.me/Banafsh_sstp)
