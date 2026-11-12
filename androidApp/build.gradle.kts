plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val auroraVersionName = providers.gradleProperty("aurora.versionName").get()
val auroraVersionCode = providers.gradleProperty("aurora.versionCode").get().toInt()

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
            // R8 is left off deliberately: media3 extensions and libass are JNI-heavy and an
            // over-eager shrink breaks them at runtime on devices we cannot test from CI.
            isMinifyEnabled = false
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
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties") }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
