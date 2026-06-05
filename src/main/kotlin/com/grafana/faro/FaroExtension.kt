package com.grafana.faro

import org.gradle.api.provider.Property

/**
 * `faro { }` configuration block exposed to the consumer build.
 *
 * Secrets (notably [apiKey]) should come from the environment or `~/.gradle/gradle.properties`,
 * never a literal committed to the build script.
 */
abstract class FaroExtension {
    /** Collector base URL, e.g. `https://faro-collector-prod-<region>.grafana.net/collect`. */
    abstract val endpoint: Property<String>

    /** Frontend/App Observability app id (the `{appId}` in the upload path). */
    abstract val appId: Property<String>

    /** Grafana Cloud stack id (numeric). Sent as `Authorization: Bearer {stackId}:{apiKey}`. */
    abstract val stackId: Property<String>

    /** Upload API key/token. Prefer an env var (e.g. `FARO_SOURCEMAP_API_KEY`). */
    abstract val apiKey: Property<String>

    /** Master switch. When false, the upload task is registered but skips. Defaults to true. */
    abstract val enabled: Property<Boolean>
}
