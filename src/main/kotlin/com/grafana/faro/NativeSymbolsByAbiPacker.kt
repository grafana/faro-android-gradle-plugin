package com.grafana.faro

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class AbiZipArtifact(
    val abi: String,
    val zipFile: File,
    val bytes: Long,
)

/**
 * Splits AGP native-debug-symbols.zip into one Deflate-compressed zip per ABI.
 * Normalizes `.so.dbg` entries to `.so` paths expected by the ingest parser.
 */
object NativeSymbolsByAbiPacker {
    val ALLOWED_ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "riscv64")

    /** Splits a single AGP zip into per-ABI zips. */
    fun pack(agpZip: File, outputDir: File): List<AbiZipArtifact> {
        require(agpZip.isFile) { "native symbols file not found: ${agpZip.absolutePath}" }
        val collected = NativeSymbolsCollector.collect(
            NativeSymbolsLocator.LocateResult(
                agpNativeSymbolsZip = agpZip,
                cxxObjRoots = emptyList(),
                shippedNativeLibraries = emptyList(),
                hasReleaseCxxBuild = false,
            ),
        )
        return packCollected(collected.byAbi, outputDir)
    }

    fun packCollected(byAbi: Map<String, Map<String, ByteArray>>, outputDir: File): List<AbiZipArtifact> {
        outputDir.mkdirs()
        if (byAbi.values.all { it.isEmpty() }) {
            throw IllegalArgumentException("no native .so libraries collected for upload")
        }

        return ALLOWED_ABIS.mapNotNull { abi ->
            val libs = byAbi[abi] ?: return@mapNotNull null
            if (libs.isEmpty()) {
                return@mapNotNull null
            }
            val outFile = File(outputDir, "$abi.zip")
            writeAbiZip(outFile, libs)
            AbiZipArtifact(abi, outFile, outFile.length())
        }
    }

    internal fun abiFromZipPath(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var candidate = parts[parts.size - 2]
        if (candidate == "obj" && parts.size >= 3) {
            candidate = parts[parts.size - 3]
        }
        return candidate.takeIf { it in ALLOWED_ABIS }
    }

    private fun writeAbiZip(outFile: File, libs: Map<String, ByteArray>) {
        ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
            zos.setLevel(Deflater.DEFAULT_COMPRESSION)
            for ((path, data) in libs.entries.sortedBy { it.key }) {
                val entry = ZipEntry(path)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
    }
}
