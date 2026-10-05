# Native code (NDK/C++) as APK protection

**Finding:** Moving app logic from DEX to native C/C++ is a source/build-integrated rewrite, not a generic post-build APK switch. For an arbitrary already-built APK with no source, an offline utility cannot safely perform this as a selectable, general-purpose transformation. Adding a `.so` alone does not move or protect existing DEX logic.

## Why

Android’s [NDK getting-started guide](https://developer.android.com/ndk/guides) describes compiling C/C++ source into a native library and packaging it into the APK through the Android build system; Java calls that library through JNI. Its existing-project workflow requires native source or an existing library, a build script, and build-system packaging. The [JNI tips](https://developer.android.com/ndk/guides/jni-tips) further describe `System.loadLibrary`, declaring native methods and registering/binding them to native implementations. Thus, conversion means implementing equivalent behavior in C/C++ and changing the managed code/JNI boundary—not merely wrapping an APK or injecting a library.

An APK-only tool could, in principle, disassemble/reconstruct DEX, translate some known code patterns, inject JNI declarations and native implementations, then rebuild. But arbitrary apps include broad Android/Java APIs, framework callbacks, reflection, dynamic class loading, resources, and third-party code; the cited platform docs provide no general DEX-to-native conversion facility. A wrapper based on `AppComponentFactory` can redirect loading, but does not supply equivalent C++ implementations or make all application logic translatable. Any narrowly scoped converter would need to reject unsupported cases and validate behavior; it should not advertise universal conversion.

## Requirements and limits

- To do this reliably: source (or accurately reconstructed semantics for a deliberately limited subset), native C/C++ implementation, JNI declarations/bindings and load initialization, Android NDK/toolchain and a build/packaging step, and device testing.
- The [Android ABI guide](https://developer.android.com/ndk/guides/abis) explains that native libraries are machine-code/ABI-specific, are packaged under `/lib/<abi>/lib<name>.so`, and Android selects a compatible library at install time. Broad device support therefore requires building and packaging appropriate ABI variants; unsupported/missing libraries can fail at runtime.
- Any APK modification invalidates its existing signature; [apksigner documentation](https://developer.android.com/tools/apksigner) says the modified APK must be signed again using a signer private key and certificate. An arbitrary-APK utility generally lacks the original signing key, so the result cannot retain the original signer identity. This can disrupt updates/integrity checks and must be explicitly disclosed.
- Native code is not intrinsically tamper-proof or impossible to reverse engineer; it changes the analysis cost and attack surface, and introduces JNI/native crash and memory-safety risks.

## Compatibility and false-positive risks

Native build/ABI coverage and JNI linkage can cause install-time or runtime failures. Translation can alter exception handling, threading, object lifetimes, callback ordering, reflection/dynamic loading and interactions with framework or vendor APIs. Existing native libraries may conflict by ABI or library name. Re-signing or changing code can trigger app integrity/self-check failures and prevent same-signer updates. Scanners’ false-positive rates cannot be inferred from the Android docs cited here; opaque or newly injected native code may warrant scanner/device validation, but do not claim a guaranteed false positive or immunity. Preserve a clear unsupported/inconclusive outcome rather than silently producing an APK.

## Recommendation

Do **not** expose “Move code to native NDK/C++” as an active general-purpose post-build option for arbitrary APK input. It is valid as a build-time/source-based hardening choice, or as a tightly constrained, explicitly partial source-to-source/binary transformation with compatibility checks and a clear failure/re-signing disclosure. For the stated offline APK-only product, present it as **unavailable for generic prebuilt APKs**, not as an active toggle.

## Sources

1. **Get started with the NDK** — https://developer.android.com/ndk/guides — NDK compiles C/C++ into a native library packaged by the Android build system; Java accesses it through JNI; integration workflow uses sources/build scripts and packaging.
2. **JNI tips** — https://developer.android.com/ndk/guides/jni-tips — Native libraries are loaded with `System.loadLibrary`; native methods must be bound through registration or symbol discovery, with class-loader/JNI details relevant to integration.
3. **Android ABIs** — https://developer.android.com/ndk/guides/abis — Native code is architecture-specific; library packaging path and runtime ABI selection are defined; missing libraries can cause runtime crashes.
4. **apksigner** — https://developer.android.com/tools/apksigner — Changing an APK after signing invalidates its signature; signing requires the signer's private key and certificate.
