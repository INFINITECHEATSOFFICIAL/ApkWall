package com.krishna.apkguard

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Narrow, fail-closed post-build DEX rewrites. This is not R8 or control-flow obfuscation. */
internal object DexTransformEngine {
    private const val MIN_LITERAL_LENGTH = 4
    private const val MAX_ENCRYPTED_LITERALS_PER_DEX = 100_000
    private const val STRING_PREFIX = "__KGSTR1__"
    private const val ASSET_MANAGER = "Landroid/content/res/AssetManager;"
    private const val CODEC_CLASS = "Lcom/krishna/apkguard/bootstrap/GuardCodec;"
    private const val INPUT_STREAM = "Ljava/io/InputStream;"
    private const val STRING = "Ljava/lang/String;"
    private const val INT = "I"

    private val random = SecureRandom()
    private val cipherLocal = ThreadLocal.withInitial { Cipher.getInstance("AES/GCM/NoPadding") }
    private val decodeStringMethod = ImmutableMethodReference(
        CODEC_CLASS,
        "decodeString",
        listOf(STRING),
        STRING
    )
    private val openAssetMethod = ImmutableMethodReference(
        CODEC_CLASS,
        "openAsset",
        listOf(ASSET_MANAGER, STRING),
        INPUT_STREAM
    )
    private val openAssetModeMethod = ImmutableMethodReference(
        CODEC_CLASS,
        "openAsset",
        listOf(ASSET_MANAGER, STRING, INT),
        INPUT_STREAM
    )

    data class Result(
        val dexBytes: ByteArray,
        val encryptedStringCount: Int,
        val rewrittenAssetOpenCount: Int,
        val unsupportedAssetOpenCount: Int,
        val dexStrings: Set<String>
    )

    fun scanStrings(dexBytes: ByteArray): Set<String> = literalStrings(parse(dexBytes))

