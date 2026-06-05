package com.grafana.faro

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Uploads the R8 `mapping.txt` and/or `native-debug-symbols.zip` for one release variant.
 *
 * No task outputs are declared, so it always runs after the build it is wired to (uploads
 * are not cacheable). File inputs are [Internal] and existence-checked at execution time so a
 * variant that produced only one (or neither) artifact does not fail the build.
 */
@DisableCachingByDefault(because = "Uploads symbol artifacts to Grafana over the network.")
abstract class UploadAndroidSymbolsTask : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val endpoint: Property<String>

    @get:Input
    @get:Optional
    abstract val appId: Property<String>

    @get:Input
    @get:Optional
    abstract val stackId: Property<String>

    @get:Input
    @get:Optional
    abstract val apiKey: Property<String>

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val uploadEnabled: Property<Boolean>

    @get:Internal
    abstract val mappingFile: RegularFileProperty

    @get:Internal
    abstract val nativeSymbolsFile: RegularFileProperty

    init {
        group = "faro"
        description = "Uploads Android symbols (mapping.txt / native-debug-symbols.zip) to Grafana."
    }

    @TaskAction
    fun upload() {
        if (!uploadEnabled.getOrElse(true)) {
            FaroLog.lifecycle(logger, project, "upload disabled (faro.enabled = false); skipping.")
            return
        }

        val missing = buildList {
            if (endpoint.orNull.isNullOrBlank()) add("endpoint")
            if (appId.orNull.isNullOrBlank()) add("appId")
            if (stackId.orNull.isNullOrBlank()) add("stackId")
            if (apiKey.orNull.isNullOrBlank()) add("apiKey")
        }
        if (missing.isNotEmpty()) {
            // Don't fail the build for a missing key (e.g. local/contributor builds) — warn and skip.
            FaroLog.warn(logger, "skipping symbol upload; missing config: ${missing.joinToString(", ")}")
            return
        }

        val mapping = mappingFile.orNull?.asFile?.takeIf { it.exists() }
        val nativeSymbols = nativeSymbolsFile.orNull?.asFile?.takeIf { it.exists() }
        if (mapping == null && nativeSymbols == null) {
            FaroLog.warn(logger, "no mapping.txt or native-debug-symbols.zip found for this build; nothing to upload.")
            return
        }

        val identity = FaroBundleId.format(
            applicationId.get(),
            versionCode.get(),
            versionName.get(),
        )
        val artifacts = listOfNotNull(mapping?.let { "mapping" }, nativeSymbols?.let { "native-symbols" })
        FaroLog.lifecycle(logger, project, "uploading Android symbols ($artifacts) for $identity")

        val result = SymbolUploader.upload(
            UploadConfig(
                endpoint = endpoint.get(),
                appId = appId.get(),
                stackId = stackId.get(),
                apiKey = apiKey.get(),
                bundleId = identity,
                mapping = mapping,
                nativeSymbols = nativeSymbols,
            ),
        )

        if (result.code !in 200..299) {
            throw GradleException("${FaroLog.PREFIX}symbol upload failed (HTTP ${result.code}): ${result.body}")
        }
        FaroLog.lifecycle(logger, project, "symbol upload complete (HTTP ${result.code}).")
    }
}
