package com.grafana.faro

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Registers the `faro { }` extension and, for each configured release variant of an
 * `com.android.application` project, wires:
 * - [WriteFaroBundleIdTask] before the React Native JS bundle task (unified Metro bundle id)
 * - [UploadAndroidSymbolsTask] after `assemble<Variant>` / `bundle<Variant>` / `install<Variant>`
 */
class FaroPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("faro", FaroExtension::class.java).apply {
            enabled.convention(true)
        }

        project.plugins.withId("com.android.application") {
            val androidComponents =
                project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            androidComponents.onVariants { variant ->
                // Shipped-artifact symbolication: release build type only (not debug/staging).
                if (variant.buildType != "release") {
                    return@onVariants
                }

                val cap = variant.name.replaceFirstChar { it.uppercase() }
                val output = variant.outputs.firstOrNull()

                val variantVersionCode = output?.versionCode?.map { it?.toString() ?: "" }?.orElse("")
                    ?: project.provider { "" }
                val variantVersionName = output?.versionName?.map { it ?: "" }?.orElse("")
                    ?: project.provider { "" }

                val writeBundleIdTask = project.tasks.register(
                    "faroWriteBundleId$cap",
                    WriteFaroBundleIdTask::class.java,
                    Action {
                        applicationId.set(variant.applicationId)
                        versionCode.set(variantVersionCode)
                        versionName.set(variantVersionName)
                        variantName.set(variant.name)
                        bundleIdFile.set(
                            project.layout.buildDirectory.file("faro/bundle-id-${variant.name}.txt"),
                        )
                    },
                )

                val uploadTask = project.tasks.register(
                    "faroUploadSymbols$cap",
                    UploadAndroidSymbolsTask::class.java,
                    Action {
                        endpoint.set(ext.endpoint)
                        appId.set(ext.appId)
                        stackId.set(ext.stackId)
                        apiKey.set(ext.apiKey)
                        uploadEnabled.set(ext.enabled)
                        applicationId.set(variant.applicationId)
                        versionCode.set(variantVersionCode)
                        versionName.set(variantVersionName)
                        mappingFile.set(
                            project.layout.buildDirectory.file("outputs/mapping/${variant.name}/mapping.txt"),
                        )
                        nativeSymbolsFile.set(
                            project.layout.buildDirectory.file(
                                "outputs/native-debug-symbols/${variant.name}/native-debug-symbols.zip",
                            ),
                        )
                    },
                )

                wireReactNativeBundleTask(project, cap, writeBundleIdTask)

                listOf("assemble$cap", "bundle$cap", "install$cap").forEach { name ->
                    project.tasks.matching { it.name == name }.configureEach(Action {
                        finalizedBy(uploadTask)
                    })
                }
            }
        }
    }

    private fun wireReactNativeBundleTask(
        project: Project,
        variantCap: String,
        writeBundleIdTask: TaskProvider<WriteFaroBundleIdTask>,
    ) {
        val candidates = listOf(
            "createBundle${variantCap}JsAndAssets",
            "bundle${variantCap}JsAndAssets",
        )
        for (name in candidates) {
            project.tasks.matching { it.name == name }.configureEach(Action {
                dependsOn(writeBundleIdTask)
            })
        }
    }
}
