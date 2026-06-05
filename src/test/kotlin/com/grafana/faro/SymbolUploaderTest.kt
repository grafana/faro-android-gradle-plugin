package com.grafana.faro

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

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
    fun `upload posts multipart file parts to bundle id path with auth header`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()

        val mapping = Files.createTempFile("mapping", ".txt").toFile().apply { writeText("a.b.c -> d:\n") }
        val nativeSymbols = Files.createTempFile("native-debug-symbols", ".zip").toFile().apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        }

        val result = SymbolUploader.upload(
            UploadConfig(
                endpoint = server.url("/").toString().trimEnd('/'),
                appId = "42",
                stackId = "777",
                apiKey = "secret-token",
                bundleId = "com.example.app@12@1.2.3",
                mapping = mapping,
                nativeSymbols = nativeSymbols,
            ),
            client = OkHttpClient(),
        )

        assertEquals(201, result.code)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/app/42/symbols/android/com.example.app%4012%401.2.3", request.path)
        assertEquals("Bearer 777:secret-token", request.getHeader("Authorization"))
        assertEquals("777", request.getHeader("X-Scope-OrgID"))

        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"mapping\""))
        assertTrue(body.contains("name=\"native-symbols\""))
        assertFalse(body.contains("name=\"applicationId\""))

        server.shutdown()
        cleanup(mapping, nativeSymbols)
    }

    private fun cleanup(vararg files: File) {
        files.forEach { it.delete() }
    }
}
