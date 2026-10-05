# Tamper and signature checks: arbitrary prebuilt APKs

**Scope.** Offline APK-only transformer; arbitrary already-built single/base APK, no source/backend, existing `AppComponentFactory` DEX wrapper. This evaluates a genuine selectable protection—not a cosmetic toggle.

## Finding

**Not safely supportable as a generic on-by-default option for arbitrary APKs.** There are two distinct controls:

1. **Package-signature/integrity validation:** Android’s Package Manager verifies an APK’s signature at install/update. For v2+ signatures, any APK modification outside the signing block invalidates verification. This is already a platform control, not an added runtime feature. A post-build transformer must re-sign its output; the tool needs a private key and must verify the final artifact.
2. **An app’s own runtime signer check:** Technically injectable into a compatible APK. The wrapper can query its installed package signing certificates and compare them to an expected signer. But after modifying the APK, the check must pin the certificate of the **final re-signed APK**—not the input signer. Re-signing with the utility’s key changes app identity for Android update/signature-permission purposes; it generally cannot update the original app or preserve its Play signing identity. The check only detects a different signer, not arbitrary in-process tampering, and a determined attacker can patch or hook the local check.

Thus post-build injection is technically possible for a constrained, tested set of APKs; it is not a reliable generic protection that can be safely promised for arbitrary input. Source/build-time integration is the robust path for the original developer, who can provision/preserve the app signing key and explicitly maintain the check and rotation policy. A wrapper does not eliminate binary compatibility and signing constraints.

## Minimum requirements for a real runtime option

- A correctly functioning DEX/manifest transformer that preserves the target app’s startup behavior and delegates through its existing `AppComponentFactory`.
- A chosen final signing key and explicit authorization to re-sign; final APK signing must occur after every byte-level modification, followed by signature verification for the declared Android-version range.
- Pinning policy based on the **final certificate(s)**, with explicit support for multiple signers and signing-certificate rotation (or a documented fail-closed/release-update process). Runtime lookup uses `PackageInfo.signingInfo`/`SigningInfo` on API 28+; older Android requires a compatibility path using deprecated signature APIs.
- Startup checks that run early enough for the desired threat model, and a defined response to failure. This still is not a server-independent root of trust.

The best production integrity signal available from the platform is install-time signature verification. Google Play Integrity can provide a Play-recognized app-integrity verdict, but Google documents it as a signal for a **backend** to act on; it does not satisfy this offline/no-backend requirement.

## Compatibility and false-positive risks

- Re-signing with a new key breaks update continuity with releases signed by the original key and can break integrations or access controlled by the original certificate (for example, signature-level permissions or certificate-registered API services). Play distribution may require the recognized app signing key; an offline transformer cannot obtain/replace that private key.
- Certificate rotation and multiple-signer APKs make simplistic “one hard-coded certificate” checks reject valid builds. Android’s `SigningInfo` exposes current signers and, for eligible single-signer packages, verified signing history; policy must distinguish these cases.
- APK signature schemes and minimum SDKs matter. v1 alone does not protect all ZIP metadata; v2+ verifies the whole APK. Re-signing scheme choices that omit schemes needed by supported old devices can cause install failures. Any edits after signing invalidate the signature.
- Generic DEX/manifest rewriting can conflict with multidex, obfuscation, manifest variants, custom class loading, startup/provider ordering, native libraries, or platform/API behavior. Failure at the wrapper’s early startup path can prevent the whole app from launching. These are per-APK compatibility risks, not solved by signature checks.
- Runtime checks are bypassable on a compromised device or by patching the APK/check. Do not market them as proof that code/process state is untampered. Fail-closed behavior can also deny legitimate users after key changes or tool signing misconfiguration.

## Recommendation

Do **not** expose this as a generic active toggle for arbitrary APKs. Keep Android’s mandatory install-time signature enforcement and verify every transformed output. If offered later as an advanced, explicitly opt-in feature, require the owner’s signing key (or make the consequences of a tool-controlled new key explicit), signer/rotation policy, and compatibility validation; label it accurately as a local signer-identity check with bypass limits. For meaningful app-integrity assurance, integrate at source/build time; use Play Integrity only where a supported backend is acceptable.

## Sources

| Title | URL | Claim supported |
|---|---|---|
| Android Developers — APK signing | https://source.android.com/docs/security/features/apksigning | Android requires signed APKs; Package Manager verifies the APK at install; v1 has coverage limitations; v2+ detects APK modifications and supports backward compatibility only when v1 is also present for older devices. |
| Android Developers — `apksigner` | https://developer.android.com/tools/apksigner | `apksigner` needs the signer’s private key; any changes after signing invalidate the signature; `verify` checks support across Android versions; scheme flags and SDK-range options affect compatibility. |
| Android Developers — `SigningInfo` | https://developer.android.com/reference/android/content/pm/SigningInfo | Runtime APIs return current APK signers and signing history; multiple signers are treated as a set; certificate rotation/history must be considered. |
| Android Developers — `PackageInfo` | https://developer.android.com/reference/android/content/pm/PackageInfo | `signingInfo` is populated with `GET_SIGNING_CERTIFICATES`; legacy `signatures` is deprecated at API 28 and does not account for rotation in the same way. |
| Android Developers — App signing | https://developer.android.com/studio/publish/app-signing | The app signing key establishes installed-app/update identity; Play App Signing uses Google’s app-signing key for distribution, distinct from an upload key. |
| Android Developers — Play Integrity overview | https://developer.android.com/google/play/integrity/overview | `appIntegrity` can identify an unmodified binary recognized by Play; intended use is for a backend to respond to the verdict, outside the stated offline constraint. |
| OWASP MASTG — Mobile App Tampering and Reverse Engineering | https://mas.owasp.org/MASTG/0x04c-Tampering-and-Reverse-Engineering/ | Tampering includes changes to compiled apps and running processes; Android openness means no generic anti-tamper process always works and defenses can be reversed/bypassed. |
| Android Developers — `AppComponentFactory` | https://developer.android.com/reference/android/app/AppComponentFactory | Factory hooks instantiate application components and select the class loader; this is a constrained lifecycle hook, not a general guarantee that arbitrary app transformations are compatible. |
