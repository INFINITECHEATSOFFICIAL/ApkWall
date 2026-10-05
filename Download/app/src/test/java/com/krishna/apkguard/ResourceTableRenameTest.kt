package com.krishna.apkguard

import com.reandroid.apk.ApkModule
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class ResourceTableRenameTest {
    @Test
    fun fixtureResourceTableExposesRenameableAppEntry() {
        val apk = File(System.getProperty("user.dir"), "../fixture/build/outputs/apk/debug/fixture-debug.apk").canonicalFile
        assertTrue("Fixture APK must be built first: $apk", apk.isFile)

        val module = ApkModule.loadApkFile(apk)
        val table = module.getTableBlock() ?: error("Fixture APK has no resources.arsc table")
        val entries = ArrayList<String>()
        val iterator = table.getResources()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entries.add("package=${entry.getPackageName()} type=${entry.getType()} name=${entry.getName()} id=${entry.getHexId()}")
        }
        println("Resource-table entries (${entries.size}):\n${entries.joinToString("\n")}")
        assertTrue("Expected at least one app-owned resource entry; got: $entries", entries.any { it.contains("fixture_title") })

        val dexLiterals = LinkedHashSet<String>()
        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries()
            while (dexEntries.hasMoreElements()) {
                val dexEntry = dexEntries.nextElement()
                if (Regex("classes(\\d*)\\.dex").matches(dexEntry.name)) {
                    zip.getInputStream(dexEntry).use { dexLiterals.addAll(DexTransformEngine.scanStrings(it.readBytes())) }
                }
            }
        }
        assertTrue("The generated R field name must not be treated as a runtime lookup literal", "fixture_title" !in dexLiterals)
        assertTrue("The executable string-literal safety scan must remain active", dexLiterals.any { "STRING DECODE PATH EXECUTED" in it })
    }
}
