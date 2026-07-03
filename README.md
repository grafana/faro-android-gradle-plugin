# Faro Android Gradle Plugin

`com.grafana.faro.android-symbols` — automatically uploads Android symbolication artifacts to Grafana
Frontend/Application Observability after a **release** build, so obfuscated Android crash
stacks are retraced at ingest time.

On **release** builds only (not debug), it uploads:

- **`mapping.txt`** (R8/ProGuard) — de-obfuscates Java/Kotlin frames.
- **`native-debug-symbols.zip`** (NDK) — symbolicates native `.so` crash frames. When AGP does
  not emit that zip (common in React Native / CMake apps), the plugin packs unstripped `.so`
  files from `intermediates/cxx/RelWithDebInfo/*/obj/{abi}/` and uploads one zip per ABI.

The plugin reads the build identity (`applicationId` / `versionCode` / `versionName`) and the
artifact output paths straight from the Android Gradle Plugin variant API, and uploads **in-JVM**
(OkHttp) — no Node, no `faro-cli`, no manual arguments. It targets the same endpoint contract as
`faro-cli android upload`:

```
POST {endpoint}/app/{appId}/symbols/android/{bundleId}
Authorization: Bearer {stackId}:{apiKey}
multipart: mapping, native-symbols

bundleId = {applicationId}@{versionCode}@{versionName} (URL-encoded in the path)
```

The same **bundle id** is written for **Hermes JS source maps** (via `@grafana/faro-metro-plugin` on the React Native bundle task) and for **Android symbols** above. You do **not** set `FARO_BUNDLE_ID` in CI.

### Unified bundle id (Metro + symbols)

The plugin only hooks **release** build types (`variant.buildType == "release"`). Debug builds are ignored.

For a plain `release` variant (no product flavors), Gradle runs:

1. **`faroWriteBundleIdRelease`** before `createBundleReleaseJsAndAssets`, writing  
   `app/build/faro/bundle-id-release.txt`.
2. Injects **`FARO_BUNDLE_ID`** into that bundle task’s environment (Gradle → Metro only; not for CI).
3. **`faroUploadSymbolsRelease`** after `assembleRelease` / `bundleRelease` / `installRelease` (e.g. `yarn android --mode=release`).

If you use **product flavors** with a release build type (e.g. `freeRelease`, `prodRelease`), the same three steps run per flavor variant name — each with its own `applicationId` / version and upload task.

Ship from the `android/` directory:

```bash
./gradlew assembleRelease
```

Standalone JS bundle (release id only — `@grafana/faro-metro-plugin` runs `faroWriteBundleIdRelease` when needed):

```bash
npx react-native bundle --platform android --dev false ...
```

## Usage

### 1. Apply the plugin

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("com.grafana.faro.android-symbols") version "<version>"
}
```

Groovy (`app/build.gradle`):

```groovy
plugins {
    id "com.android.application"
    id "com.grafana.faro.android-symbols" version "<version>"
}
```

### 2. Configure it

```kotlin
faro {
    endpoint = "https://faro-collector-prod-<region>.grafana.net/collect"
    appId    = "123"
    stackId  = "456"
    apiKey   = System.getenv("FARO_SOURCEMAP_API_KEY") // never hardcode the key
    // enabled = true // default; set false to disable uploads
}
```

> **Security:** keep `apiKey` out of the build script — source it from an env var (CI secret) or
> `~/.gradle/gradle.properties`. It is a build-time upload token and must never ship in the app.

### 3. Build a release

```bash
./gradlew assembleRelease   # or bundleRelease
```

The `faroUploadSymbols<Variant>` task runs automatically afterwards and uploads whatever
artifacts the build produced.

- Missing **config** (endpoint, appId, stackId, apiKey) → the task **warns and skips** (never fails the build), so contributors without credentials are unaffected.
- Missing **mapping.txt** on a Java-only app → uploads native symbols only (if any).
- **Release ships native `.so` libraries** (React Native, NDK, CMake) but no unstripped symbols were produced → logs a **red `[Faro]` error** with configuration guidance; the build continues (mapping upload still runs). Does not print “symbol upload complete” for native.
- **Native libraries built** (CMake `RelWithDebInfo` obj dirs) but none collected or uploaded → same red error; build is not failed.

To enable native symbols, make sure your release build emits them:

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            ndk { debugSymbolLevel = "FULL" }
        }
    }
}
```

## Local development against an unpublished build

Publish to your local Maven and resolve from there:

```bash
# in this repo
gradle publishToMavenLocal -Pversion=0.1.0
```

Add `mavenLocal()` to the consumer's `settings.gradle(.kts)` `pluginManagement { repositories { } }`,
then apply `id("com.grafana.faro.android-symbols") version "0.1.0"`.

## Build & test

```bash
gradle build      # compile + unit tests (or ./gradlew after `gradle wrapper`)
```

## Publishing

Releases publish to the [Gradle Plugin Portal](https://plugins.gradle.org) via
`.github/workflows/publish.yml` on a `v*` tag. Requires repo secrets `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET`.

## License

Apache-2.0 — see [LICENSE](LICENSE).
