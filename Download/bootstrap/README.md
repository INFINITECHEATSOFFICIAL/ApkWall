# Krishna APK Guard bootstrap DEX

This small Java module is compiled separately and embedded into protected APKs as the public DEX loader stub. It decrypts the app-owned AES-GCM payload in process-private storage and hands the authenticated code to Android's `AppComponentFactory` classloader hook.

It is original Krishna APK Guard code and does not copy or link Apk Wall VIP's proprietary implementation or native libraries. Runtime DEX loading is a compatibility-sensitive technique: generated outputs are limited to Android 9/API 28 and newer, and must be tested on the target app/device. AES-GCM wrapping is a deterrent to casual static extraction, not an unbreakable shield; code must be decrypted in memory/on-device to run.
