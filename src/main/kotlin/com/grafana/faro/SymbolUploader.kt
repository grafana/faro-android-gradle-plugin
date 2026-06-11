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

/** Inputs for a single Android symbols upload POST. */
data class UploadConfig(
    val endpoint: String,
    val appId: String,
    val stackId: String,
    val apiKey: String,
    /** Encoded build identity: `{applicationId}@{versionCode}@{versionName}`. */
    val bundleId: String,
    val mapping: File?,
    val nativeSymbols: File?,
    val nativeAbi: String? = null,
)

data class UploadResult(val code: Int, val body: String)

/**
 * Builds and sends multipart uploads that mirror `faro-cli android upload` and the
 * `POST /app/{appId}/symbols/android/{bundleId}` endpoint contract.
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
        if (config.nativeSymbols != null) {
            require(!config.nativeAbi.isNullOrBlank()) { "nativeAbi is required when nativeSymbols is set" }
        }

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)

        config.mapping?.let {
            multipart.addFormDataPart("mapping", it.name, it.asRequestBody("text/plain".toMediaType()))
        }
        config.nativeSymbols?.let { zip ->
            multipart.addFormDataPart("abi", config.nativeAbi!!)
            multipart.addFormDataPart("native-symbols", zip.name, zip.asRequestBody("application/zip".toMediaType()))
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

    fun uploadMapping(config: UploadConfig, client: OkHttpClient = OkHttpClient()): UploadResult =
        upload(config.copy(nativeSymbols = null, nativeAbi = null), client)

    fun uploadNativeAbi(
        config: UploadConfig,
        abiZip: File,
        abi: String,
        client: OkHttpClient = OkHttpClient(),
    ): UploadResult = upload(config.copy(mapping = null, nativeSymbols = abiZip, nativeAbi = abi), client)
}
