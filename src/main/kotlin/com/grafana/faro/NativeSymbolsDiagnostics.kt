package com.grafana.faro

/**
 * Actionable guidance when a release ships native code but no unstripped symbols can be uploaded.
 */
internal object NativeSymbolsDiagnostics {

    fun missingSymbolsMessage(
        locateResult: NativeSymbolsLocator.LocateResult,
        collected: NativeSymbolsCollector.CollectResult?,
    ): String {
        val shippedSample = locateResult.shippedNativeLibraries
            .take(4)
            .joinToString { "${it.abi}/${it.fileName}" }
        val shippedCount = locateResult.shippedNativeLibraries.size
        val agpPath = locateResult.agpNativeSymbolsZip?.absolutePath ?: "(not found)"
        val cxxStatus = when {
            locateResult.hasCxxNativeLibs -> "obj dirs contain .so files but collection failed"
            locateResult.hasReleaseCxxBuild -> "RelWithDebInfo tree exists but obj dirs have no .so files"
            else -> "no RelWithDebInfo cxx output found"
        }

        return buildString {
            appendLine("native symbols were not uploaded — native crashes will not be symbolicated.")
            appendLine()
            if (shippedCount > 0) {
                appendLine(
                    "Release packages $shippedCount native .so file(s) " +
                        "(e.g. $shippedSample) but no unstripped symbols were found to upload.",
                )
            } else if (locateResult.hasReleaseCxxBuild) {
                appendLine("A native (CMake/NDK) release build ran but no unstripped .so files were found.")
            }
            appendLine()
            appendLine("Fix: enable full native debug symbols on the release build type:")
            appendLine("  android {")
            appendLine("      buildTypes {")
            appendLine("          release {")
            appendLine("              ndk { debugSymbolLevel 'FULL' }  // or SYMBOL_TABLE minimum")
            appendLine("          }")
            appendLine("      }")
            appendLine("  }")
            appendLine()
            appendLine("Then run a clean release build, e.g. ./gradlew clean assembleRelease")
            appendLine()
            appendLine("Expected artifact (at least one):")
            appendLine("  - app/build/intermediates/cxx/RelWithDebInfo/.../obj/{abi}/*.so")
            appendLine("  - app/build/outputs/native-debug-symbols/{variant}/native-debug-symbols.zip")
            appendLine()
            appendLine("Diagnostics:")
            appendLine("  - AGP native-debug-symbols.zip: $agpPath")
            appendLine("  - CMake cxx: $cxxStatus")
            if (collected != null && collected.agpZipUsed && collected.isEmpty) {
                appendLine("  - AGP zip was present but contained no uploadable .so entries")
            }
        }.trimEnd()
    }
}
