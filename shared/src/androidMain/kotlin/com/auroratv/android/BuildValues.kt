package com.auroratv.android

import android.content.Context
import android.content.pm.PackageManager

/**
 * Values the build writes into the app's manifest (see androidApp/build.gradle.kts): the commit it was
 * made from and the built-in disc menu token. Each is stored with a one-letter prefix so Android keeps
 * it as text; an empty value means a local build, or no token set for the build.
 */
internal class BuildValues(context: Context) {
    private val meta = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    }.getOrNull()

    private fun value(name: String): String = meta?.get(name)?.toString()?.drop(1)?.trim().orEmpty()

    val commit: String = value("com.auroratv.commit")
    val discMenuToken: String = value("com.auroratv.discMenuToken")
    val discMenuSource: String = value("com.auroratv.discMenuSource")
}
