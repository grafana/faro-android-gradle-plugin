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
import java.io.File

/**
 * Uploads the R8 `mapping.txt` and/or per-ABI native symbol zips for one release variant.
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

    @get:Input
    abstract val variantName: Property<String>

    @get:Internal
    abstract val mappingFile: RegularFileProperty

    init {
        group = "faro"
        description = "Uploads Android symbols (mapping.txt and native .so libraries) to Grafana."
    }

    @TaskAction
    fun upload() {
        if (!uploadEnabled.getOrElse(true)) {
            FaroLog.info(logger, project, "upload disabled (faro.enabled = false); skipping.")
            return
        }

        val missing = buildList {
            if (endpoint.orNull.isNullOrBlank()) add("endpoint")
            if (appId.orNull.isNullOrBlank()) add("appId")
            if (stackId.orNull.isNullOrBlank()) add("stackId")
            if (apiKey.orNull.isNullOrBlank()) add("apiKey")
        }
        if (missing.isNotEmpty()) {
            FaroLog.warn(logger, "skipping symbol upload; missing config: ${missing.joinToString(", ")}")
            return
        }

        val mapping = mappingFile.orNull?.asFile?.takeIf { it.exists() }
        val buildDir = project.layout.buildDirectory.get().asFile
        val variant = variantName.get()
        val locateResult = NativeSymbolsLocator.locate(buildDir, variant)
        val nativeUploadExpected = isNativeUploadExpected(locateResult)

        val collected = if (nativeUploadExpected || locateResult.agpNativeSymbolsZip != null) {
            NativeSymbolsCollector.collect(locateResult)
        } else {
            null
        }

        if (mapping == null && !nativeUploadExpected && (collected == null || collected.isEmpty)) {
            FaroLog.warn(logger, "no mapping.txt or native .so libraries found for this build; nothing to upload.")
            return
        }

        val identity = FaroBundleId.format(
            applicationId.get(),
            versionCode.get(),
            versionName.get(),
        )

        val baseConfig = UploadConfig(
            endpoint = endpoint.get(),
            appId = appId.get(),
            stackId = stackId.get(),
            apiKey = apiKey.get(),
            bundleId = identity,
            mapping = null,
            nativeSymbols = null,
        )

        var mappingUploaded = false
        if (mapping != null) {
            FaroLog.info(
                logger,
                project,
                "uploading R8 mapping.txt (${mapping.length()} bytes) for $identity",
            )
            val result = SymbolUploader.uploadMapping(baseConfig.copy(mapping = mapping))
            if (result.code !in 200..299) {
                throw GradleException(
                    "${FaroLog.PREFIX}R8 mapping.txt upload failed (HTTP ${result.code}): ${result.body}",
                )
            }
            FaroLog.success(
                logger,
                project,
                "R8 mapping.txt upload complete for $identity (HTTP ${result.code})",
            )
            mappingUploaded = true
        }

        var nativeAbisUploaded = emptyList<String>()
        var nativeUploadSkippedReason: String? = null

        if (collected != null && !collected.isEmpty) {
            logNativeCollection(collected, locateResult)
            val tempDir = File(project.buildDir, "faro-native-abi-zips").apply {
                deleteRecursively()
                mkdirs()
            }
            val abiArtifacts = NativeSymbolsByAbiPacker.packCollected(collected.byAbi, tempDir)
            nativeAbisUploaded = abiArtifacts.map { it.abi }
            for (artifact in abiArtifacts) {
                FaroLog.info(
                    logger,
                    project,
                    "uploading native-symbols (${artifact.abi}, ${artifact.bytes} bytes)",
                )
                val result = SymbolUploader.uploadNativeAbi(baseConfig, artifact.zipFile, artifact.abi)
                if (result.code !in 200..299) {
                    throw GradleException(
                        "${FaroLog.PREFIX}native-symbols upload failed for ${artifact.abi} " +
                            "(HTTP ${result.code}): ${result.body}",
                    )
                }
                FaroLog.info(
                    logger,
                    project,
                    "uploaded native-symbols (${artifact.abi}, ${artifact.bytes} bytes, HTTP ${result.code})",
                )
            }
        } else if (locateResult.agpNativeSymbolsZip != null) {
            nativeUploadSkippedReason = NativeSymbolsDiagnostics.missingSymbolsMessage(locateResult, collected)
        } else if (nativeUploadExpected) {
            nativeUploadSkippedReason = NativeSymbolsDiagnostics.missingSymbolsMessage(locateResult, collected)
        }

        if (!mappingUploaded && nativeAbisUploaded.isEmpty()) {
            if (nativeUploadSkippedReason != null) {
                FaroLog.error(logger, project, nativeUploadSkippedReason)
            }
            FaroLog.warn(logger, "no symbol artifacts were uploaded for $identity.")
            return
        }

        if (nativeUploadSkippedReason != null) {
            FaroLog.error(logger, project, nativeUploadSkippedReason)
            FaroLog.info(
                logger,
                project,
                "symbol upload finished for $identity (native symbols not uploaded).",
            )
            return
        }

        if (nativeAbisUploaded.isNotEmpty()) {
            FaroLog.success(
                logger,
                project,
                "native symbols upload complete for $identity (${nativeAbisUploaded.joinToString()}).",
            )
        }
    }

    private fun isNativeUploadExpected(locateResult: NativeSymbolsLocator.LocateResult): Boolean =
        locateResult.shipsNativeLibraries ||
            locateResult.hasCxxNativeLibs ||
            locateResult.hasReleaseCxxBuild

    private fun logNativeCollection(
        collected: NativeSymbolsCollector.CollectResult,
        locateResult: NativeSymbolsLocator.LocateResult,
    ) {
        val agpStatus = when {
            collected.agpZipUsed -> "present"
            locateResult.agpNativeSymbolsZip != null -> "empty"
            else -> "absent"
        }
        val cxxRoots = collected.cxxObjRootsUsed.joinToString { it.relativeTo(project.projectDir).path }
        FaroLog.info(
            logger,
            project,
            "collecting native symbols (agp zip=$agpStatus, cxx obj roots=${cxxRoots.ifEmpty { "none" }})",
        )
        FaroLog.info(
            logger,
            project,
            "collected native libraries: ${collected.libraryNames.joinToString()}",
        )
    }
}
