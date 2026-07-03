package com.grafana.faro

import java.io.File
import java.util.zip.ZipInputStream

/**
 * Builds a per-ABI map of `{abi}/{lib}.so` → unstripped ELF bytes for upload.
 *
 * Merges AGP's native-debug-symbols.zip (when present) with CMake `obj/` trees so custom
 * JNI libraries (e.g. `libquickpizza_ndk_crash.so`) are not dropped when AGP's zip is
 * incomplete or missing.
 */
internal object NativeSymbolsCollector {
    data class CollectResult(
        val byAbi: Map<String, Map<String, ByteArray>>,
        val agpZipUsed: Boolean,
        val cxxObjRootsUsed: List<File>,
        val libraryNames: List<String>,
    ) {
        val isEmpty: Boolean
            get() = byAbi.values.all { it.isEmpty() }
    }

    fun collect(locateResult: NativeSymbolsLocator.LocateResult): CollectResult {
        val byAbi = linkedMapOf<String, LinkedHashMap<String, ByteArray>>()
        var agpZipUsed = false
        val cxxRootsUsed = mutableListOf<File>()

        locateResult.agpNativeSymbolsZip?.let { zip ->
            mergeAgpZip(byAbi, zip)
            agpZipUsed = true
        }

        for (objRoot in locateResult.cxxObjRoots) {
            if (mergeCxxObjRoot(byAbi, objRoot)) {
                cxxRootsUsed.add(objRoot)
            }
        }

        val libraryNames = byAbi.values
            .flatMap { libs -> libs.keys.map { it.substringAfterLast('/') } }
            .distinct()
            .sorted()

        return CollectResult(
            byAbi = byAbi,
            agpZipUsed = agpZipUsed,
            cxxObjRootsUsed = cxxRootsUsed,
            libraryNames = libraryNames,
        )
    }

    private fun mergeAgpZip(byAbi: LinkedHashMap<String, LinkedHashMap<String, ByteArray>>, zip: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val lower = name.lowercase()
                if (!lower.endsWith(".so") && !lower.endsWith(".so.dbg")) {
                    zis.closeEntry()
                    continue
                }
                val abi = NativeSymbolsByAbiPacker.abiFromZipPath(name)
                if (abi == null) {
                    zis.closeEntry()
                    continue
                }
                var libName = name.substringAfterLast('/')
                if (libName.endsWith(".so.dbg")) {
                    libName = libName.removeSuffix(".dbg")
                }
                val zipPath = "$abi/$libName"
                putLib(byAbi, abi, zipPath, zis.readBytes())
                zis.closeEntry()
            }
        }
    }

    private fun mergeCxxObjRoot(byAbi: LinkedHashMap<String, LinkedHashMap<String, ByteArray>>, objRoot: File): Boolean {
        var added = false
        objRoot.listFiles()?.filter { it.isDirectory }?.forEach { abiDir ->
            val abi = abiDir.name
            if (abi !in NativeSymbolsByAbiPacker.ALLOWED_ABIS) {
                return@forEach
            }
            abiDir.listFiles()?.filter { it.isFile && it.name.endsWith(".so") }?.forEach { so ->
                val zipPath = "$abi/${so.name}"
                putLib(byAbi, abi, zipPath, so.readBytes())
                added = true
            }
        }
        return added
    }

    /** Prefer the larger payload for the same `{abi}/{lib}.so` (unstripped > stripped). */
    private fun putLib(
        byAbi: LinkedHashMap<String, LinkedHashMap<String, ByteArray>>,
        abi: String,
        zipPath: String,
        data: ByteArray,
    ) {
        val libs = byAbi.getOrPut(abi) { linkedMapOf() }
        val existing = libs[zipPath]
        if (existing == null || existing.size < data.size) {
            libs[zipPath] = data
        }
    }
}
