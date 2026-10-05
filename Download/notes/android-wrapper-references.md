# Research references for the DEX-wrapper prototype

- Android security guidance on Dynamic Code Loading: https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
  - Android warns that dynamic code loading increases tampering/code-execution risk; prefer in-app code, use trusted app-private storage, and verify code integrity before loading. Remote-code loading can violate Play policies.
- Android 14 behavior changes, safer dynamic code loading: https://developer.android.com/about/versions/14/behavior-changes-14
  - If target API 34+, dynamically loaded DEX/JAR/APK files must be marked read-only before content is written; Android recommends avoiding DCL when possible and verifying integrity of pre-existing dynamic code.
- AppComponentFactory API reference: https://developer.android.com/reference/android/app/AppComponentFactory
  - Introduced API 28; `instantiateClassLoader` is called before application components/application context initialization and can return a custom classloader. It also has factory hooks for application/activity/provider/receiver/service instantiation.
- REAndroid ARSCLib repository: https://github.com/REAndroid/ARSCLib
  - Apache-2.0 library exposes Android binary XML/resource-table parsing and writing, and allows APK archive rewriting.
- ARSCLib project license: https://raw.githubusercontent.com/REAndroid/ARSCLib/master/LICENSE
  - Apache License 2.0; include attribution/license when bundled.
- ARSCLib APK module source: https://raw.githubusercontent.com/REAndroid/ARSCLib/master/src/main/java/com/reandroid/apk/ApkModule.java
- ARSCLib manifest block source: https://raw.githubusercontent.com/REAndroid/ARSCLib/master/src/main/java/com/reandroid/arsc/chunk/xml/AndroidManifestBlock.java
- APKEditor documentation: https://github.com/REAndroid/APKEditor
  - A useful reference for resource rewriting; its `protect` command describes resource-file obfuscation, not the loader-based DEX encryption being prototyped here.

Observed facts from the supplied sample (static inspection only so far): its embedded `assets/SignatureKiller/origin.apk` contains a normal 6,726,960-byte root `classes.dex`; outer APK has a 3,492-byte root bootstrap DEX and 6,716,288-byte non-DEX `assets/classes.dex` plus `assets/classes2.dex` (10,448 bytes), with 11 native libraries. These facts support the user's correction that the sample is a real DEX-wrapping/encryption design, but do not establish that each UI option is implemented or works.

Runtime testing is restricted to a disposable, no-network Android Emulator; no supplied APK has been executed on the host or user's device.

## Follow-up references for v1.4 factory chaining and code loading (checked 2026-09-30)

- Android `DexClassLoader` API: https://developer.android.com/reference/dalvik/system/DexClassLoader — describes loading interpreted classes from APK/JAR DEX; optimized directory is ignored since API 26.
- Android `AppComponentFactory` API: https://developer.android.com/reference/android/app/AppComponentFactory — documents the custom classloader hook and component/Application instantiation delegation used by the bootstrap.
- Android dynamic-code-loading security guidance: https://developer.android.com/privacy-and-security/risks/dynamic-code-loading — recommends private storage and integrity checks and warns about tampering risks.
- Android 14 behavior changes: https://developer.android.com/about/versions/14/behavior-changes-14 — read-only dynamically loaded code requirement.
- ARSCLib upstream repository (Apache-2.0): https://github.com/REAndroid/ARSCLib — APK archive/binary-resource/manifest parser used for the post-build rewrite.
