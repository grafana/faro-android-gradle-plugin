package com.grafana.faro

import java.io.File
import java.util.zip.ZipInputStream

data class ShippedNativeLibrary(
    val abi: String,
    val fileName: String,
)

/**
 * Locates unstripped native libraries for a release variant.
 *
 * AGP may emit native-debug-symbols.zip under outputs/native-debug-symbols/, but React Native /
 * CMake projects often skip that zip while still writing unstripped .so files under
 * intermediates/cxx/RelWithDebInfo/.../obj/{abi}/.
 */
internal object NativeSymbolsLocator {
    data class LocateResult(
        val agpNativeSymbolsZip: File?,
        val cxxObjRoots: List<File>,
        val shippedNativeLibraries: List<ShippedNativeLibrary>,
        val hasReleaseCxxBuild: Boolean,
    ) {
        val hasCxxNativeLibs: Boolean
            get() = cxxObjRoots.any { root ->
                root.isDirectory && root.listFiles()?.any { abiDir ->
                    abiDir.isDirectory && abiDir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true
                } == true
            }

        val shipsNativeLibraries: Boolean
            get() = shippedNativeLibraries.isNotEmpty()
    }

    fun locate(buildDir: File, variant: String): LocateResult {
        val agpZip = File(buildDir, "outputs/native-debug-symbols/$variant/native-debug-symbols.zip")
            .takeIf { it.isFile }

        val cxxBase = File(buildDir, "intermediates/cxx")
        val objRoots = linkedSetOf<File>()
        var hasReleaseCxxBuild = false
        if (cxxBase.isDirectory) {
            cxxBase.walkTopDown()
                .maxDepth(6)
                .forEach { dir ->
                    if (dir.isDirectory && dir.name == "RelWithDebInfo") {
                        hasReleaseCxxBuild = true
                    }
                    if (dir.isDirectory && dir.name == "obj" && isReleaseCxxObjRoot(dir)) {
                        objRoots.add(dir)
                    }
                }
        }

        return LocateResult(
            agpNativeSymbolsZip = agpZip,
            cxxObjRoots = objRoots.sortedBy { it.absolutePath },
            shippedNativeLibraries = findShippedNativeLibraries(buildDir, variant),
            hasReleaseCxxBuild = hasReleaseCxxBuild,
        )
    }

    /** Release CMake outputs use RelWithDebInfo; ignore Debug cxx obj trees. */
    private fun isReleaseCxxObjRoot(objDir: File): Boolean {
        val path = objDir.invariantSeparatorsPath
        return path.contains("/RelWithDebInfo/") && path.endsWith("/obj")
    }

    private fun findShippedNativeLibraries(buildDir: File, variant: String): List<ShippedNativeLibrary> {
        val found = linkedMapOf<Pair<String, String>, ShippedNativeLibrary>()
        val variantMarkers = variantNameMarkers(variant)

        listOf("stripped_native_libs", "merged_native_libs").forEach { segment ->
            val root = File(buildDir, "intermediates/$segment")
            if (!root.isDirectory) {
                return@forEach
            }
            root.walkTopDown()
                .maxDepth(8)
                .filter { it.isFile && it.name.endsWith(".so") }
                .forEach { soFile ->
                    val path = soFile.invariantSeparatorsPath
                    if (!path.contains("/out/lib/")) {
                        return@forEach
                    }
                    if (!pathMatchesVariant(path, variantMarkers)) {
                        return@forEach
                    }
                    val abi = soFile.parentFile.name
                    if (abi !in NativeSymbolsByAbiPacker.ALLOWED_ABIS) {
                        return@forEach
                    }
                    found.putIfAbsent(abi to soFile.name, ShippedNativeLibrary(abi, soFile.name))
                }
        }

        if (found.isEmpty()) {
            findShippedNativeLibrariesInApk(buildDir, variant, found)
        }

        return found.values.sortedWith(compareBy({ it.abi }, { it.fileName }))
    }

    private fun findShippedNativeLibrariesInApk(
        buildDir: File,
        variant: String,
        found: LinkedHashMap<Pair<String, String>, ShippedNativeLibrary>,
    ) {
        val apkDir = File(buildDir, "outputs/apk/$variant")
        if (!apkDir.isDirectory) {
            return
        }
        apkDir.listFiles()
            ?.filter { it.isFile && it.extension == "apk" }
            ?.forEach { apk -> mergeApkNativeLibraries(apk, found) }
    }

    private fun mergeApkNativeLibraries(
        apk: File,
        found: LinkedHashMap<Pair<String, String>, ShippedNativeLibrary>,
    ) {
        ZipInputStream(apk.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                if (!name.startsWith("lib/") || !name.endsWith(".so")) {
                    zis.closeEntry()
                    continue
                }
                val parts = name.split('/').filter { it.isNotEmpty() }
                if (parts.size != 3) {
                    zis.closeEntry()
                    continue
                }
                val abi = parts[1]
                val fileName = parts[2]
                if (abi !in NativeSymbolsByAbiPacker.ALLOWED_ABIS) {
                    zis.closeEntry()
                    continue
                }
                found.putIfAbsent(abi to fileName, ShippedNativeLibrary(abi, fileName))
                zis.closeEntry()
            }
        }
    }

    private fun variantNameMarkers(variant: String): List<String> {
        val lower = variant.lowercase()
        val capitalized = variant.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
        return listOf(lower, capitalized).distinct()
    }

    private fun pathMatchesVariant(path: String, variantMarkers: List<String>): Boolean {
        val normalized = path.lowercase()
        return variantMarkers.any { marker -> normalized.contains(marker.lowercase()) }
    }
}
