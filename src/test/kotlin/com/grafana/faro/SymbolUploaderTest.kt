package com.grafana.faro

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SymbolUploaderTest {

    @Test
    fun `targetUrl encodes bundle id in the path`() {
        assertEquals(
            "https://collector.example/app/42/symbols/android/com.example.app%4042%401.0.0",
            SymbolUploader.targetUrl("https://collector.example/", "42", "com.example.app@42@1.0.0"),
        )
    }

    @Test
    fun `isLocalEndpoint detects loopback hosts`() {
        assertTrue(SymbolUploader.isLocalEndpoint("http://localhost:8027/app/1/symbols/android/x"))
        assertTrue(SymbolUploader.isLocalEndpoint("http://127.0.0.1:8027/app/1/symbols/android/x"))
        assertFalse(SymbolUploader.isLocalEndpoint("https://collector.grafana.net/app/1/symbols/android/x"))
    }

    @Test
    fun `uploadMapping posts mapping file part only`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()

        val mapping = Files.createTempFile("mapping", ".txt").toFile().apply { writeText("a.b.c -> d:\n") }

        val result = SymbolUploader.uploadMapping(
            UploadConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                appId = "42",
                stackId = "777",
                apiKey = "secret-token",
                bundleId = "com.example.app@12@1.2.3",
                mapping = mapping,
                nativeSymbols = null,
            ),
        )

        assertEquals(201, result.code)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"mapping\""))
        assertFalse(body.contains("name=\"native-symbols\""))

        server.shutdown()
        mapping.delete()
    }

    @Test
    fun `uploadNativeAbi posts abi field and native-symbols zip`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()

        val abiZip = Files.createTempFile("arm64-v8a", ".zip").toFile().apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }

        val result = SymbolUploader.uploadNativeAbi(
            UploadConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                appId = "42",
                stackId = "777",
                apiKey = "secret-token",
                bundleId = "com.example.app@12@1.2.3",
                mapping = null,
                nativeSymbols = abiZip,
            ),
            abiZip,
            "arm64-v8a",
        )

        assertEquals(201, result.code)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"abi\""))
        assertTrue(body.contains("arm64-v8a"))
        assertTrue(body.contains("name=\"native-symbols\""))

        server.shutdown()
        abiZip.delete()
    }

    @Test
    fun `uploadNativeAbi extracts server warning from 201 JSON response`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"warn","message":"Native symbols uploaded, but 1 library was skipped because the decompressed ELF exceeded the per-library size limit: arm64-v8a/libreactnative.so (93 MiB > 64 MiB limit).","skipped_libraries":[{"abi":"arm64-v8a","name":"libreactnative.so","size_bytes":97517568,"limit_bytes":67108864,"reason":"per_library_size_limit"}]}""",
                ),
        )
        server.start()

        val abiZip = Files.createTempFile("arm64-v8a", ".zip").toFile().apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }

        val result = SymbolUploader.uploadNativeAbi(
            UploadConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                appId = "42",
                stackId = "777",
                apiKey = "secret-token",
                bundleId = "com.example.app@12@1.2.3",
                mapping = null,
                nativeSymbols = abiZip,
            ),
            abiZip,
            "arm64-v8a",
        )

        assertEquals(201, result.code)
        assertEquals(
            "Native symbols uploaded, but 1 library was skipped because the decompressed ELF exceeded the per-library size limit: arm64-v8a/libreactnative.so (93 MiB > 64 MiB limit).",
            result.warning,
        )

        server.shutdown()
        abiZip.delete()
    }
}

class NativeSymbolsByAbiPackerTest {

    @Test
    fun `pack splits AGP zip into per-ABI zips`() {
        val agp = Files.createTempFile("native-debug-symbols", ".zip").toFile()
        ZipOutputStream(agp.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("arm64-v8a/libdemo.so"))
            zos.write(ByteArray(128) { 1 })
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("x86_64/libdemo.so.dbg"))
            zos.write(ByteArray(128) { 2 })
            zos.closeEntry()
        }

        val outDir = Files.createTempDirectory("faro-abi-out").toFile()
        val artifacts = NativeSymbolsByAbiPacker.pack(agp, outDir)

        assertEquals(2, artifacts.size)
        assertTrue(artifacts.any { it.abi == "arm64-v8a" && it.bytes > 0 })
        assertTrue(artifacts.any { it.abi == "x86_64" && it.bytes > 0 })

        agp.delete()
        outDir.deleteRecursively()
    }
}
