# Banafsh for Android TV

**[فارسی](README.fa.md)** | English

A remote-controlled SSTP and L2TP/IPsec VPN client for Android TV / Google TV (Android 6.0 or later). Use the D-pad to move, **OK** to choose and **Back** to return. The dark purple and lilac home screen shows the connection, server and protocol. Use the menu to enter a server manually or change DNS.

### Split tunneling

By default all apps use the VPN. Open **Settings → Split tunneling**, enable it, choose a routing mode and select apps. Either **bypass the VPN for selected apps** or **use the VPN only for selected apps**. In the latter mode at least one selected app must be installed. The picker includes TV-launchable apps and sideloaded apps that have a conventional launcher entry. Changes are saved immediately and apply when you reconnect, with either protocol. No regional app or website exceptions are applied.

### Build and install

- With JDK 21 and the Android SDK (compile SDK 36), run `bash gradlew :app:testDebugUnitTest :app:assembleDebug`. Install `app/build/outputs/apk/debug/app-debug.apk` on your TV, or use `adb install -r <path-to-apk>`.
- Alternatively, run the **Build APK** workflow on this branch and download the `Banafsh-TV-debug` artifact. See the [remote-only smoke test](docs/android-tv-testing.md) for device checks.
- Look for **Banafsh TV** in the TV launcher. The TV edition (`com.banafsh.sstp.tv`) installs separately from the phone app. In-app updates only use stable `tv-v…` releases with a matching `Banafsh-TV-<version>.apk`.

Free servers are provided by [VPN Gate](https://www.vpngate.net/) volunteers and may log connections. Avoid using them for banking or other sensitive accounts.

Based on [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client) by kittoku (MIT); see also the [L2TP core license](l2tp-core/LICENSE). Made by BANAFSHX.
