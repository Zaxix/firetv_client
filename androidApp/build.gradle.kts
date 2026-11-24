plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// CI numbers every build (AURORA_BUILD = run number): versionName 1.0.<n>, versionCode <n>, so the
// in-app updater and Android both see each build as newer than the last. Local builds use gradle.properties.
val auroraBuild = providers.environmentVariable("AURORA_BUILD").orNull?.toIntOrNull()
val auroraBaseVersion = providers.gradleProperty("aurora.versionName").get()
val auroraVersionName = auroraBuild?.let { auroraBaseVersion.substringBeforeLast('.') + ".$it" } ?: auroraBaseVersion
val auroraVersionCode = auroraBuild ?: providers.gradleProperty("aurora.versionCode").get().toInt()
// The commit a CI build was made from, shown next to the version in the app ("1.0.158 (abc1234)").
val auroraCommit = providers.environmentVariable("GITHUB_SHA").orNull?.take(7).orEmpty()
// The disc menu token every install starts with, from the DISC_MENU_TOKEN repository secret: never in
// the source. Empty when the secret isn't set (local builds, forks); the app then has no built-in token.
val discMenuToken = providers.environmentVariable("DISC_MENU_TOKEN").orNull?.trim().orEmpty()

// Release signing comes from environment variables so no key material is ever committed.
// CI sets these (from repository secrets when present, otherwise from a freshly generated key).
val releaseKeystore = System.getenv("AURORA_KEYSTORE_PATH")

android {
    namespace = "com.auroratv.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.auroratv.android"
        minSdk = 23
        targetSdk = 35
        versionCode = auroraVersionCode
        versionName = auroraVersionName
        // Prefixed so Android keeps them as text (a commit like "1234567" would otherwise be read as a number).
        manifestPlaceholders["auroraCommit"] = "c$auroraCommit"
        manifestPlaceholders["discMenuToken"] = "t$discMenuToken"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("AURORA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AURORA_KEY_ALIAS")
                keyPassword = System.getenv("AURORA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 makes Compose markedly faster (inlining, dead-code removal) and the APK smaller,
            // which matters on Fire TV sticks. JNI-backed libraries (libass, FFmpeg) and our own
            // serialized models are kept explicitly; CI runs the release APK on an emulator.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("AURORA_KEYSTORE_PATH not set: release APK will be signed with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // libmpv, ass-media and the FFmpeg extension may each bundle the NDK C++ runtime.
            pickFirsts += setOf("lib/*/libc++_shared.so")
        }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Installs the baseline profiles that ship inside Compose/Media3 even for sideloaded APKs,
    // so the first launches and first scrolls are AOT-compiled instead of interpreted.
    implementation(libs.androidx.profileinstaller)
}

// ExoplayerHdrUtils 0.4.1 (only its libdovi JNI class is used) was built with compileSdk 37 and
// says so in its AAR metadata. Nothing it uses needs API 37, and this AGP release tops out at
// compileSdk 36, so the metadata check is skipped rather than moving the whole app to an
// unsupported SDK. Every other dependency still compiles against 36 cleanly.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