    /** Return only executable const-string values; DEX string IDs also contain field/type names. */
    private fun literalStrings(dexFile: DexBackedDexFile): Set<String> {
        val literals = LinkedHashSet<String>()
        for (classDef in dexFile.classes) {
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    if (instruction.opcode !in STRING_OPCODES) continue
                    val reference = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
                    literals.add(reference.string)
                }
            }
        }
        return literals
    }

    /**
     * Rewrites only literal strings of at least four UTF-16 characters and direct
     * AssetManager.open(String[, int]) virtual invocations. All other opcodes and references
     * are retained by dexlib2. Rewriter errors deliberately fail the complete job.
     */
    fun rewrite(
        dexBytes: ByteArray,
        aesKey: ByteArray,
        encryptStrings: Boolean,
        rewriteAssetOpens: Boolean,
        outputDex: File
    ): Result {
        val parsed = parse(dexBytes)
        val originalStrings = literalStrings(parsed)
        val counters = Counters()

        val module = object : RewriterModule() {
            override fun getMethodImplementationRewriter(rewriters: Rewriters): Rewriter<MethodImplementation> {
                return object : Rewriter<MethodImplementation> {
                    override fun rewrite(methodImplementation: MethodImplementation): MethodImplementation {
                        val mutable = MutableMethodImplementation(methodImplementation)
                        val initialSize = mutable.instructions.size
                        var changed = false

                        // Walk backwards so inserted instructions do not change the yet-to-visit
                        // indexes. MutableMethodImplementation relocates branches, labels and tries.
                        for (index in initialSize - 1 downTo 0) {
                            val instruction = mutable.instructions[index]
                            val methodRef = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                            if (methodRef != null && methodRef.definingClass == ASSET_MANAGER) {
                                if (rewriteAssetOpens && methodRef.name == "openFd") {
                                    counters.unsupportedAssetOpenCount++
                                }
                                if (rewriteAssetOpens && methodRef.name in setOf("openNonAsset", "openNonAssetFd")) {
                                    counters.unsupportedAssetOpenCount++
                                }
                                if (rewriteAssetOpens && isAssetOpen(methodRef, instruction.opcode)) {
                                    replaceAssetOpen(mutable, index, instruction, methodRef)
                                    counters.rewrittenAssetOpenCount++
                                    changed = true
                                    continue
                                }
                            }

                            if (encryptStrings && instruction.opcode in STRING_OPCODES) {
                                val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference
                                    ?: continue
                                val literal = ref.string
                                if (literal.length < MIN_LITERAL_LENGTH) continue
                                if (counters.encryptedStringCount >= MAX_ENCRYPTED_LITERALS_PER_DEX) {
                                    throw IllegalArgumentException("A DEX contains more than $MAX_ENCRYPTED_LITERALS_PER_DEX eligible string literals; string encryption stopped rather than leaving a partial result")
                                }
                                val destinationRegister = (instruction as? OneRegisterInstruction)?.registerA
                                    ?: throw IllegalArgumentException("Unsupported const-string register encoding")
                                val encryptedLiteral = encryptLiteral(literal, aesKey)
                                mutable.replaceInstruction(
                                    index,
                                    BuilderInstruction31c(
                                        Opcode.CONST_STRING_JUMBO,
                                        destinationRegister,
                                        ImmutableStringReference(encryptedLiteral)
                                    )
                                )
                                mutable.addInstruction(
                                    index + 1,
                                    BuilderInstruction3rc(
                                        Opcode.INVOKE_STATIC_RANGE,
                                        destinationRegister,
                                        1,
                                        decodeStringMethod
                                    )
                                )
                                mutable.addInstruction(
                                    index + 2,
                                    BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, destinationRegister)
                                )
                                counters.encryptedStringCount++
                                changed = true
                            }
                        }
                        return if (changed) mutable else methodImplementation
                    }
                }
            }
        }

        if (outputDex.exists() && !outputDex.delete()) {
            throw IllegalStateException("Cannot replace temporary DEX output")
        }
        val rewritten = DexRewriter(module).dexFileRewriter.rewrite(parsed)
        DexPool.writeTo(outputDex.absolutePath, ImmutableDexFile.of(rewritten))
        val output = outputDex.readBytes()
        if (output.size < 112 || !output.copyOfRange(0, 4).contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0a))) {
            throw IllegalStateException("DEX transformer emitted an invalid file")
        }
        return Result(
            dexBytes = output,
            encryptedStringCount = counters.encryptedStringCount,
            rewrittenAssetOpenCount = counters.rewrittenAssetOpenCount,
            unsupportedAssetOpenCount = counters.unsupportedAssetOpenCount,
            dexStrings = originalStrings
        )
    }

    private fun parse(dexBytes: ByteArray): DexBackedDexFile {
        if (dexBytes.size < 112 || !dexBytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x64, 0x65, 0x78, 0x0a))) {
            throw IllegalArgumentException("Malformed DEX file")
        }
        val versionText = String(dexBytes, 4, 3, Charsets.US_ASCII)
        val version = versionText.toIntOrNull()
            ?: throw IllegalArgumentException("Malformed DEX version header: $versionText")
        val opcodes = try {
            Opcodes.forDexVersion(version)
        } catch (error: Exception) {
            throw IllegalArgumentException("Unsupported DEX version $versionText", error)
        }
        return DexBackedDexFile(opcodes, dexBytes)
    }

    private fun isAssetOpen(reference: MethodReference, opcode: Opcode): Boolean {
        if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
        if (reference.name != "open" || reference.returnType != INPUT_STREAM) return false
        val parameters = reference.parameterTypes.map(CharSequence::toString)
        return parameters == listOf(STRING) || parameters == listOf(STRING, INT)
    }

    private fun replaceAssetOpen(
        method: MutableMethodImplementation,
        index: Int,
        original: com.android.tools.smali.dexlib2.iface.instruction.Instruction,
        reference: MethodReference
    ) {
        val withMode = reference.parameterTypes.count() == 2
        val replacementReference = if (withMode) openAssetModeMethod else openAssetMethod
        when (original) {
            is Instruction35c -> method.replaceInstruction(
                index,
                BuilderInstruction35c(
                    Opcode.INVOKE_STATIC,
                    original.registerCount,
                    original.registerC,
                    original.registerD,
                    original.registerE,
                    original.registerF,
                    original.registerG,
                    replacementReference
                )
            )
            is Instruction3rc -> method.replaceInstruction(
                index,
                                BuilderInstruction3rc(
                                    Opcode.INVOKE_STATIC_RANGE,
                                    original.startRegister,
                                    original.registerCount,
                                    replacementReference
                                )
            )
            else -> throw IllegalArgumentException("Unsupported AssetManager.open instruction format: ${original.opcode}")
        }
    }

    private fun encryptLiteral(value: String, key: ByteArray): String {
        val nonce = ByteArray(12)
        synchronized(random) { random.nextBytes(nonce) }
        val cipher = cipherLocal.get() ?: Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, blob, 0, nonce.size)
        System.arraycopy(encrypted, 0, blob, nonce.size, encrypted.size)
        return STRING_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(blob)
    }

    private data class Counters(
        var encryptedStringCount: Int = 0,
        var rewrittenAssetOpenCount: Int = 0,
        var unsupportedAssetOpenCount: Int = 0
    )

    private val STRING_OPCODES = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)
}
