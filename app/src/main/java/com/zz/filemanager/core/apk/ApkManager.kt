package com.zz.filemanager.core.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.step4.SafeOutputWriter
import com.zz.filemanager.core.step4.SafeWriteResult
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
                    totalApkSizeBytes = (listOf(base) + app.splitSourceDirs.orEmpty()).map(::File).filter(File::isFile).sumOf(File::length),
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    icon = null,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    suspend fun installedAppIcon(packageName: String) = withContext(Dispatchers.IO) {
        runCatching { packageManager.getApplicationInfo(packageName, 0).loadIcon(packageManager) }.getOrNull()
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

    /**
     * Exports the complete installed APK set into a hidden transaction directory. Components are
     * never written into the visible final folder. A manifest containing every component's byte
     * length and SHA-256 is written last; SafeOutputWriter then independently hashes every staged
     * member and only renames the whole directory after the set is complete and proved.
     */
    private suspend fun backupSplits(app: InstalledAppInfo, destination: BrowserLocation): ApkBackupResult {
        val provider = providers.writableProviderFor(destination.providerId)
            ?: throw IllegalStateException("Backup destination is read-only")
        val requestedFolder = "${sanitizeLeaf(app.label)}-${app.versionCode}-apks"
        val paths = listOf(app.baseApkPath) + app.splitApkPaths
        val exported = mutableListOf<String>()

        safeWriter.writeDirectoryAtomically(
            parent = destination,
            requestedName = requestedFolder,
            stagePrefix = ".zzapkbackup-",
        ) { stagingDirectory ->
            val usedNames = mutableSetOf<String>()
            val componentProofs = mutableListOf<ApkComponentProof>()
            paths.forEachIndexed { index, path ->
                coroutineContext.ensureActive()
                val source = File(path)
                require(source.isFile && source.canRead()) { "Installed APK component is unavailable: $path" }
                val preferred = if (index == 0) {
                    "base.apk"
                } else {
                    sanitizeLeaf(source.name).let { if (it.endsWith(".apk", true)) it else "$it.apk" }
                }
                val name = uniqueComponentName(preferred, usedNames)
                val proof = writeComponent(provider, stagingDirectory, source, name)
                usedNames += name
                exported += name
                componentProofs += proof
            }

            require(componentProofs.size == paths.size) { "Not every installed APK component was exported" }
            val manifestBytes = JSONObject().apply {
                put("schemaVersion", 1)
                put("setId", UUID.randomUUID().toString())
                put("complete", true)
                put("packageName", app.packageName)
                put("versionCode", app.versionCode)
                put("versionName", app.versionName ?: JSONObject.NULL)
                put("componentCount", componentProofs.size)
                put("components", JSONArray().apply {
                    componentProofs.forEach { proof ->
                        put(JSONObject().apply {
                            put("name", proof.name)
                            put("sizeBytes", proof.sizeBytes)
                            put("sha256", proof.sha256)
                        })
                    }
                })
            }.toString().toByteArray(Charsets.UTF_8)
            writeBytes(
                provider = provider,
                directory = stagingDirectory,
                name = MANIFEST_NAME,
                mimeType = "application/json",
                bytes = manifestBytes,
            )

            // Re-read and validate the manifest before allowing directory finalization.
            val manifestEntry = provider.findChild(stagingDirectory, MANIFEST_NAME)
                ?: throw IllegalStateException("Complete APK backup manifest disappeared")
            val parsed = provider.openInputStream(manifestEntry.reference).bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText())
            }
            if (!parsed.optBoolean("complete") || parsed.optInt("componentCount") != paths.size) {
                throw IllegalStateException("Complete APK backup manifest verification failed")
            }
        }

        return ApkBackupResult(
            ApkBackupMode.COMPLETE_SPLITS,
            exported,
            reinstallableSetPreserved = true,
        )
    }

    private data class ApkComponentProof(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private suspend fun writeComponent(
        provider: WritableStorageProvider,
        directory: BrowserLocation,
        source: File,
        name: String,
    ): ApkComponentProof {
        val created = provider.createFile(directory, name, "application/vnd.android.package-archive")
        val scoped = ScopedFileReference(created.reference, directory.rootReference, directory.storageId)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            FileInputStream(source).use { input ->
                provider.openOutputStream(scoped, truncate = true).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count.toLong()
                    }
                    output.flush()
                }
            }
            if (copied != source.length()) throw IllegalStateException("APK component byte count changed during backup: $name")
            val verified = provider.getMetadata(created.reference)
                ?: throw IllegalStateException("APK component disappeared during backup: $name")
            if (verified.sizeBytes != null && verified.sizeBytes != copied) {
                throw IllegalStateException("APK component size verification failed: $name")
            }
            return ApkComponentProof(name, copied, digest.digest().toHex())
        } catch (error: Throwable) {
            runCatching { provider.delete(scoped) }
            throw error
        }
    }

    private suspend fun writeBytes(
        provider: WritableStorageProvider,
        directory: BrowserLocation,
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val created = provider.createFile(directory, name, mimeType)
        val scoped = ScopedFileReference(created.reference, directory.rootReference, directory.storageId)
        try {
            provider.openOutputStream(scoped, truncate = true).use { output ->
                output.write(bytes)
                output.flush()
            }
            val verified = provider.getMetadata(created.reference)
                ?: throw IllegalStateException("Backup manifest disappeared")
            if (verified.sizeBytes != null && verified.sizeBytes != bytes.size.toLong()) {
                throw IllegalStateException("Backup manifest size verification failed")
            }
        } catch (error: Throwable) {
            runCatching { provider.delete(scoped) }
            throw error
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
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) },
            subject = cert.subjectX500Principal?.name,
            issuer = cert.issuerX500Principal?.name,
        )
    }.getOrElse {
        runCatching { ApkCertificate(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> "%02X".format(b) }, null, null) }.getOrNull()
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

    private fun uniqueComponentName(preferred: String, used: Set<String>): String {
        if (preferred !in used && preferred != MANIFEST_NAME) return preferred
        val dot = preferred.lastIndexOf('.')
        val base = if (dot > 0) preferred.substring(0, dot) else preferred
        val extension = if (dot > 0) preferred.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base-$index$extension"
            if (candidate !in used && candidate != MANIFEST_NAME) return candidate
            index++
        }
    }

    private fun safeApkName(packageName: String) = sanitizeLeaf(packageName) + ".apk"
    private fun sanitizeLeaf(value: String): String = value.replace(Regex("[\\/:*?\"<>|\\u0000]"), "_").trim().ifBlank { "app" }
    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private companion object {
        const val MANIFEST_NAME = "zz-apk-backup-manifest.json"
        const val COPY_BUFFER_SIZE = 256 * 1024
    }
}
