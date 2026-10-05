# APK signing and publisher identity — feasibility memo

**Method:** APK signing and publisher identity (certificate-based signer identity / runtime signer check)

## Finding

APK signing is a platform-enforced **integrity and signer-continuity mechanism**, not a generic code-hardening switch. Android requires installable APKs to be signed; its v2+ schemes authenticate the APK as a whole, and any modification invalidates the signature. Android certificates are self-signed and Android does not CA-validate them: the security meaning is principally continuity/possession of the signing key, not independent proof that certificate subject text names a real publisher. [1][2]

## Can this be an arbitrary post-build option?

**Signing an already-built APK is technically possible without source**, using `apksigner`, but signing requires the signer’s private key and certificate. It does not preserve an arbitrary input APK’s publisher identity unless the original publisher’s signing key (or an authorized Play/App Store signing flow) is available. A wrapper/manifest/DEX edit also invalidates the original APK signature; the transformed output must be signed again. If a utility signs with its own/new key, Android treats that key as the app’s signer. The package generally cannot update an installed original signed by a different key; signature-based relationships such as shared UID/signature permissions may also cease to work. [1][2][3]

An embedded **runtime signer check** is a distinct, source/build-integrated behavior: code can query the installed package’s signing data (`SigningInfo` / `GET_SIGNING_CERTIFICATES`, API 28+) and compare current signer(s) or signing history against a pin. But a post-build utility cannot transparently preserve the original signer after altering the APK. If it embeds the original certificate and then re-signs with a new key, that runtime comparison will fail; if it pins the utility’s new key, it only confirms the utility-produced signer, not the original publisher. A local check can be patched by an attacker who can modify/repackage the app and is not a robust trust anchor against a determined client-side attacker. [1][4]

## Requirements and limits

- **For signing only:** a release private key and matching certificate under explicit user/publisher control; use Android `apksigner` and verify the result. Choose enabled signature schemes and min/max SDK compatibility deliberately; Android documents v1 compatibility for older devices and v2+ for newer devices. Any later byte-level APK change requires signing again. [2][3]
- **To retain original publisher/update identity:** access to the original app-signing private key or the publisher’s authorized managed signing workflow is required. A public certificate alone is insufficient. For a Play-distributed app, Play App Signing’s app-signing key is managed by Google; the upload key is not the installed APK’s signing identity. [1]
- **For a runtime pin/check:** code must be integrated into the installed app (the supplied AppComponentFactory wrapper could be an insertion point only if compatible); pin the intended output signer set/history and handle key rotation and multi-signer packages correctly. `SigningInfo` distinguishes current content signers from signing history; multi-signer identity is the complete signer set. Build/API compatibility and signer allowlist policy must be explicit. Runtime verification does not itself retain the old publisher identity or prevent patching. [4]
- A safe release pipeline must preserve the input, report the signer change, sign only with an authorized key, and test installation, cold start, updates, and app-specific integrations across target Android versions. Do not silently replace the publisher identity.

## Compatibility / false-positive risks

Generic transformation is not safe to promise for arbitrary APKs. Re-signing under a different key can break updates and signature-protected integrations; rotating keys requires a valid signing lineage, not a guessed certificate match. Checks can reject legitimate rotated signers or mishandle multiple signers if they compare only one certificate. Existing custom `Application`/`AppComponentFactory` behavior, manifest/provider initialization, multidex/native loading, and API-level differences can fail when a wrapper is inserted or changes the factory. These are app-specific compatibility risks; a signer mismatch is not necessarily evidence of tampering when the publisher legitimately changed signing configuration.

## Recommendation

**Do not expose this as an unconditional “protection” toggle for arbitrary prebuilt APKs.** It may be offered as an explicit **sign/re-sign output** action only when the user supplies/controls the signing key and accepts that the output’s publisher identity changes (or supplies the original authorized key). Treat runtime signer enforcement as a separate, opt-in, app-specific integration with a declared signer policy and compatibility testing—not a decorative active toggle. For arbitrary APKs with no source and no original key, report the original signature/signer and verify it offline if desired, but do not claim to preserve or enforce original publisher identity after transformation.

## Sources

1. Android Developers, [Sign your app](https://developer.android.com/studio/publish/app-signing) — signing keys/certificates, app-signing vs upload keys, Play App Signing, and continuity of the app-signing key for updates.
2. Android Open Source Project, [App signing](https://source.android.com/docs/security/features/apksigning) — Android install-time signature validation, self-signed certificates/no CA validation, v1/v2+ behavior, and the fact that any APK modification invalidates v2+ signatures.
3. Android Developers, [apksigner](https://developer.android.com/tools/apksigner) — post-build APK signing and verification commands; signing requires a private key and certificate; min/max SDK and scheme options; further APK edits invalidate signatures.
4. Android Developers, [SigningInfo](https://developer.android.com/reference/android/content/pm/SigningInfo) and [PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager) — runtime signer/history APIs and `GET_SIGNING_CERTIFICATES`; signer history supports rotation, and a multi-signer identity is the full signer set.
