# White-box cryptography for arbitrary prebuilt APKs

**Finding.** White-box cryptography (WBC) is a way to implement a *particular cryptographic operation* so its secret key is harder to extract while an attacker can inspect and control the running software. It is not a generic APK encryption/obfuscation layer and does not automatically protect every secret or crypto call in an APK. For an offline utility receiving an arbitrary already-built APK with no app-specific integration, safely offering WBC as a generic active toggle is **not feasible**. It could be a real option only for a bounded, app-specific transformation where the utility can reliably identify and replace a supported operation and its key, preserve semantics, and validate the result.

## What the sources establish

- James A. Muir, [*A Tutorial on White-box AES*](https://eprint.iacr.org/2013/104.pdf), IACR Cryptology ePrint Archive (2013): defines WBC as cryptographic implementations for untrusted hosts where the attacker can inspect all intermediate computation; the goal is to make key extraction difficult. It also documents key extraction against the original Chow et al. AES implementation (about 2^30 work), illustrating that WBC is not an absolute guarantee.
- OneSpan, [*Overview of the White-Box Cryptography SDK*](https://docs.onespan.com/mobile/docs/wbc-sdk-ig-overview-of-the-sdk-4-31-0) (vendor documentation): its specific SDK workflow converts known cleartext key values into encoded key tables using a Table Generator, then integrates those tables in the application; its documented implementation is AES-128-CTR exposed through encrypt/decrypt methods. This is a concrete example of operation- and key-specific integration, not an APK-wide switch.
- Android Open Source Project, [*App signing*](https://source.android.com/docs/security/features/apksigning): all installed apps must be signed, and any modification to a v2+ signed APK—including ZIP metadata changes—invalidates its signature. A changed APK must therefore be signed again.
- Android Developers, [*Sign your app*](https://developer.android.com/studio/publish/app-signing): Android checks signing-certificate continuity for updates; an update signed with a different certificate is not accepted as an update to the existing app (unless it is treated as a different package/app). This matters when a post-build transformer lacks the original private signing key.

## Source/build-time vs. post-build

WBC is normally integrated where the app’s crypto operation and key are known: the developer substitutes a WBC implementation/table for the specific operation (often in application code or a vendor SDK/tooling workflow) and tests that callers, modes, formats, and error behavior remain compatible. This is not necessarily a source-only concept, but it does require app-specific knowledge and a mechanism to replace the relevant code/key.

A post-build DEX transformation is conceivable in principle without source if the exact crypto call sites, key material, and data flow are recoverable and a compatible implementation can be inserted and wired in. The mere presence of an `AppComponentFactory` wrapper does not establish those facts. It cannot generically make arbitrary existing crypto operations use WBC, and it cannot protect unrelated secrets. Runtime injection alone is not WBC unless it actually replaces the relevant cryptographic implementation and key use. Any bytecode/resource change also requires APK re-signing; without the original signing key the result generally cannot update the original installed app, and signing-sensitive integrations may fail.

## Minimum requirements for a real selectable option

1. A supported WBC implementation and generator for a declared set of algorithms/modes/key sizes, with clear licensing, platform/API, and runtime requirements.
2. Reliable identification of the target operation, actual secret key (or a supported way to provision/transform it), call sites, and all dependent inputs/outputs. For arbitrary unknown apps, static signatures alone cannot establish this reliably.
3. A transformation that replaces the target operation and key representation, not merely adds a library/table or changes application metadata; compatibility testing of functional behavior and crypto test vectors is required.
4. APK parsing/rebuilding and signing with a key appropriate to the user’s distribution/update path, plus explicit disclosure that original signer identity is not preserved without its private key.
5. Fail-closed behavior when the transformation cannot prove a supported, unambiguous match. A report-only result is preferable to claiming protection.

## Realistic limits and risks

- WBC raises the cost of extracting selected embedded keys; it does not guarantee secrecy against a fully controlling attacker, prevent runtime plaintext/key observation, or protect secrets that are still used through an unmodified path. The academic tutorial’s attack history is a concrete caution.
- Arbitrary APKs vary in DEX layout, multidex, obfuscation, reflection, native/JNI code, providers, custom crypto, and algorithm/mode use. AppComponentFactory wrapping does not solve those issues. Rewriting may break initialization order, class loading, performance, or semantics; false positives are likely if heuristic scans label literals, test vectors, certificates, public keys, or unrelated crypto calls as secret AES keys.
- Re-signing with a tool-owned key changes app identity for update/signature-permission purposes unless the user can supply the correct original signing key. In-app signature checks and external services that pin the signer may also reject a modified build.
- Android recommends Keystore when greater key security is needed; WBC is not a substitute for a hardware-backed/non-exportable key when that is the right design, nor does it retrofit server-held key management (excluded here).

**Recommendation.** Do not expose “White-box cryptography” as a generic active-protection toggle for arbitrary APKs. If product scope later supports known apps or a verifiable crypto call/key pattern, offer it only as a narrowly scoped, opt-in transformation with explicit algorithm support, signing-key requirements, compatibility checks, and honest “not applied” outcomes. Do not report success merely because wrapper code or a WBC library was added.
