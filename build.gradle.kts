plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "com.grafana.faro"
// CI passes -Pversion=<tag without leading v>; defaults to a dev version locally.
version = (providers.gradleProperty("version").orNull ?: "0.1.0").removePrefix("v")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // AGP variant API — provided by the consumer's Android build, so compileOnly.
    compileOnly("com.android.tools.build:gradle-api:9.2.1")
    // In-JVM multipart uploader (no Node / faro-cli dependency in the consumer build).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website.set("https://github.com/grafana/faro-android-gradle-plugin")
    vcsUrl.set("https://github.com/grafana/faro-android-gradle-plugin.git")
    plugins {
        create("faroAndroidSymbols") {
            id = "com.grafana.faro.android-symbols"
            implementationClass = "com.grafana.faro.FaroPlugin"
            displayName = "Grafana Faro Android symbols"
            description =
                "Automatically uploads R8/ProGuard mapping.txt and native-debug-symbols.zip to Grafana " +
                "Frontend/Application Observability after release builds, so obfuscated Android crash stacks are retraced."
            tags.set(listOf("grafana", "faro", "android", "symbols", "proguard", "r8", "crash", "observability", "rum"))
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
