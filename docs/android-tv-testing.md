# Android TV verification

Use a TV or an Android TV AVD, not a phone rotated sideways. Do not use a mouse or touch input. For repeatable automated checks, `:app:testDebugUnitTest` covers routing policy and `:app:connectedDebugAndroidTest` runs the TV smoke tests on an attached device. The manual **Build APK** workflow can also run the latter on a TV emulator.

1. Install the TV APK. Launch its 16:9 banner from the TV launcher; finish or skip the intro using only the remote. Verify Home fits inside the visible screen and the focused control has a bright outline.
2. Move from Connect to the server, protocol and Settings cards. Open the overflow menu and use DNS and manual server entry (including the TV keyboard). Back should unwind each screen. Repeat in Persian (RTL) and English (LTR).
3. Open a country in the server list. Move focus while ping results arrive; it should stay on the same server. Back should return to the same country. Pick a server and use the TV VPN consent dialog to connect and then disconnect.
4. In Settings → Split tunneling, enable the rule and select a TV-only app. Search for a second app, toggle it with OK, clear the search and return with Back. Re-open Settings and check that both selections survived. Neither the picker nor any automatic routing switches should appear on Home.
5. On a network you control, use two apps with observable network traffic. With split tunneling off, both use the VPN. In bypass mode only checked apps go direct. In only-selected mode only checked apps use the VPN; connecting with an empty/uninstalled selection must not create an unrestricted tunnel. After every change, reconnect. Repeat for SSTP and L2TP/IPsec using known-good endpoints.
6. Restart the app; verify choices, language and dark appearance persist. No camera permission or QR flow should be offered. If an APK update is available, its name must start with `Banafsh-TV-`.
