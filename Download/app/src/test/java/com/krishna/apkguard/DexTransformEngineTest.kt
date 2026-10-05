package com.krishna.apkguard

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

class DexTransformEngineTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun encryptStringLiterals() {
        val result = rewrite(encryptStrings = true, rewriteAssetOpens = false)
        assertTrue("fixture should contain eligible literals", result.encryptedStringCount > 0)
    }

    @Test
    fun redirectAssetOpenCalls() {
        val result = rewrite(encryptStrings = false, rewriteAssetOpens = true)
        assertTrue("fixture should contain a direct AssetManager.open call", result.rewrittenAssetOpenCount > 0)
    }

    @Test
    fun applyBothTransformsTogether() {
        val result = rewrite(encryptStrings = true, rewriteAssetOpens = true)
        assertTrue(result.encryptedStringCount > 0)
        assertTrue(result.rewrittenAssetOpenCount > 0)
    }

    @Test
    fun injectedRangeInvokesUseMethodParameterCount() {
        val result = rewrite(encryptStrings = true, rewriteAssetOpens = true)
        val version = String(result.dexBytes, 4, 3, Charsets.US_ASCII).toInt()
        val dex = DexBackedDexFile(Opcodes.forDexVersion(version), result.dexBytes)
        var decoderCalls = 0
        for (classDef in dex.classes) {
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    if (instruction.opcode != Opcode.INVOKE_STATIC_RANGE) continue
                    val target = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (target.definingClass != "Lcom/krishna/apkguard/bootstrap/GuardCodec;") continue
                    val range = instruction as? Instruction3rc ?: error("invoke-static/range must use format 3rc")
                    assertEquals("range count must match ${target.name}${target.parameterTypes}", target.parameterTypes.size, range.registerCount)
                    when (target.name) {
                        "decodeString" -> decoderCalls++
                    }
                }
            }
        }
        assertTrue("fixture should include injected string decoder calls", decoderCalls > 0)
    }

    private fun rewrite(encryptStrings: Boolean, rewriteAssetOpens: Boolean): DexTransformEngine.Result {
        val input = javaClass.getResourceAsStream("/fixture-main.dex")
            ?.use { it.readBytes() }
            ?: error("missing fixture-main.dex test resource")
        val output = Files.createTempFile("apkwall-dex-test-", ".dex").toFile()
        return try {
            val result = DexTransformEngine.rewrite(input, key, encryptStrings, rewriteAssetOpens, output)
            assertArrayEquals("rewritten DEX must preserve the input version", input.copyOfRange(0, 8), result.dexBytes.copyOfRange(0, 8))
            DexTransformEngine.scanStrings(result.dexBytes)
            result
        } finally {
            output.delete()
        }
    }
}
