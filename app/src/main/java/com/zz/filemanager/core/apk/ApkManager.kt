package com.zz.filemanager.core.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.SafeOutputWriter
import com.zz.filemanager.core.step4.SafeWriteResult
import com.zz.filemanager.core.storage.StorageProviderRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlin.coroutines.coroutineContext

class ApkManager(
    context: Context,
    private val providers: StorageProviderRegistry,
    private val safeWriter: SafeOutputWriter,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val cacheRoot = File(appContext.cacheDir, "step4-apk").apply { mkdirs() }

    @Suppress("DEPRECATION")
    suspend fun inspect(entry: FileEntry): ApkMetadata = withContext(Dispatchers.IO) {
        withApkFile(entry) { file ->
            val flags = PackageManager.GET_PERMISSIONS or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
                ?: throw IllegalArgumentException("Invalid or unsupported APK")
            val applicationInfo = info.applicationInfo ?: throw IllegalArgumentException("APK application metadata is missing")
            applicationInfo.sourceDir = file.absolutePath
            applicationInfo.publicSourceDir = file.absolutePath
            ApkMetadata(
                appLabel = runCatching { applicationInfo.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = versionCode(info),
                minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) applicationInfo.minSdkVersion else null,
                targetSdk = applicationInfo.targetSdkVersion,
                apkSizeBytes = entry.sizeBytes ?: file.length(),
                permissions = info.requestedPermissions?.toList()?.sorted().orEmpty(),
                certificates = signatures(info).mapNotNull(::certificate),
                installed = runCatching { packageManager.getPackageInfo(info.packageName, 0) }.isSuccess,
                icon = runCatching { applicationInfo.loadIcon(packageManager) }.getOrNull(),
            )
        }
    }

    @Suppress("DEPRECATION")
    suspend fun installedApps(query: String = ""): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { app ->
                query.isBlank() || app.packageName.contains(query, true) || runCatching { app.loadLabel(packageManager).toString().contains(query, true) }.getOrDefault(false)
            }
            .mapNotNull { app ->
                coroutineContext.ensureActive()
                val info = runCatching { packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return@mapNotNull null
                val base = app.sourceDir ?: return@mapNotNull null
                InstalledAppInfo(
                    label = runCatching { app.loadLabel(packageManager).toString() }.getOrDefault(app.packageName),
                    packageName = app.packageName,
                    versionName = info.versionName,
                    versionCode = versionCode(info),
                    firstInstallTime = info.firstInstallTime.takeIf { it > 0L },
                    lastUpdateTime = info.lastUpdateTime.takeIf { it > 0L },
                    baseApkPath = base,
                    splitApkPaths = app.splitSourceDirs?.toList().orEmpty(),
                    baseApkSizeBytes = File(base).length().takeIf { it >= 0L },
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = runCatching { app.loadIcon(packageManager) }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun backup(app: InstalledAppInfo, destination: BrowserLocation, mode: ApkBackupMode): ApkBackupResult = withContext(Dispatchers.IO) {
        when (mode) {
            ApkBackupMode.BASE_ONLY -> {
                val file = File(app.baseApkPath)
                require(file.isFile && file.canRead()) { "Installed base APK is unavailable" }
                val result = FileInputStream(file).use { input ->
                    safeWriter.write(destination, safeApkName(app.packageName), "application/vnd.android.package-archive", input, file.length(), CollisionPolicy.KEEP_BOTH)
                }
                val written = (result as SafeWriteResult.Written).entry.name
                ApkBackupResult(ApkBackupMode.BASE_ONLY, listOf(written), reinstallableSetPreserved = !app.hasSplits)
            }
            ApkBackupMode.COMPLETE_SPLITS -> backupSplits(app, destination)
        }
    }

    private suspend fun backupSplits(app: InstalledAppInfo, destination: BrowserLocation): ApkBackupResult {
        val provider = providers.writableProviderFor(destination.providerId) ?: throw IllegalStateException("Backup destination is read-only")
        val folderName = uniqueFolderName(provider, destination, "${sanitizeLeaf(app.label)}-${app.versionCode}-apks")
        val folder = provider.createDirectory(destination, folderName)
        val folderLocation = BrowserLocation(
            providerId = folder.reference.providerId,
            id = folder.id,
            displayName = folder.name,
            reference = folder.reference.uri ?: folder.reference.path ?: folder.reference.opaqueId,
            rootReference = destination.rootReference,
            storageId = destination.storageId,
            readable = folder.isReadable,
            writable = folder.isWritable,
        )
        val paths = listOf(app.baseApkPath) + app.splitApkPaths
        val exported = mutableListOf<String>()
        try {
            paths.forEachIndexed { index, path ->
                coroutineContext.ensureActive()
                val source = File(path)
                require(source.isFile && source.canRead()) { "Installed APK component is unavailable: $path" }
                val name = if (index == 0) "base.apk" else sanitizeLeaf(source.name).let { if (it.endsWith(".apk", true)) it else "$it.apk" }
                val result = FileInputStream(source).use { input ->
                    safeWriter.write(folderLocation, name, "application/vnd.android.package-archive", input, source.length(), CollisionPolicy.KEEP_BOTH)
                }
                exported += (result as SafeWriteResult.Written).entry.name
            }
            return ApkBackupResult(ApkBackupMode.COMPLETE_SPLITS, exported, reinstallableSetPreserved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<ByteArray> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signing = info.signingInfo ?: return emptyList()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        signatures?.map { it.toByteArray() }.orEmpty()
    } else {
        info.signatures?.map { it.toByteArray() }.orEmpty()
    }

    private fun certificate(bytes: ByteArray): ApkCertificate? = runCatching {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        ApkCertificate(
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) },
            subject = cert.subjectX500Principal?.name,
            issuer = cert.issuerX500Principal?.name,
        )
    }.getOrElse {
        runCatching { ApkCertificate(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { b -> "%02X".format(b) }, null, null) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private suspend fun <T> withApkFile(entry: FileEntry, block: (File) -> T): T {
        val direct = entry.reference.path?.let(::File)?.takeIf { it.isFile && it.canRead() }
        if (direct != null) return block(direct)
        val usable = cacheRoot.usableSpace
        val expected = entry.sizeBytes
        if (expected != null && usable >= 0L && expected > usable) throw IllegalStateException("Not enough temporary storage to inspect this APK")
        val staged = File(cacheRoot, ".zzapk-${UUID.randomUUID()}.apk")
        try {
            providers.providerFor(entry.reference.providerId).openInputStream(entry.reference).use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count.toLong()
                        if (usable >= 0L && copied > usable) throw IllegalStateException("Temporary storage became insufficient")
                    }
                }
            }
            return block(staged)
        } finally {
            staged.delete()
        }
    }

    private suspend fun uniqueFolderName(provider: com.zz.filemanager.core.storage.WritableStorageProvider, parent: BrowserLocation, requested: String): String {
        if (provider.findChild(parent, requested) == null) return requested
        var index = 1
        while (true) {
            val candidate = "$requested ($index)"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private fun safeApkName(packageName: String) = sanitizeLeaf(packageName) + ".apk"
    private fun sanitizeLeaf(value: String): String = value.replace(Regex("[\\/:*?\"<>|\\u0000]"), "_").trim().ifBlank { "app" }
}
