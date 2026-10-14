package com.grafana.faro

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Builds a release app on the minimum supported AGP and Gradle (README > Requirements) and
 * checks that the plugin uploads its mapping.txt.
 */
class MinimumAgpCompatibilityTest {

    @TempDir
    lateinit var projectDir: File

    private val server = MockWebServer()

    @BeforeEach
    fun startServer() {
        server.enqueue(MockResponse().setResponseCode(201))
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `release build on minimum AGP uploads mapping`() {
        checkNotNull(System.getenv("ANDROID_HOME")) {
            "functionalTest needs an Android SDK; set ANDROID_HOME"
        }
        writeSampleApp()

        val result = GradleRunner.create()
            .withProjectDir(projectDir)
            .withGradleVersion(property("faro.minGradleVersion"))
            .withArguments("assembleRelease", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":faroWriteBundleIdRelease")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":faroUploadSymbolsRelease")?.outcome)
        assertEquals(
            "com.example.faro@7@1.2.3",
            projectDir.resolve("build/faro/bundle-id-release.txt").readText(),
        )

        val request = server.takeRequest(10, TimeUnit.SECONDS)
        assertNotNull(request, "plugin did not upload anything")
        assertEquals("POST", request!!.method)
        assertEquals("/collect/app/42/symbols/android/com.example.faro%407%401.2.3", request.path)
        assertEquals("Bearer 777:test-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("name=\"mapping\""))
    }

    private fun writeSampleApp() {
        val pluginRepo = File(property("faro.pluginRepo")).toURI()
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$pluginRepo") }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "faro-sample-app"
            """.trimIndent(),
        )
        projectDir.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx2g\n")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "${property("faro.minAgpVersion")}"
                id("com.grafana.faro.android-symbols") version "${property("faro.pluginVersion")}"
            }

            android {
                namespace = "com.example.faro"
                compileSdk = 34
                defaultConfig {
                    applicationId = "com.example.faro"
                    minSdk = 24
                    versionCode = 7
                    versionName = "1.2.3"
                }
                buildTypes {
                    release {
                        isMinifyEnabled = true
                    }
                }
            }

            faro {
                endpoint = "${server.url("/collect")}"
                appId = "42"
                stackId = "777"
                apiKey = "test-key"
            }
            """.trimIndent(),
        )
        projectDir.resolve("src/main/AndroidManifest.xml").apply { parentFile.mkdirs() }.writeText(
            """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />""",
        )
        projectDir.resolve("src/main/java/com/example/faro/Greeter.java").apply { parentFile.mkdirs() }.writeText(
            """
            package com.example.faro;

            public class Greeter {
                public String greet() { return "hello"; }
            }
            """.trimIndent(),
        )
    }

    private fun property(name: String): String =
        checkNotNull(System.getProperty(name)) { "missing system property $name; run via `gradle functionalTest`" }
}
