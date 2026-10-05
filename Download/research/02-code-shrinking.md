# Code shrinking: feasibility for an arbitrary prebuilt APK

**Finding.** Code shrinking removes statically unreachable code; it is not inherently an APK protection boundary. Google's R8 also performs optimization and, when enabled, obfuscates names. A source-less APK-only utility must not present generic R8-style shrinking as a safe, generally applicable post-build toggle.

## Evidence and workflow

- Android's [Enable app optimization with R8](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization) guide describes shrinking as whole-program reachability analysis from app entry points, and shows enabling optimization through an Android Gradle Plugin (AGP) release build configuration plus project keep-rule files. It recommends optimizing the final version that is tested before publishing and notes the extra build time and debugging difficulty. This is a **build-time project operation**, not a documented “shrink this APK” service.
- The primary [R8 project documentation](https://r8.googlesource.com/r8/) describes R8 as a whole-program optimizing compiler that **consumes class files**, uses ProGuard-format configuration including application entry points, and emits optimized DEX for Android. It documents command-line use with program inputs, an Android SDK `android.jar` library and a keep-rule configuration file. This is compiler input/output, not an APK-in-place postprocessor.
- Android's [Keep rule examples](https://developer.android.com/topic/performance/app-optimization/keep-rule-examples?hl=en) state that R8 cannot detect classes loaded by a string via reflection and may remove them; without a keep rule, a reflective integration can break, including a runtime crash. The examples also require preserving reflectively accessed constructors/members. These rules normally come from the library/app build inputs and knowledge of intended behavior.

## Feasibility and requirements

**No, not safely as a generic selectable option** under the stated constraints (arbitrary already-built single/base APK, no app source/build project, offline, no backend). The conventional R8 workflow expects program class-file inputs, app/library boundaries and entry points, Android platform library definitions, configuration/keep rules, and a release build/test loop. AGP is configured for the app's release variant; code and resource shrinking are distinct operations. An APK contains DEX and packaged resources, not the original app project and its build configuration. The official R8 workflow does not establish a general, semantics-preserving arbitrary-APK rewrite mode.

A custom post-build DEX analyzer/rewriter could attempt conservative removals, but safely reproducing whole-program reachability and app-specific keep knowledge is a separate, high-risk engineering project—not a routine R8 toggle. Removing nothing or merely rebuilding/repacking is not code shrinking. A narrowly scoped mode might be possible only with per-app analysis/allowlists, explicit user acceptance of risk, and extensive runtime verification; it would not meet a promise of safe arbitrary-APK support.

**Minimum requirements for a credible normal implementation:** the app's source/build project (or equivalent complete class-file inputs and dependency/library model), AGP/R8 and Android SDK platform definitions, the app's manifest/entry-point and dependency information, app/library consumer keep rules for reflection/JNI/serialization/dynamic loading, the ability to configure and rerun a release build, and device/instrumentation testing of the optimized result. Preserve the mapping file for retracing obfuscated stack traces. To apply R8 to an APK obtained from elsewhere, first recovering class files and reconstructing missing rules/configuration is not equivalent to having the original project and is not a safe generic substitute.

## Compatibility and false-positive risks

- Static reachability can mistake string-based reflection, annotation scans, JNI/native name lookups, plugin/dynamic loading, dependency injection/serialization conventions, or other runtime-discovered elements for dead code. Incorrectly removed or renamed elements can produce missing-class/method failures or crashes. Android's own guidance explicitly documents reflection as requiring keep rules.
- Manifest components are important roots, but cannot reveal every behavior reached indirectly. A generic tool cannot infer all application-specific roots or safe keep rules from the binary alone.
- False-positive “unused” findings are especially costly: a feature may fail only on a particular screen, account, locale, device/OS, or delayed background path. Testing cannot exhaust all possible executions of an arbitrary app.
- Shrinking may yield little or no size reduction, or be defeated by broad keep rules. It is not itself a security guarantee; name obfuscation/minification is a related but distinct R8 operation.
- Any rewritten APK must remain internally consistent and be re-signed; that changes its signing identity and prevents an in-place update over an install signed by the original publisher unless the same signing key is available. That packaging constraint is separate from shrinking correctness.

## Recommendation

Do not implement or advertise “Code shrinking” as an active generic toggle for the described APK-only utility. Leave it unavailable with a clear explanation that shrinking requires app build inputs and app-specific keep rules. Offer it only in a future source/build-project integration (or a clearly experimental, per-app expert workflow with explicit risk disclosure, testing, and verified outcomes); do not substitute a no-op or decorative toggle.
