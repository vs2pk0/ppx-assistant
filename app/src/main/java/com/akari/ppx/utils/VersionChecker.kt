package com.akari.ppx.utils

import com.akari.ppx.App.Companion.context
import com.akari.ppx.data.Const.TARGET_APP_ID

object VersionChecker {
    val supportedVersions = listOf("6.5.0", "6.2.0")
    val supportedVersionLabel = supportedVersions.joinToString("、")

    val targetVersion: String
        get() = runCatching {
            context.packageManager.getPackageInfo(TARGET_APP_ID, 0).versionName ?: "(未知)"
        }.getOrDefault("(未知)")

}
