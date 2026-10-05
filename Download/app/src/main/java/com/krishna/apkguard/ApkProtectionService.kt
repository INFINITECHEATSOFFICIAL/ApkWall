package com.krishna.apkguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.model.ResourceEntry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Offline APK transformation service. DEX code is AES-GCM wrapped; other methods are not implied. */
class ApkProtectionService : Service() {
    private data class ProtectionOptions(
        val disableDebug: Boolean,
        val disableBackup: Boolean,
        val requireInputSignature: Boolean,
        val encryptStrings: Boolean,
        val encryptAssets: Boolean,
        val obfuscateResourceNames: Boolean,
        val blockRoot: Boolean,
        val blockEmulator: Boolean,
        val blockDebugger: Boolean,
        val blockInstrumentation: Boolean
    )

    private data class AssetAssessment(val aliases: List<String>, val skipReason: String?)

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var notifications: NotificationManager
    private var lastNotificationProgress = -1

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "APK protection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the local DEX encryption and APK signing progress"
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apk = intent?.getStringExtra(EXTRA_APK_URI)?.let(Uri::parse)
        val output = intent?.getStringExtra(EXTRA_OUTPUT_URI)?.let(Uri::parse)
        val options = ProtectionOptions(
            disableDebug = intent?.getBooleanExtra(EXTRA_DISABLE_DEBUG, true) ?: true,
            disableBackup = intent?.getBooleanExtra(EXTRA_DISABLE_BACKUP, false) ?: false,
            requireInputSignature = intent?.getBooleanExtra(EXTRA_REQUIRE_INPUT_SIGNATURE, false) ?: false,
            encryptStrings = intent?.getBooleanExtra(EXTRA_ENCRYPT_STRINGS, false) ?: false,
            encryptAssets = intent?.getBooleanExtra(EXTRA_ENCRYPT_ASSETS, false) ?: false,
            obfuscateResourceNames = intent?.getBooleanExtra(EXTRA_OBFUSCATE_RESOURCES, false) ?: false,
            blockRoot = intent?.getBooleanExtra(EXTRA_BLOCK_ROOT, false) ?: false,
            blockEmulator = intent?.getBooleanExtra(EXTRA_BLOCK_EMULATOR, false) ?: false,
            blockDebugger = intent?.getBooleanExtra(EXTRA_BLOCK_DEBUGGER, false) ?: false,
            blockInstrumentation = intent?.getBooleanExtra(EXTRA_BLOCK_INSTRUMENTATION, false) ?: false
        )
        if (apk == null || output == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        setForeground("Preparing local DEX protection…", 1)
        executor.execute {
            try {
                protect(apk, output, options)
            } catch (e: Exception) {
                try { contentResolver.delete(output, null, null) } catch (_: Exception) { }
                val detail = e.cause?.message ?: e.message ?: "check APK compatibility"
                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val lastProgress = prefs.getInt("progress", 1).coerceIn(1, 99)
                val phase = when {
                    lastProgress >= 95 -> "saving output"
                    lastProgress >= 69 -> "signing or verification"
                    lastProgress >= 55 -> "APK rebuild"
                    lastProgress >= 26 -> "content transforms"
                    lastProgress >= 22 -> "manifest processing"
                    lastProgress >= 14 -> "APK parsing"
                    else -> "preparation"
                }
                updateState(lastProgress, "Protection failed during $phase: ${detail.take(180)}", active = false)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun protect(apkUri: Uri, outputUri: Uri, options: ProtectionOptions) {
        val workDir = File(cacheDir, "guard-work-${System.currentTimeMillis()}").apply {
            if (!mkdirs()) throw IllegalStateException("Cannot create private work directory")
        }
        val input = File(workDir, "input.apk")
        val unsigned = File(workDir, "wrapped-unsigned.apk")
        val signed = File(workDir, "wrapped-signed.apk")
        val keyFile = File(workDir, "signing-key.p12")
        var module: ApkModule? = null
        var workingKey: ByteArray? = null
        try {
            updateState(3, "Reading selected APK…", true)
            copyUriToFile(apkUri, input) { copied, total ->
                val value = if (total > 0) (4 + copied * 8 / total).toInt().coerceIn(4, 12) else 7
                updateState(value, "Reading APK locally…", true)
            }
            if (input.length() > MAX_APK_BYTES) throw IllegalArgumentException("APK is larger than the current 500 MB prototype limit")
            val info = packageManager.getPackageArchiveInfo(input.absolutePath, 0)
                ?: throw IllegalArgumentException("Selected file is not a readable APK")
            val packageName = info.packageName ?: throw IllegalArgumentException("APK package name is missing")
            val samePackageAsProtector = packageName == applicationContext.packageName
            val versionName = info.versionName ?: "version unavailable"
            val originalVerified = try { ApkVerifier.Builder(input).build().verify().isVerified } catch (_: Exception) { false }
            if (options.requireInputSignature && !originalVerified) {
                throw IllegalArgumentException("The original APK signature is unsigned or invalid. Turn off the signature requirement or choose a signed APK.")
            }
            updateState(14, "Parsing APK… $packageName · $versionName", true)

            module = ApkModule.loadApkFile(input)
            if (!module.hasAndroidManifest()) throw IllegalArgumentException("AndroidManifest.xml could not be decoded")
            if (module.inputSources.any { it.alias.startsWith("assets/.krishna-guard/", ignoreCase = true) }) {
                throw IllegalArgumentException("This APK contains APK Wall's reserved payload path; already protected APKs cannot be wrapped again")
            }
            val manifest = module.androidManifest
            if (manifest.isSplit) throw IllegalArgumentException("Split APKs / app bundles are not supported yet. Select a single base APK instead.")
            val dexFiles = module.listDexFiles()
            if (dexFiles.isEmpty()) throw IllegalArgumentException("This APK has no standard classes*.dex files to protect")
            if (dexFiles.size > MAX_DEX_COUNT) throw IllegalArgumentException("The APK has too many DEX files for this prototype")
            val originalAppClass = manifest.applicationClassName.orEmpty()
            val application = manifest.applicationElement
                ?: throw IllegalArgumentException("APK application manifest section is missing")
            val oldFactory = application.searchAttributeByResourceId(android.R.attr.appComponentFactory)
            val originalFactory = oldFactory?.getValueAsString()?.takeIf { it.isNotBlank() && it != BOOTSTRAP_FACTORY }.orEmpty()
            if (options.disableDebug) {
                application.getOrCreateAndroidAttribute("debuggable", android.R.attr.debuggable).setValueAsBoolean(false)
            }
            if (options.disableBackup) {
                application.getOrCreateAndroidAttribute("allowBackup", android.R.attr.allowBackup).setValueAsBoolean(false)
            }
            val existingMin = manifest.minSdkVersion ?: 1
            if (existingMin > 35) throw IllegalArgumentException("This APK requires a newer Android API than this wrapper was built for")
            if (existingMin < MIN_WRAPPER_API) manifest.setMinSdkVersion(MIN_WRAPPER_API)
            manifest.setApplicationClassName(BOOTSTRAP_APPLICATION)
            application.getOrCreateAndroidAttribute("appComponentFactory", android.R.attr.appComponentFactory)
                .setValueAsString(BOOTSTRAP_FACTORY)
            module.refreshManifest()
            updateState(22, "Processing XML… Android 9+ runtime loader", true)

            val aesKey = ByteArray(32).also(SecureRandom()::nextBytes)
            workingKey = aesKey
            val runtimeFlags = protectionFlags(options)
            val configBytes = makeConfig(originalAppClass, originalFactory, dexFiles.size, runtimeFlags, aesKey)
            val encryptedInputs = ArrayList<Pair<String, ByteArray>>(dexFiles.size)
            val dexStrings = LinkedHashSet<String>()
            var encryptedStringCount = 0
            var rewrittenAssetOpenCount = 0
            var unsupportedAssetOpenCount = 0
            var totalDex = 0L
            var totalTransformedDex = 0L
            dexFiles.forEachIndexed { index, dexSource ->
                val name = dexSource.alias
                val dexInput = module.getInputSource(name)
                    ?: throw IllegalStateException("Could not read $name")
                val dex = readBytesLimited(dexInput, MAX_DEX_BYTES)
                totalDex += dex.size
                if (totalDex > MAX_TOTAL_DEX_BYTES) throw IllegalArgumentException("Total DEX size exceeds the 256 MB prototype limit")
                val tempDex = File(workDir, "rewritten-${index + 1}.dex")
                val rewrite = if (options.encryptStrings || options.encryptAssets) {
                    DexTransformEngine.rewrite(dex, aesKey, options.encryptStrings, options.encryptAssets, tempDex)
                } else null
                if (rewrite != null) {
                    dexStrings.addAll(rewrite.dexStrings)
                    encryptedStringCount += rewrite.encryptedStringCount
                    rewrittenAssetOpenCount += rewrite.rewrittenAssetOpenCount
                    unsupportedAssetOpenCount += rewrite.unsupportedAssetOpenCount
                } else if (options.obfuscateResourceNames) {
                    dexStrings.addAll(DexTransformEngine.scanStrings(dex))
                }
                val transformedDex = rewrite?.dexBytes ?: dex
                totalTransformedDex += transformedDex.size
                if (transformedDex.size > MAX_DEX_BYTES || totalTransformedDex > MAX_TOTAL_DEX_BYTES) {
                    throw IllegalArgumentException("Transformed DEX exceeds the current 128 MB per-file / 256 MB total limit")
                }
                val encrypted = encryptDex(transformedDex, aesKey)
                val entryName = "assets/.krishna-guard/payload-${index + 1}.bin"
                encryptedInputs.add(entryName to encrypted)
                updateState(
                    26 + ((index + 1) * 24 / dexFiles.size),
                    "Transforming and encrypting DEX… ${index + 1}/${dexFiles.size} · $encryptedStringCount strings",
                    true
                )
                java.util.Arrays.fill(dex, 0)
                if (transformedDex !== dex) java.util.Arrays.fill(transformedDex, 0)
                tempDex.delete()
            }

            if (options.encryptStrings && encryptedStringCount == 0) {
                throw IllegalArgumentException("String encryption was selected, but no eligible const-string literals were found")
            }
            var assetSkipReason: String? = null
            var assetAliases = emptyList<String>()
            if (options.encryptAssets) {
                assetSkipReason = when {
                    unsupportedAssetOpenCount > 0 -> "This APK uses openFd/openNonAsset access that cannot be safely redirected."
                    module.inputSources.any { it.alias.startsWith("lib/", ignoreCase = true) } -> "Native libraries are present; their asset access cannot be safely rewritten."
                    dexStrings.any { it.contains("file:///android_asset/", ignoreCase = true) } -> "WebView android_asset URLs were found and cannot be safely redirected."
                    rewrittenAssetOpenCount == 0 -> "No direct AssetManager.open(String[, int]) call sites were found."
                    else -> null
                }
                if (assetSkipReason == null) {
                    val assessment = assessTextConfigAssets(module)
                    assetAliases = assessment.aliases
                    assetSkipReason = assessment.skipReason
                }
                if (assetSkipReason != null) {
                    updateState(50, "Asset encryption skipped · $assetSkipReason Continuing with the other protections…", true)
                }
            }
            val encryptedAssetNames = if (options.encryptAssets && assetSkipReason == null) {
                encryptTextConfigAssets(module, aesKey, assetAliases)
            } else emptyList()
            val renamedResourceCount = if (options.obfuscateResourceNames) renameResourceNames(module, packageName, dexStrings) else 0
            if (options.obfuscateResourceNames && renamedResourceCount == 0) {
                throw IllegalArgumentException("No resource names could be safely renamed; the APK has no eligible resource-table entries")
            }

            val dexNames = dexFiles.map { it.alias }
            dexNames.forEach { module.removeInputSource(it) }
            module.add(ByteInputSource(assets.open("krishna-guard/bootstrap.dex").use { it.readBytes() }, "classes.dex"))
            encryptedInputs.forEach { (name, bytes) -> module.add(ByteInputSource(bytes, name)) }
            module.add(ByteInputSource(configBytes, CONFIG_ENTRY))
            if (encryptedAssetNames.isNotEmpty()) updateState(53, "Encrypted ${encryptedAssetNames.size} text/config assets · renamed $renamedResourceCount resource entries", true)
            stripOldJarSignatures(module)
            updateState(55, "Rebuilding protected APK…", true)
            module.writeApk(unsigned)
            if (!unsigned.isFile || unsigned.length() <= MIN_APK_BYTES) throw IllegalStateException("Rebuilt APK is unexpectedly empty")
            java.util.Arrays.fill(aesKey, 0)

            updateState(69, "Creating local signing identity…", true)
            val identity = LocalSigningIdentity.getOrCreate(this)
            keyFile.writeBytes(identity.pkcs12)
            val signing = loadSigningMaterial(keyFile, identity.alias, identity.password)
            updateState(75, "Signing encrypted APK…", true)
            val signConfig = ApkSigner.SignerConfig.Builder(identity.alias, signing.first, signing.second).build()
            ApkSigner.Builder(listOf(signConfig))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(MIN_WRAPPER_API)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .build()
                .sign()

            updateState(88, "Verifying APK signature and encrypted payload…", true)
            val verification = ApkVerifier.Builder(signed).build().verify()
            if (!verification.isVerified) {
                val reason = verification.errors.firstOrNull()?.toString()?.take(120)
                throw IllegalStateException("Output APK signature did not verify${reason?.let { ": $it" } ?: ""}")
            }
            ZipFile(signed).use { archive ->
                if (archive.getEntry("classes.dex") == null) throw IllegalStateException("Bootstrap DEX is missing from the output")
                if (archive.getEntry(CONFIG_ENTRY) == null || encryptedInputs.any { archive.getEntry(it.first) == null } || encryptedAssetNames.any { archive.getEntry(it) == null }) {
                    throw IllegalStateException("An authenticated encrypted DEX payload entry is missing")
                }
                if (archive.getEntry("classes2.dex") != null) throw IllegalStateException("A plaintext secondary DEX was left in the output")
            }
            val published = packageManager.getPackageArchiveInfo(signed.absolutePath, 0)
                ?: throw IllegalStateException("Android could not read the final APK package metadata")
            if (published.packageName != packageName) throw IllegalStateException("Package name changed unexpectedly during rebuild")

            updateState(95, "Saving protected APK to Downloads…", true)
            contentResolver.openOutputStream(outputUri, "w")?.use { destination ->
                FileInputStream(signed).use { source -> source.copyTo(destination, 1024 * 1024) }
            } ?: throw IllegalStateException("Could not write the Downloads output file")
            val count = contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            if (count <= 0) throw IllegalStateException("Could not publish the output in Downloads")

            val signer = verification.signerCertificates.firstOrNull()?.let(::certificateFingerprint) ?: "unavailable"
            val original = if (originalVerified) "original signature was valid" else "original signature was unsigned/invalid"
            val samePackageNote = if (samePackageAsProtector) "\nSame package as APK Wall: uninstall the currently installed copy before installing this differently signed output." else ""
            val appliedMethods = buildList {
                add("AES-256-GCM DEX wrapper")
                if (encryptedStringCount > 0) add("$encryptedStringCount encrypted DEX string literals")
                if (encryptedAssetNames.isNotEmpty()) add("${encryptedAssetNames.size} encrypted text/config assets")
                if (assetSkipReason != null) add("asset encryption skipped: $assetSkipReason")
                if (renamedResourceCount > 0) add("$renamedResourceCount resource names obfuscated")
                if (options.disableDebug) add("debug mode disabled")
                if (options.disableBackup) add("Android backup disabled")
                if (runtimeFlags and (FLAG_BLOCK_ROOT or FLAG_BLOCK_EMULATOR or FLAG_BLOCK_DEBUGGER or FLAG_BLOCK_INSTRUMENTATION) != 0) add("selected runtime checks enabled")
                add("output signed and verified")
            }.joinToString(", ")
            val completion = if (assetSkipReason == null) "Completed" else "Completed with warning"
            updateState(100, "$completion · $packageName\nApplied: $appliedMethods\n$original · output signer SHA-256: $signer\nSaved in Downloads/APK Wall$samePackageNote\nRuntime decrypts protected data; heuristics are bypassable and compatibility must be tested.", active = false)
            setNotification("Protected APK saved to Downloads", 100, ongoing = false)
        } finally {
            workingKey?.let { java.util.Arrays.fill(it, 0) }
            try { module?.close() } catch (_: Exception) { }
            input.delete(); unsigned.delete(); signed.delete(); keyFile.delete()
            workDir.deleteRecursively()
        }
    }

    private fun makeConfig(originalAppClass: String, originalFactory: String, dexCount: Int, flags: Int, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(CONFIG_MAGIC)
            data.writeUTF(originalAppClass)
            data.writeUTF(originalFactory)
            data.writeInt(dexCount)
            repeat(dexCount) { data.writeUTF("assets/.krishna-guard/payload-${it + 1}.bin") }
            data.writeInt(flags)
            key.forEachIndexed { index, value -> data.writeByte(value.toInt() xor KEY_MASK[index % KEY_MASK.size].toInt()) }
        }
        return out.toByteArray()
    }

    private fun protectionFlags(options: ProtectionOptions): Int {
        var flags = 0
        if (options.encryptStrings) flags = flags or FLAG_ENCRYPT_STRINGS
        if (options.encryptAssets) flags = flags or FLAG_ENCRYPT_ASSETS
        if (options.blockRoot) flags = flags or FLAG_BLOCK_ROOT
        if (options.blockEmulator) flags = flags or FLAG_BLOCK_EMULATOR
        if (options.blockDebugger) flags = flags or FLAG_BLOCK_DEBUGGER
        if (options.blockInstrumentation) flags = flags or FLAG_BLOCK_INSTRUMENTATION
        return flags
    }

    private fun assessTextConfigAssets(module: ApkModule): AssetAssessment {
        val candidates = module.inputSources.filter { source -> isTextConfigAsset(source.alias) }
        if (candidates.isEmpty()) {
            return AssetAssessment(emptyList(), "No supported text/config assets were found under assets/ (supported files must be at most 1 MiB).")
        }
        val accepted = ArrayList<String>()
        var totalBytes = 0L
        for (source in candidates) {
            val plain = try {
                readOptionalAsset(source, MAX_TEXT_ASSET_BYTES)
            } catch (e: IllegalArgumentException) {
                return AssetAssessment(emptyList(), e.message ?: "A supported asset exceeded the 1 MiB limit.")
            } ?: continue
            totalBytes += plain.size
            java.util.Arrays.fill(plain, 0)
            if (totalBytes > MAX_TOTAL_TEXT_ASSET_BYTES) {
                return AssetAssessment(emptyList(), "Supported text/config assets exceed the 16 MiB total safety limit.")
            }
            accepted.add(source.alias)
        }
        if (accepted.isEmpty()) {
            return AssetAssessment(emptyList(), "Supported text/config asset files are empty; no asset payload needs encryption.")
        }
        return AssetAssessment(accepted, null)
    }

    private fun encryptTextConfigAssets(module: ApkModule, key: ByteArray, aliases: List<String>): List<String> {
        val selectedAliases = aliases.toHashSet()
        val selected = module.inputSources.filter { source -> source.alias in selectedAliases }
        val applied = ArrayList<String>()
        for (source in selected) {
            val plain = readOptionalAsset(source, MAX_TEXT_ASSET_BYTES) ?: continue
            val encrypted = encryptAsset(plain, key)
            module.removeInputSource(source.alias)
            module.add(ByteInputSource(encrypted, source.alias))
            applied.add(source.alias)
            java.util.Arrays.fill(plain, 0)
        }
        return applied
    }

    private fun isTextConfigAsset(path: String): Boolean {
        if (!path.startsWith("assets/", ignoreCase = true) || path.startsWith("assets/.krishna-guard/", ignoreCase = true)) return false
        val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in TEXT_ASSET_EXTENSIONS
    }

    private fun readOptionalAsset(source: InputSource, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var oversized = false
        source.openStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > maxBytes) {
                    oversized = true
                    break
                }
                out.write(buffer, 0, count)
            }
        }
        if (oversized) throw IllegalArgumentException("Asset ${source.alias} exceeds the 1 MiB encryption limit; asset encryption was stopped rather than leaving it plaintext")
        return if (out.size() == 0) null else out.toByteArray()
    }

    private fun encryptAsset(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plain)
        return ByteArray(ASSET_MAGIC.size + nonce.size + encrypted.size).also { output ->
            System.arraycopy(ASSET_MAGIC, 0, output, 0, ASSET_MAGIC.size)
            System.arraycopy(nonce, 0, output, ASSET_MAGIC.size, nonce.size)
            System.arraycopy(encrypted, 0, output, ASSET_MAGIC.size + nonce.size, encrypted.size)
        }
    }

    private fun renameResourceNames(module: ApkModule, packageName: String, dexStrings: Set<String>): Int {
        val table = module.getTableBlock() ?: throw IllegalArgumentException("APK has no Android resource table to rename")
        val iterator = table.getResources()
        val entries = ArrayList<ResourceEntry>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.getPackageName() == packageName && !entry.getName().isNullOrBlank()) entries.add(entry)
        }
        val usedNames = entries.map { it.getName() }.toMutableSet()
        var serial = 0
        var renamed = 0
        for (entry in entries) {
            val originalName = entry.getName()
            if (originalName in dexStrings) continue
            var replacement: String
            do {
                replacement = "g${serial.toString(36)}"
                serial++
            } while (replacement in usedNames)
            usedNames.add(replacement)
            entry.setName(replacement)
            renamed++
        }
        return renamed
    }

    private fun encryptDex(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plain)
        return ByteArray(nonce.size + encrypted.size).also {
            System.arraycopy(nonce, 0, it, 0, nonce.size)
            System.arraycopy(encrypted, 0, it, nonce.size, encrypted.size)
        }
    }

    private fun stripOldJarSignatures(module: ApkModule) {
        val paths = module.inputSources.map { it.alias }.filter { path ->
            if (!path.startsWith("META-INF/", ignoreCase = true)) return@filter false
            val base = path.substringAfterLast('/').uppercase(Locale.ROOT)
            base == "MANIFEST.MF" || base.endsWith(".SF") || base.endsWith(".RSA") || base.endsWith(".DSA") || base.endsWith(".EC")
        }
        paths.forEach { module.removeInputSource(it) }
    }

    private fun readBytesLimited(source: InputSource, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        source.openStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalArgumentException("One DEX file exceeds the 128 MB prototype limit")
                out.write(buffer, 0, count)
            }
        }
        if (out.size() < 112 || !out.toByteArray().copyOfRange(0, 4).contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0a))) {
            throw IllegalArgumentException("A classes*.dex entry is malformed")
        }
        return out.toByteArray()
    }

    private fun loadSigningMaterial(file: File, alias: String, password: String): Pair<PrivateKey, List<X509Certificate>> {
        LocalSigningIdentity.ensureBouncyCastle()
        val attempts = listOf("PKCS12" to "BC", "PKCS12" to null, "JKS" to null)
        var last: Exception? = null
        for ((type, provider) in attempts) {
            try {
                val store = if (provider == null) KeyStore.getInstance(type) else KeyStore.getInstance(type, provider)
                FileInputStream(file).use { store.load(it, password.toCharArray()) }
                val key = store.getKey(alias, password.toCharArray()) as? PrivateKey
                    ?: throw IllegalArgumentException("Local private signing key is missing")
                val chain = store.getCertificateChain(alias)?.mapNotNull { it as? X509Certificate }
                    ?: throw IllegalArgumentException("Local signing certificate is missing")
                if (chain.isEmpty()) throw IllegalArgumentException("Local signing certificate chain is empty")
                return key to chain
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("Could not unlock the local APK signing identity", last)
    }

    private fun copyUriToFile(uri: Uri, destination: File, onProgress: (Long, Long) -> Unit) {
        val total = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }
        val source = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("Cannot open the selected APK")
        var copied = 0L
        source.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_APK_BYTES) throw IllegalArgumentException("APK exceeds the 500 MB limit")
                    output.write(buffer, 0, count)
                    onProgress(copied, total)
                }
                output.fd.sync()
            }
        }
        if (copied <= 0) throw IllegalArgumentException("Selected APK is empty")
    }

    private fun certificateFingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded).joinToString("") { "%02X".format(Locale.ROOT, it) }

    private fun updateState(progress: Int, message: String, active: Boolean = true) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("progress", progress.coerceIn(0, 100))
            .putString("message", message)
            .putBoolean("active", active)
            .apply()
        setNotification(message.lineSequence().firstOrNull()?.take(80) ?: "Protecting APK", progress, ongoing = active)
    }

    private fun setForeground(message: String, progress: Int) {
        startForeground(NOTIFICATION_ID, notification(message, progress, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    private fun setNotification(message: String, progress: Int, ongoing: Boolean) {
        if (progress == lastNotificationProgress && ongoing) return
        lastNotificationProgress = progress
        notifications.notify(NOTIFICATION_ID, notification(message, progress, ongoing))
    }
    private fun notification(message: String, progress: Int, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("APK Wall · Krishna")
            .setContentText(message.take(110))
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }

    companion object {
        const val PREFS = "apk_guard_processing"
        const val EXTRA_APK_URI = "apk_uri"
        const val EXTRA_OUTPUT_URI = "output_uri"
        const val EXTRA_DISABLE_DEBUG = "disable_debug"
        const val EXTRA_DISABLE_BACKUP = "disable_backup"
        const val EXTRA_REQUIRE_INPUT_SIGNATURE = "require_input_signature"
        const val EXTRA_ENCRYPT_STRINGS = "encrypt_strings"
        const val EXTRA_ENCRYPT_ASSETS = "encrypt_assets"
        const val EXTRA_OBFUSCATE_RESOURCES = "obfuscate_resource_names"
        const val EXTRA_BLOCK_ROOT = "block_root"
        const val EXTRA_BLOCK_EMULATOR = "block_emulator"
        const val EXTRA_BLOCK_DEBUGGER = "block_debugger"
        const val EXTRA_BLOCK_INSTRUMENTATION = "block_instrumentation"
        private const val BOOTSTRAP_APPLICATION = "android.app.Application"
        private const val BOOTSTRAP_FACTORY = "com.krishna.apkguard.bootstrap.GuardFactory"
        private const val CONFIG_ENTRY = "assets/.krishna-guard/config.bin"
        private const val CONFIG_MAGIC = 0x4B475032
        private const val FLAG_ENCRYPT_STRINGS = 1
        private const val FLAG_ENCRYPT_ASSETS = 1 shl 1
        private const val FLAG_BLOCK_ROOT = 1 shl 2
        private const val FLAG_BLOCK_EMULATOR = 1 shl 3
        private const val FLAG_BLOCK_DEBUGGER = 1 shl 4
        private const val FLAG_BLOCK_INSTRUMENTATION = 1 shl 5
        private const val MIN_WRAPPER_API = 28
        private const val MAX_DEX_COUNT = 32
        private const val MAX_APK_BYTES = 500L * 1024L * 1024L
        private const val MAX_DEX_BYTES = 128L * 1024L * 1024L
        private const val MAX_TOTAL_DEX_BYTES = 256L * 1024L * 1024L
        private const val MAX_TEXT_ASSET_BYTES = 1024 * 1024
        private const val MAX_TOTAL_TEXT_ASSET_BYTES = 16L * 1024L * 1024L
        private const val MIN_APK_BYTES = 1024L
        private val TEXT_ASSET_EXTENSIONS = setOf("txt", "json", "xml", "html", "htm", "css", "js", "properties", "csv", "yaml", "yml", "ini")
        private val ASSET_MAGIC = byteArrayOf(0x00, 0x4b, 0x47, 0x41, 0x53, 0x45, 0x43, 0x31)
        private val KEY_MASK = byteArrayOf(0x31, 0x6A, 0x5D, 0x22, 0x47, 0x19, 0x73, 0x4B, 0x2C, 0x55, 0x08, 0x6F, 0x14, 0x3D, 0x62, 0x27)
        private const val CHANNEL_ID = "apk_protection"
        private const val NOTIFICATION_ID = 4401
    }
}
