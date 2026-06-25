package com.grafana.faro

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeSymbolsLocatorTest {

    @Test
    fun `locate finds cxx RelWithDebInfo obj roots`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        val objRoot = File(
            buildDir,
            "intermediates/cxx/RelWithDebInfo/abc123/obj/arm64-v8a",
        )
        objRoot.mkdirs()
        File(objRoot, "libquickpizza_ndk_crash.so").writeBytes(ByteArray(256) { 1 })

        val result = NativeSymbolsLocator.locate(buildDir, "release")

        assertTrue(result.hasCxxNativeLibs)
        assertEquals(1, result.cxxObjRoots.size)
        assertEquals(
            File(buildDir, "intermediates/cxx/RelWithDebInfo/abc123/obj").absolutePath,
            result.cxxObjRoots.first().absolutePath,
        )
        assertFalse(result.agpNativeSymbolsZip?.exists() == true)

        buildDir.deleteRecursively()
    }

    @Test
    fun `locate ignores Debug cxx obj trees`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        val objRoot = File(buildDir, "intermediates/cxx/Debug/abc123/obj/arm64-v8a")
        objRoot.mkdirs()
        File(objRoot, "libdemo.so").writeBytes(byteArrayOf(1))

        val result = NativeSymbolsLocator.locate(buildDir, "release")

        assertFalse(result.hasCxxNativeLibs)
        assertTrue(result.cxxObjRoots.isEmpty())

        buildDir.deleteRecursively()
    }

    @Test
    fun `locate finds shipped native libs in stripped_native_libs`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        val soDir = File(
            buildDir,
            "intermediates/stripped_native_libs/stripReleaseDebugSymbols/out/lib/arm64-v8a",
        )
        soDir.mkdirs()
        File(soDir, "libreactnative.so").writeBytes(byteArrayOf(1))

        val result = NativeSymbolsLocator.locate(buildDir, "release")

        assertTrue(result.shipsNativeLibraries)
        assertEquals(1, result.shippedNativeLibraries.size)
        assertEquals("libreactnative.so", result.shippedNativeLibraries.first().fileName)

        buildDir.deleteRecursively()
    }

    @Test
    fun `locate detects release cxx build without obj libs`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        File(buildDir, "intermediates/cxx/RelWithDebInfo/abc123/obj/arm64-v8a").mkdirs()

        val result = NativeSymbolsLocator.locate(buildDir, "release")

        assertTrue(result.hasReleaseCxxBuild)
        assertFalse(result.hasCxxNativeLibs)

        buildDir.deleteRecursively()
    }
}

class NativeSymbolsDiagnosticsTest {

    @Test
    fun `missingSymbolsMessage mentions debugSymbolLevel when libs are shipped`() {
        val locate = NativeSymbolsLocator.LocateResult(
            agpNativeSymbolsZip = null,
            cxxObjRoots = emptyList(),
            shippedNativeLibraries = listOf(
                ShippedNativeLibrary("arm64-v8a", "libreactnative.so"),
            ),
            hasReleaseCxxBuild = false,
        )

        val message = NativeSymbolsDiagnostics.missingSymbolsMessage(locate, null)

        assertTrue(message.contains("debugSymbolLevel"))
        assertTrue(message.contains("libreactnative.so"))
        assertTrue(message.contains("native symbols were not uploaded"))
    }
}

class NativeSymbolsCollectorTest {

    @Test
    fun `collect merges AGP zip and cxx obj preferring larger so`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        val agp = File(buildDir, "native-debug-symbols.zip")
        ZipOutputStream(agp.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("arm64-v8a/libquickpizza_ndk_crash.so"))
            zos.write(ByteArray(64) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("arm64-v8a/libappmodules.so"))
            zos.write(ByteArray(128) { 2 })
            zos.closeEntry()
        }

        val objRoot = File(buildDir, "intermediates/cxx/RelWithDebInfo/x/obj")
        val abiDir = File(objRoot, "arm64-v8a").apply { mkdirs() }
        File(abiDir, "libquickpizza_ndk_crash.so").writeBytes(ByteArray(512) { 3 })
        File(abiDir, "libappmodules.so").writeBytes(ByteArray(32) { 4 })

        val collected = NativeSymbolsCollector.collect(
            NativeSymbolsLocator.LocateResult(
                agpNativeSymbolsZip = agp,
                cxxObjRoots = listOf(objRoot),
                shippedNativeLibraries = emptyList(),
                hasReleaseCxxBuild = true,
            ),
        )

        assertTrue(collected.agpZipUsed)
        assertEquals(1, collected.cxxObjRootsUsed.size)
        assertTrue(collected.libraryNames.contains("libquickpizza_ndk_crash.so"))
        assertTrue(collected.libraryNames.contains("libappmodules.so"))

        val arm64 = collected.byAbi["arm64-v8a"]!!
        assertEquals(512, arm64["arm64-v8a/libquickpizza_ndk_crash.so"]!!.size)
        assertEquals(128, arm64["arm64-v8a/libappmodules.so"]!!.size)

        buildDir.deleteRecursively()
    }

    @Test
    fun `collect from cxx obj only when AGP zip missing`() {
        val buildDir = Files.createTempDirectory("app-build").toFile()
        val objRoot = File(buildDir, "intermediates/cxx/RelWithDebInfo/x/obj")
        val abiDir = File(objRoot, "arm64-v8a").apply { mkdirs() }
        File(abiDir, "libquickpizza_ndk_crash.so").writeBytes(ByteArray(256) { 5 })

        val collected = NativeSymbolsCollector.collect(
            NativeSymbolsLocator.LocateResult(
                agpNativeSymbolsZip = null,
                cxxObjRoots = listOf(objRoot),
                shippedNativeLibraries = emptyList(),
                hasReleaseCxxBuild = true,
            ),
        )

        assertFalse(collected.agpZipUsed)
        assertEquals(listOf("libquickpizza_ndk_crash.so"), collected.libraryNames)
        assertEquals(256, collected.byAbi["arm64-v8a"]!!["arm64-v8a/libquickpizza_ndk_crash.so"]!!.size)

        buildDir.deleteRecursively()
    }
}
