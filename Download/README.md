# APK Wall · Krishna v1.5.2

A native, offline Android utility for applying a constrained set of real protections to one complete/base APK. The selected APK stays on the device. Use APK Wall only on apps you own or are authorized to modify.

## Methods that actually run

### Always applied

- **AES-256-GCM DEX wrapper.** Encrypts every standard `classes*.dex` with a fresh per-output key, 96-bit nonce, and authenticated GCM tag. Plain DEX entries are removed and replaced by encrypted payloads plus a small `AppComponentFactory` bootstrap. At launch, the bootstrap authenticates and decrypts the DEX into the app's private read-only code cache, then delegates to a compatible original component factory.
- **APK signing and verification.** Any content edit requires a new signature. APK Wall signs with its device-local Krishna key and verifies the built APK before saving to `Downloads/APK Wall/`. The input publisher's signature is replaced; the output normally cannot update an installation signed by the original publisher.
- **Manifest debug flag.** `android:debuggable=false` is applied by default. This only changes that manifest flag; it does not remove logs, test endpoints, or app-level debug behavior.

### Optional, real transformations

- **DEX string-literal encryption.** Rewrites eligible `const-string` and `const-string-jumbo` literals of 4+ characters (up to 100,000 eligible literals per DEX) to AES-GCM ciphertext and runtime decode calls. It is not R8, shrinking, or control-flow obfuscation. Reflection, native code, dynamically assembled strings, and other indirect use patterns can be affected or remain outside coverage.
- **Small text/config asset encryption.** Encrypts supported non-empty files under `assets/` with `.txt`, `.json`, `.xml`, `.html`, `.htm`, `.css`, `.js`, `.properties`, `.csv`, `.yaml`, `.yml`, or `.ini` extensions, up to 1 MiB each. It rewrites direct DEX calls to `AssetManager.open(String)` and `AssetManager.open(String, int)` to a runtime decoder. The job fails closed if no eligible asset/call is found, an eligible file exceeds 1 MiB, or known unsupported paths such as `openFd`, `openNonAsset`, native access, or an `android_asset` WebView URL are detected. This is not arbitrary binary-asset or compiled-resource encryption.
- **Resource-name obfuscation.** Renames eligible app-owned names in `resources.arsc` while keeping numeric resource IDs. DEX string literals used for name-based resource lookup are preserved. Runtime-generated `Resources.getIdentifier()` names may still break; test the target app.
- **Optional launch checks.** Best-effort root, emulator, attached-debugger, and known Frida/Xposed-style marker heuristics run before target code when selected. A positive signal blocks startup. These checks are bypassable and can false-positive; they default off.
- **Optional backup hardening.** `android:allowBackup=false` is off by default because it changes restore behavior.
- **Input-signature policy.** The APK is always inspected for a valid signature. A separate optional switch refuses unsigned/invalid inputs; with it off, the modified output is still newly signed and verified.

The **Protection methods** drawer page maps each requested method to its actual status. The active offline options are deliberately limited to the transformations above. R8/ProGuard, code/resource shrinking, control-flow obfuscation, complete release hardening, general reflection/dynamic-loading rewriting, native-code conversion, runtime-memory integrity, code virtualization, white-box cryptography, and commercial shielding require source/build integration, a narrowly supported engine, or an authorized licensed SDK; APK Wall does not pretend to implement these for arbitrary APKs. Play Integrity and server-side validation are omitted as requested.

## Compatibility and security limits

This is layered resistance for offline analysis, **not unbreakable encryption**. Android must execute plaintext code. A determined analyst can extract decrypted DEX and runtime data or recover the embedded key from a running app. Key masking in the small config record is obfuscation, not secure key storage.

Target compatibility is app-specific. Split APK sets/app bundles, custom factories, signature-bound licensing, self-signature checks, remote modules, dynamic code, native access, and OEM-specific behavior may fail after wrapping or re-signing. Asset handling only covers the exact direct call sites above. Runtime heuristics are signals, not guarantees.

APK Wall itself requires Android 10 / API 29+. Protected output requires Android 9 / API 28+ because the wrapper uses `AppComponentFactory`. Uninstalling APK Wall removes its device-local signing identity; keep the original APK and test protected output on a disposable device before distribution. APK Wall rejects its reserved `assets/.krishna-guard/` paths and refuses to rewrap its own protected outputs.

## Build and test

Requirements: JDK 17+, Android SDK Platform 35, Build Tools 35.0.0, and the included Gradle 8.9 wrapper.

```sh
export ANDROID_HOME="$HOME/android-sdk"  # adjust for your SDK
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :bootstrap:jar :fixture:assembleDebug
```

The first-party fixture exercises the DEX loader, encrypted string decoding, a direct asset-open/decrypt path, and resource-ID lookup after resource-name obfuscation. APK Wall v1.5.2 was built and linted, its JVM DEX/resource regressions passed, and an all-options protected fixture was installed and exercised on Android 10 / API 29; see [test status](TEST-STATUS.md). This fixture test does not establish compatibility for arbitrary third-party APKs. The debug APK is for testing, not an organization release-signing process.

ARSCLib is included under Apache License 2.0; see `third_party/ARSCLib-LICENSE.txt`. dexlib2 and other dependencies retain their upstream license terms.

## Project notes

- [Hindi quick-start](QUICKSTART-HI.md)
- [Protection-method research and sources](PROTECTION-METHODS-RESEARCH.md)
- [Current test status](TEST-STATUS.md)
- Developer/credits: **Krishna** · [Telegram](https://t.me/KRISHNA1_EXE)
