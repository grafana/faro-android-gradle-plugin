package com.grafana.faro

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
    private val allowedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "riscv64")

    fun pack(agpZip: File, outputDir: File): List<AbiZipArtifact> {
        require(agpZip.isFile) { "native symbols file not found: ${agpZip.absolutePath}" }
        outputDir.mkdirs()

        val byAbi = linkedMapOf<String, LinkedHashMap<String, ByteArray>>()
        ZipInputStream(agpZip.inputStream().buffered()).use { zis ->
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
                val abi = abiFromZipPath(name) ?: run {
                    zis.closeEntry()
                    continue
                }
                var libName = name.substringAfterLast('/')
                if (libName.endsWith(".so.dbg")) {
                    libName = libName.removeSuffix(".dbg")
                }
                val zipPath = "$abi/$libName"
                val data = zis.readBytes()
                zis.closeEntry()
                byAbi.getOrPut(abi) { linkedMapOf() }[zipPath] = data
            }
        }

        if (byAbi.isEmpty()) {
            throw IllegalArgumentException("no native .so entries found in ${agpZip.absolutePath}")
        }

        return allowedAbis.mapNotNull { abi ->
            val libs = byAbi[abi] ?: return@mapNotNull null
            val outFile = File(outputDir, "$abi.zip")
            writeAbiZip(outFile, libs)
            AbiZipArtifact(abi, outFile, outFile.length())
        }
    }

    private fun abiFromZipPath(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var candidate = parts[parts.size - 2]
        if (candidate == "obj" && parts.size >= 3) {
            candidate = parts[parts.size - 3]
        }
        return candidate.takeIf { it in allowedAbis }
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
