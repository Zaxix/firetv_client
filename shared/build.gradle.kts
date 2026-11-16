import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Same numbering as the apps: CI builds are 1.0.<run number> (AURORA_BUILD).
val versionName = providers.gradleProperty("aurora.versionName").get().let { base ->
    providers.environmentVariable("AURORA_BUILD").orNull?.toIntOrNull()?.let { base.substringBeforeLast('.') + ".$it" } ?: base
}

// Generates AppInfo.kt so shared code (About screen, Jellyfin client header) knows the real version.
val generateAppInfo = tasks.register("generateAppInfo") {
    val outDir = layout.buildDirectory.dir("generated/appinfo/commonMain/kotlin")
    val version = versionName
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/auroratv/AppInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.auroratv
            |
            |object AppInfo {
            |    /** The app's display name (package ids and data folders keep the original "Aurora" names). */
            |    const val NAME = "Sharkfin"
            |    const val VERSION = "$version"
            |}
            """.trimMargin()
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            // ExoplayerHdrUtils may be built with a newer Kotlin; only its plain LibDovi API is used.
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateAppInfo)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
            implementation(libs.media3.effect)
            // Video over the app's own OkHttp client: reuses its warm connections to the server.
            implementation(libs.media3.datasource.okhttp)
            implementation(libs.ass.media)
            implementation(libs.jellyfin.media3.ffmpeg)
            // libmpv for Android (mpv 0.42 + FFmpeg + libass), the optional second player. GPL-3.0.
            implementation(libs.libmpv.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.common)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

// libdovi (Dolby Vision RPU rewriting, MIT) packaged with JNI bindings by ExoplayerHdrUtils (MIT).
// Only its LibDovi class is used, so the library's own Media3 dependency (a newer release than the
// one this app is built and tested with) is not pulled in.
dependencies {
    "androidMainImplementation"(libs.exoplayerhdrutils) { isTransitive = false }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.auroratv.resources"
    generateResClass = always
}

android {
    namespace = "com.auroratv.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// See androidApp/build.gradle.kts: ExoplayerHdrUtils' AAR metadata asks for compileSdk 37. In a
// library the check's (empty) output directory is an input of the AAR bundle task, so the task is
// kept but its check action is replaced by creating that directory.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach {
    actions.clear()
    doLast { outputs.files.forEach { it.mkdirs() } }
}
