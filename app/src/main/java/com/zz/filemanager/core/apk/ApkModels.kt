package com.zz.filemanager.core.apk

import android.graphics.drawable.Drawable

data class ApkCertificate(
    val sha256: String,
    val subject: String?,
    val issuer: String?,
)

data class ApkMetadata(
    val appLabel: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int?,
    val targetSdk: Int,
    val apkSizeBytes: Long?,
    val permissions: List<String>,
    val certificates: List<ApkCertificate>,
    val installed: Boolean,
    val icon: Drawable?,
)

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstallTime: Long?,
    val lastUpdateTime: Long?,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val baseApkSizeBytes: Long?,
    val totalApkSizeBytes: Long? = baseApkSizeBytes,
    val isSystemApp: Boolean,
    val icon: Drawable?,
) {
    val hasSplits: Boolean get() = splitApkPaths.isNotEmpty()
}

enum class ApkBackupMode { BASE_ONLY, COMPLETE_SPLITS }

data class ApkBackupResult(
    val mode: ApkBackupMode,
    val exportedNames: List<String>,
    val reinstallableSetPreserved: Boolean,
)
