package com.grafana.faro

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes `{applicationId}@{versionCode}@{versionName}` for Metro / Faro source map upload.
 * Consumed by [@grafana/faro-metro-plugin] and injected on RN bundle tasks via [FARO_BUNDLE_ID].
 */
@CacheableTask
abstract class WriteFaroBundleIdTask : DefaultTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val versionCode: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:OutputFile
    abstract val bundleIdFile: RegularFileProperty

    init {
        group = "faro"
        description = "Writes the unified Faro bundle id for this Android variant (Metro + symbols)."
    }

    @TaskAction
    fun write() {
        val bundleId = FaroBundleId.format(
            applicationId.get(),
            versionCode.get(),
            versionName.get(),
        )
        check(FaroBundleId.validate(bundleId)) {
            "Invalid Faro bundle id $bundleId for variant ${variantName.get()}"
        }
        val file = bundleIdFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(bundleId)
        FaroLog.lifecycle(logger, project, "bundle id for ${variantName.get()}: $bundleId")
    }
}
