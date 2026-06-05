package com.grafana.faro

/**
 * Canonical Android / RN release bundle id for JS source maps and R8 symbol upload.
 * Format: `{applicationId}@{versionCode}@{versionName}` (matches KWL ingest + MySQL).
 */
object FaroBundleId {
    private const val SEPARATOR = "@"

    fun format(applicationId: String, versionCode: String, versionName: String): String =
        "$applicationId$SEPARATOR$versionCode$SEPARATOR$versionName"

    /**
     * Validates the encoded triple used for Android symbol retrace and unified RN release ids.
     */
    fun validate(bundleId: String): Boolean {
        val parts = bundleId.split(SEPARATOR)
        if (parts.size != 3) {
            return false
        }
        if (parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return false
        }
        return parts[1].all { it.isDigit() }
    }
}
