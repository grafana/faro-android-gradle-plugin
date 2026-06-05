package com.grafana.faro

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Inputs for a single Android symbols upload. */
data class UploadConfig(
    val endpoint: String,
    val appId: String,
    val stackId: String,
    val apiKey: String,
    /** Encoded build identity: `{applicationId}@{versionCode}@{versionName}`. */
    val bundleId: String,
    val mapping: File?,
    val nativeSymbols: File?,
)

data class UploadResult(val code: Int, val body: String)

/**
 * Builds and sends the multipart upload that mirrors `faro-cli android upload` and the
 * `POST /app/{appId}/symbols/android/{bundleId}` endpoint contract:
 *   - path: bundle id (URL-encoded)
 *   - file parts: mapping (text/plain), native-symbols (application/zip)
 *   - auth header: `Authorization: Bearer {stackId}:{apiKey}` (+ `X-Scope-OrgID` for local dev)
 *
 * Kept free of Gradle types so it is unit-testable with MockWebServer.
 */
object SymbolUploader {
    fun targetUrl(endpoint: String, appId: String, bundleId: String): String {
        val encoded = URLEncoder.encode(bundleId, StandardCharsets.UTF_8)
        return "${endpoint.trimEnd('/')}/app/$appId/symbols/android/$encoded"
    }

    /** Local (noop-auth) collectors derive the stack from `X-Scope-OrgID` rather than the token. */
    fun isLocalEndpoint(url: String): Boolean =
        runCatching { URI(url).host }.getOrNull()?.let { host ->
            host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "::1"
        } ?: false

    fun upload(config: UploadConfig, client: OkHttpClient = OkHttpClient()): UploadResult {
        require(config.mapping != null || config.nativeSymbols != null) {
            "Provide at least one of mapping.txt or native-debug-symbols.zip"
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)

        config.mapping?.let {
            multipart.addFormDataPart("mapping", it.name, it.asRequestBody("text/plain".toMediaType()))
        }
        config.nativeSymbols?.let {
            multipart.addFormDataPart("native-symbols", it.name, it.asRequestBody("application/zip".toMediaType()))
        }

        val url = targetUrl(config.endpoint, config.appId, config.bundleId)
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.stackId}:${config.apiKey}")
            .post(multipart.build())

        if (isLocalEndpoint(url)) {
            requestBuilder.header("X-Scope-OrgID", config.stackId)
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            return UploadResult(response.code, response.body?.string().orEmpty())
        }
    }
}
