# Test report — APK Wall · Krishna v1.5.3

**Date:** 2026-10-02  
**Package:** `com.krishna.apkguard` · version code 9 / version name 1.5.3  
**Minimum Android:** API 29 (Android 10) · target API 35

## Changes made for the reported issues

- Optional asset encryption is preflighted before it mutates the APK. If native libraries or unsupported asset-access paths make redirection unsafe, or there are no eligible supported text/config assets, APK Wall records a clear skip reason and continues with the other selected protections instead of failing the whole job.
- The skip result is reported as **Completed with warning** at 100%; the selected DEX wrapper, APK rebuild and output signing remain active. The failure handler also preserves the reached phase/progress rather than replacing it with a misleading 0%.
- Replaced the theme-dependent toolbar navigation rendering with a high-contrast, accessible custom hamburger control. The page title is shown beside it. Starting a job scrolls back to the progress card so the beginner-facing progress and warning are in view.

## Build and regression checks

The following final-build tasks completed successfully:

```text
:app:testDebugUnitTest
:fixture:assembleDebug
:app:assembleDebug
```

All five JVM DEX/resource regression tests passed. Kotlin compilation reported only Android API deprecation warnings for system bar colors and the legacy activity-result method; there were no build errors. (The final v1.5.3 check did not rerun `lintDebug`.)

## On-device drawer check

- **Device:** AOSP Android 10 / API 29 x86_64 emulator, QEMU TCG (no KVM).
- Installed and launched the final v1.5.3 build.
- Confirmed the hamburger is visible, its accessibility description is **Open navigation drawer**, and tapping it opens the drawer. The drawer shows Home, About, Credits · KRISHNA, and Protection methods.
- Final-build screenshot: [runtime proof image](/home/ubuntu/KrishnaApkWall-v1.5.3-runtime-proof.png).

## Native-library asset-skip processing check

- **Input:** First-party test fixture retaining `assets/guard.txt` and a deliberately inserted dummy `lib/arm64-v8a/libdummy.so` entry.
- **Selected option:** Encrypt small text/config assets, in addition to the always-on DEX wrapper.
- **Observed result:** APK Wall reached 100% and saved a signed output. Its persisted status was **Completed with warning** and explicitly said: `asset encryption skipped: Native libraries are present; their asset access cannot be safely rewritten.` It also listed the AES-256-GCM DEX wrapper, debug-mode disabling and output signing/verification as applied.
- **Output checks:** `apksigner verify --verbose` reported one signer and successful v3 verification. The rebuilt archive contained the encrypted DEX payload under `assets/.krishna-guard/` (932 bytes, no DEX magic); the fixture's `assets/guard.txt` remained plaintext as expected because only asset encryption was skipped.
- **Important test-fixture limit:** The inserted `.so` was a marker, not a valid native library. Android therefore rejected this synthetic output with `INSTALL_FAILED_NO_MATCHING_ABIS`; this deliberately artificial native-marker test validates the skip-and-continue transformation/signing path, **not** installation or runtime behavior for arbitrary native APKs. Do not infer that any real native-library APK will install or run after transformation without target-specific testing.

A valid first-party fixture's protected runtime had already passed the API 29 launch test on v1.5.2, including the AES-GCM DEX loader, string decoder, supported asset reader and ID-based resource lookup. v1.5.3 keeps that runtime path; the new native-marker fixture was not a runtime-launch test.

## Known limits and user guidance

- Supported asset encryption is limited to allow-listed text/config files under `assets/` (up to 1 MiB per file / 16 MiB total) and direct `AssetManager.open(String[, int])` paths. APKs containing native libraries, `openFd`/`openNonAsset` use, WebView `android_asset` URLs, or no eligible assets receive a warning and continue with asset encryption skipped.
- DEX must be decrypted at runtime to execute; a determined analyst can recover it and the embedded key. Runtime root/debugger/emulator/instrumentation checks are opt-in heuristics that can false-positive and be bypassed.
- Resource-name obfuscation may break dynamic name lookups. R8/ProGuard, code/resource shrinking, control-flow obfuscation, generic reflection/dynamic-loading rewrites, NDK conversion, runtime-memory integrity, Play Integrity/server checks, virtualization, white-box cryptography and commercial shielding are not generic APK-only switches.
- Re-signing replaces the original publisher identity. The output generally cannot update the original publisher-signed installation; keep the original APK and signing identity safe. Test on representative devices and flows before distributing.
- APK Wall itself requires API 29+; protected output requires Android 9/API 28+.
