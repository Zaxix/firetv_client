import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val auroraVersionName = providers.gradleProperty("aurora.versionName").get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jna)
    implementation(libs.jna.platform)
}

compose.desktop {
    application {
        mainClass = "com.auroratv.desktop.MainKt"
        jvmArgs += listOf("-Dsun.java2d.d3d=false", "-Xmx768m")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Aurora TV"
            packageVersion = auroraVersionName
            description = "Aurora TV — Jellyfin client"
            vendor = "Aurora TV"
            licenseFile.set(rootProject.file("LICENSE"))
            // CI drops libmpv-2.dll (and its license) into native/windows before packaging.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("native"))
            modules("java.instrument", "java.management", "java.naming", "jdk.unsupported", "java.net.http", "jdk.crypto.ec")

            windows {
                iconFile.set(project.file("icons/aurora.ico"))
                menuGroup = "Aurora TV"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8c2f5c1e-4b7a-4d0e-9a51-3f3d6b8e2a77"
            }
        }
    }
}
