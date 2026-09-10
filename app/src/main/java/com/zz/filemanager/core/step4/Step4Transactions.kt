package com.zz.filemanager.core.step4

import android.content.Context
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.ScopedFileReference
import com.zz.filemanager.core.operation.CollisionPolicy
import com.zz.filemanager.core.storage.StorageCapability
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import com.zz.filemanager.core.util.BrowserLocationCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class Step4WritePhase {
    STAGING,
    STAGED,
    ATOMIC_COMMITTING,
    BACKUP_PLANNED,
    BACKED_UP,
    COMMITTING,
    COMMITTED,
}

enum class Step4OutputKind { FILE, DIRECTORY }

data class Step4MemberProof(
    val name: String,
    val sizeBytes: Long?,
    val sha256: String,
)

data class Step4WriteTransaction(
    val id: String,
    val parent: BrowserLocation,
    val finalName: String,
    val stagedName: String,
    val backupName: String?,
    val expectedBytes: Long?,
    val phase: Step4WritePhase,
    val outputKind: Step4OutputKind = Step4OutputKind.FILE,
    val replacesExisting: Boolean = false,
    val stagedMutationIdentity: String? = null,
    val contentSha256: String? = null,
    val directoryMembers: List<Step4MemberProof> = emptyList(),
)

sealed interface SafeWriteResult {
    data class Written(val entry: FileEntry, val sha256: String? = null) : SafeWriteResult
    data class Skipped(val existing: FileEntry) : SafeWriteResult
}

/**
 * Durable Step 4 write journal.
 *
 * The journal is written before a hidden stage is created. This means process death while bytes
 * are still being streamed leaves a discoverable STAGING transaction rather than an untracked
 * partial. Older Step 4 records are decoded conservatively: missing proof fields are never treated
 * as evidence that a commit succeeded.
 */
class Step4WriteJournal(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("step4_write_transactions", Context.MODE_PRIVATE)

    @Synchronized
    fun list(): List<Step4WriteTransaction> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index -> decode(array.getJSONObject(index))?.let(::add) }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun put(transaction: Step4WriteTransaction) {
        val updated = list().associateBy { it.id }.toMutableMap().apply { put(transaction.id, transaction) }
        persist(updated.values.toList())
    }

    @Synchronized
    fun remove(id: String) = persist(list().filterNot { it.id == id })

    private fun persist(items: List<Step4WriteTransaction>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("parent", BrowserLocationCodec.encode(item.parent))
                put("finalName", item.finalName)
                put("stagedName", item.stagedName)
                put("backupName", item.backupName ?: JSONObject.NULL)
                put("expectedBytes", item.expectedBytes ?: JSONObject.NULL)
                put("phase", item.phase.name)
                put("outputKind", item.outputKind.name)
                put("replacesExisting", item.replacesExisting)
                put("stagedMutationIdentity", item.stagedMutationIdentity ?: JSONObject.NULL)
                put("contentSha256", item.contentSha256 ?: JSONObject.NULL)
                put("directoryMembers", JSONArray().apply {
                    item.directoryMembers.forEach { proof ->
                        put(JSONObject().apply {
                            put("name", proof.name)
                            put("sizeBytes", proof.sizeBytes ?: JSONObject.NULL)
                            put("sha256", proof.sha256)
                        })
                    }
                })
            })
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private fun decode(json: JSONObject): Step4WriteTransaction? = runCatching {
        val phase = Step4WritePhase.valueOf(json.getString("phase"))
        val members = buildList {
            val array = json.optJSONArray("directoryMembers") ?: JSONArray()
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    Step4MemberProof(
                        name = item.getString("name"),
                        sizeBytes = if (item.isNull("sizeBytes")) null else item.getLong("sizeBytes"),
                        sha256 = item.getString("sha256"),
                    ),
                )
            }
        }
        Step4WriteTransaction(
            id = json.getString("id"),
            parent = BrowserLocationCodec.decode(json.getString("parent")) ?: return null,
            finalName = json.getString("finalName"),
            stagedName = json.getString("stagedName"),
            backupName = if (json.isNull("backupName")) null else json.getString("backupName"),
            expectedBytes = if (json.isNull("expectedBytes")) null else json.getLong("expectedBytes"),
            phase = phase,
            outputKind = runCatching {
                Step4OutputKind.valueOf(json.optString("outputKind", Step4OutputKind.FILE.name))
            }.getOrDefault(Step4OutputKind.FILE),
            replacesExisting = if (json.has("replacesExisting")) json.optBoolean("replacesExisting") else true,
            stagedMutationIdentity = if (!json.has("stagedMutationIdentity") || json.isNull("stagedMutationIdentity")) null
            else json.getString("stagedMutationIdentity"),
            contentSha256 = if (!json.has("contentSha256") || json.isNull("contentSha256")) null
            else json.getString("contentSha256"),
            directoryMembers = members,
        )
    }.getOrNull()

    private companion object { const val KEY = "transactions" }
}

/**
 * Transactional writer shared by extraction, text Save As, APK backup and generated archives.
 *
 * Invariants:
 * - user-facing final names are never opened for streaming;
 * - a journal exists before a hidden stage is created;
 * - the completed stage is proved before any final-name mutation;
 * - recovery never accepts filename + size as commit proof;
 * - provider mutation identity is authoritative when available; otherwise SHA-256 is streamed with
 *   bounded memory and used as the commit proof;
 * - replacement backups are deleted only after the final object is proved to be the staged object.
 */
class SafeOutputWriter(
    private val providers: StorageProviderRegistry,
    private val journal: Step4WriteJournal,
) {
    suspend fun write(
        parent: BrowserLocation,
        requestedName: String,
        mimeType: String?,
        input: InputStream,
        expectedBytes: Long?,
        collisionPolicy: CollisionPolicy,
        onProgress: suspend (Long) -> Unit = {},
    ): SafeWriteResult = writeGenerated(
        parent = parent,
        requestedName = requestedName,
        mimeType = mimeType,
        expectedBytes = expectedBytes,
        collisionPolicy = collisionPolicy,
    ) { output ->
        copy(input, output, onProgress)
    }

    suspend fun writeGenerated(
        parent: BrowserLocation,
        requestedName: String,
        mimeType: String?,
        expectedBytes: Long? = null,
        collisionPolicy: CollisionPolicy = CollisionPolicy.KEEP_BOTH,
        producer: suspend (OutputStream) -> Unit,
    ): SafeWriteResult = withContext(Dispatchers.IO) {
        val provider = providers.writableProviderFor(parent.providerId)
            ?: throw IllegalStateException("Destination is read-only")
        requireFileStaging(provider, parent)

        var finalName = requestedName
        var plannedExisting = provider.findChild(parent, finalName)
        when {
            plannedExisting == null -> Unit
            collisionPolicy == CollisionPolicy.SKIP -> return@withContext SafeWriteResult.Skipped(plannedExisting)
            collisionPolicy == CollisionPolicy.KEEP_BOTH -> {
                finalName = uniqueName(provider, parent, requestedName)
                plannedExisting = null
            }
            collisionPolicy == CollisionPolicy.MERGE ->
                throw IllegalArgumentException("MERGE is only valid for directories")
            plannedExisting?.isDirectory == true ->
                throw IllegalStateException("Cannot replace a directory with a file")
        }

        val plannedExistingIdentity = plannedExisting?.let {
            runCatching { provider.mutationIdentity(it.reference) }.getOrNull()
        }
        val staged = stageFile(
            provider = provider,
            parent = parent,
            finalName = finalName,
            mimeType = mimeType,
            expectedBytes = expectedBytes,
            replacesExisting = plannedExisting != null,
            producer = producer,
        )
        var tx = staged.transaction
        var existingForReplace = plannedExisting

        val live = provider.findChild(parent, tx.finalName)
        if (existingForReplace == null && live != null) {
            when (collisionPolicy) {
                CollisionPolicy.SKIP -> {
                    discardUncommitted(provider, tx, staged.entry)
                    return@withContext SafeWriteResult.Skipped(live)
                }
                CollisionPolicy.KEEP_BOTH -> {
                    val unique = uniqueName(provider, parent, requestedName)
                    tx = tx.copy(finalName = unique)
                    journal.put(tx)
                }
                CollisionPolicy.REPLACE -> {
                    if (live.isDirectory) {
                        discardUncommitted(provider, tx, staged.entry)
                        throw IllegalStateException("Destination changed to a directory while the output was staged")
                    }
                    existingForReplace = live
                    tx = tx.copy(replacesExisting = true)
                    journal.put(tx)
                }
                CollisionPolicy.MERGE -> error("validated above")
            }
        } else if (existingForReplace != null) {
            when {
                live == null -> {
                    existingForReplace = null
                    tx = tx.copy(replacesExisting = false)
                    journal.put(tx)
                }
                !sameDestinationSnapshot(provider, existingForReplace, live, plannedExistingIdentity) -> {
                    discardUncommitted(provider, tx, staged.entry)
                    throw IllegalStateException("Destination changed while the replacement was staged; no data was replaced")
                }
                else -> existingForReplace = live
            }
        }

        val committed = if (existingForReplace == null) {
            commitFresh(provider, tx, staged.entry)
        } else {
            commitReplacement(provider, tx, staged.entry, existingForReplace)
        }
        SafeWriteResult.Written(committed, staged.sha256)
    }

    suspend fun writeDirectoryAtomically(
        parent: BrowserLocation,
        requestedName: String,
        stagePrefix: String = ".zzapkbackup-",
        producer: suspend (BrowserLocation) -> Unit,
    ): FileEntry = withContext(Dispatchers.IO) {
        val provider = providers.writableProviderFor(parent.providerId)
            ?: throw IllegalStateException("Destination is read-only")
        val capabilities = provider.capabilities(parent)
        if (
            StorageCapability.CREATE_DIRECTORY !in capabilities ||
            StorageCapability.CREATE_FILE !in capabilities ||
            StorageCapability.RENAME !in capabilities ||
            StorageCapability.DELETE !in capabilities
        ) {
            throw IllegalStateException(
                "Provider cannot safely finalize a staged directory; complete backup is unsupported here",
            )
        }

        val id = UUID.randomUUID().toString()
        val stagedName = uniqueInternalName(provider, parent, "$stagePrefix$id")
        var finalName = uniqueDirectoryName(provider, parent, requestedName)
        var tx = Step4WriteTransaction(
            id = id,
            parent = parent,
            finalName = finalName,
            stagedName = stagedName,
            backupName = null,
            expectedBytes = null,
            phase = Step4WritePhase.STAGING,
            outputKind = Step4OutputKind.DIRECTORY,
            replacesExisting = false,
        )
        journal.put(tx)

        val stagedEntry = try {
            provider.createDirectory(parent, stagedName)
        } catch (error: Throwable) {
            journal.remove(id)
            throw error
        }
        val stagedRef = stagedEntry.scoped(parent)
        val stagedLocation = stagedEntry.asLocation(parent)

        try {
            producer(stagedLocation)
            coroutineContext.ensureActive()

            val proofs = collectDirectoryProofs(provider, stagedLocation)
            require(proofs.isNotEmpty()) { "Staged directory is empty" }
            val stageIdentity = runCatching { provider.mutationIdentity(stagedEntry.reference) }.getOrNull()
            tx = tx.copy(
                phase = Step4WritePhase.STAGED,
                stagedMutationIdentity = stageIdentity,
                directoryMembers = proofs,
            )
            journal.put(tx)

            if (provider.findChild(parent, finalName) != null) {
                finalName = uniqueDirectoryName(provider, parent, requestedName)
                tx = tx.copy(finalName = finalName)
                journal.put(tx)
            }

            tx = tx.copy(phase = Step4WritePhase.COMMITTING)
            journal.put(tx)
            val committed = provider.rename(stagedRef, finalName)
            val verified = provider.getMetadata(committed.reference)
                ?: throw IllegalStateException("Committed directory disappeared")
            if (!proveFinal(provider, tx, verified)) {
                throw IllegalStateException(
                    "Committed directory could not be proven as the staged directory; recovery record retained",
                )
            }

            tx = tx.copy(phase = Step4WritePhase.COMMITTED)
            journal.put(tx)
            journal.remove(id)
            verified
        } catch (cancelled: CancellationException) {
            cleanupUncommittedDirectoryIfSafe(provider, tx, stagedEntry)
            throw cancelled
        } catch (error: Throwable) {
            cleanupUncommittedDirectoryIfSafe(provider, tx, stagedEntry)
            throw error
        }
    }

    suspend fun reconcile(): List<String> = withContext(Dispatchers.IO) {
        val unresolved = mutableListOf<String>()
        journal.list().forEach { tx ->
            coroutineContext.ensureActive()
            val provider = providers.writableProviderFor(tx.parent.providerId)
            if (provider == null) {
                unresolved += tx.id
                return@forEach
            }

            val staged = runCatching { provider.findChild(tx.parent, tx.stagedName) }.getOrNull()
            val final = runCatching { provider.findChild(tx.parent, tx.finalName) }.getOrNull()
            val backup = tx.backupName?.let {
                runCatching { provider.findChild(tx.parent, it) }.getOrNull()
            }

            when (tx.phase) {
                Step4WritePhase.STAGING,
                Step4WritePhase.STAGED -> {
                    if (staged == null || cleanupStaged(provider, tx, staged)) journal.remove(tx.id)
                    else unresolved += tx.id
                }

                Step4WritePhase.ATOMIC_COMMITTING -> {
                    val committed = staged == null && final != null &&
                        runCatching { proveFinal(provider, tx, final) }.getOrDefault(false)
                    if (committed) journal.remove(tx.id) else unresolved += tx.id
                }

                Step4WritePhase.BACKUP_PLANNED -> when {
                    backup != null && final == null -> restoreBackup(provider, tx, backup, staged, unresolved)
                    backup != null && staged == null && final != null &&
                        runCatching { proveFinal(provider, tx, final) }.getOrDefault(false) ->
                        finishCommitted(provider, tx, backup, unresolved)
                    else -> unresolved += tx.id
                }

                Step4WritePhase.BACKED_UP -> when {
                    backup != null && final == null -> restoreBackup(provider, tx, backup, staged, unresolved)
                    else -> unresolved += tx.id
                }

                Step4WritePhase.COMMITTING -> {
                    if (!tx.replacesExisting) {
                        when {
                            staged == null && final != null &&
                                runCatching { proveFinal(provider, tx, final) }.getOrDefault(false) ->
                                journal.remove(tx.id)
                            staged != null && final == null -> {
                                if (cleanupStaged(provider, tx, staged)) journal.remove(tx.id)
                                else unresolved += tx.id
                            }
                            else -> unresolved += tx.id
                        }
                    } else {
                        when {
                            backup != null && final == null ->
                                restoreBackup(provider, tx, backup, staged, unresolved)
                            backup != null && staged == null && final != null &&
                                runCatching { proveFinal(provider, tx, final) }.getOrDefault(false) ->
                                finishCommitted(provider, tx, backup, unresolved)
                            else -> unresolved += tx.id
                        }
                    }
                }

                Step4WritePhase.COMMITTED -> {
                    val finalProven = final != null &&
                        runCatching { proveFinal(provider, tx, final) }.getOrDefault(false)
                    when {
                        !finalProven -> unresolved += tx.id
                        backup != null -> finishCommitted(provider, tx, backup, unresolved)
                        else -> journal.remove(tx.id)
                    }
                }
            }
        }
        unresolved
    }

    private data class StagedFile(
        val entry: FileEntry,
        val transaction: Step4WriteTransaction,
        val sha256: String,
    )

    private suspend fun stageFile(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        finalName: String,
        mimeType: String?,
        expectedBytes: Long?,
        replacesExisting: Boolean,
        producer: suspend (OutputStream) -> Unit,
    ): StagedFile {
        val id = UUID.randomUUID().toString()
        val stagedName = uniqueInternalName(provider, parent, ".zzstage-$id")
        var tx = Step4WriteTransaction(
            id = id,
            parent = parent,
            finalName = finalName,
            stagedName = stagedName,
            backupName = null,
            expectedBytes = expectedBytes,
            phase = Step4WritePhase.STAGING,
            outputKind = Step4OutputKind.FILE,
            replacesExisting = replacesExisting,
        )
        journal.put(tx)

        val staged = try {
            provider.createFile(parent, stagedName, mimeType)
        } catch (error: Throwable) {
            journal.remove(id)
            throw error
        }
        val stagedRef = staged.scoped(parent)

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val output = DigestCountingOutputStream(
                provider.openOutputStream(stagedRef, truncate = true),
                digest,
            )
            output.use { destination ->
                producer(destination)
            }
            coroutineContext.ensureActive()

            val copied = output.count
            if (expectedBytes != null && copied != expectedBytes) {
                throw IllegalStateException("Staged byte count does not match source")
            }
            val stagedVerified = provider.getMetadata(staged.reference)
                ?: throw IllegalStateException("Staged file disappeared")
            if (expectedBytes != null && stagedVerified.sizeBytes != null && stagedVerified.sizeBytes != expectedBytes) {
                throw IllegalStateException("Staged size verification failed")
            }
            if (stagedVerified.sizeBytes != null && stagedVerified.sizeBytes != copied) {
                throw IllegalStateException("Staged provider size does not match streamed byte count")
            }

            val sha256 = digest.digest().toHex()
            val mutationIdentity = runCatching {
                provider.mutationIdentity(stagedVerified.reference)
            }.getOrNull()
            tx = tx.copy(
                expectedBytes = copied,
                phase = Step4WritePhase.STAGED,
                stagedMutationIdentity = mutationIdentity,
                contentSha256 = sha256,
            )
            journal.put(tx)
            return StagedFile(stagedVerified, tx, sha256)
        } catch (cancelled: CancellationException) {
            discardStagingAfterFailure(provider, tx, staged)
            throw cancelled
        } catch (error: Throwable) {
            discardStagingAfterFailure(provider, tx, staged)
            throw error
        }
    }

    private suspend fun commitFresh(
        provider: WritableStorageProvider,
        initial: Step4WriteTransaction,
        staged: FileEntry,
    ): FileEntry {
        var tx = initial.copy(replacesExisting = false, phase = Step4WritePhase.COMMITTING)
        journal.put(tx)
        val committed = provider.rename(staged.scoped(tx.parent), tx.finalName)
        val verified = provider.getMetadata(committed.reference)
            ?: throw IllegalStateException("Committed output disappeared")
        if (!proveFinal(provider, tx, verified)) {
            throw IllegalStateException(
                "Committed output could not be proven as the staged object; recovery record retained",
            )
        }
        tx = tx.copy(phase = Step4WritePhase.COMMITTED)
        journal.put(tx)
        journal.remove(tx.id)
        return verified
    }

    private suspend fun commitReplacement(
        provider: WritableStorageProvider,
        initial: Step4WriteTransaction,
        staged: FileEntry,
        existing: FileEntry,
    ): FileEntry {
        val capabilities = provider.capabilities(initial.parent)
        if (StorageCapability.RENAME !in capabilities || StorageCapability.DELETE !in capabilities) {
            throw IllegalStateException("Provider cannot safely replace this file; use Keep Both or Save As")
        }

        var tx = initial.copy(replacesExisting = true)
        val stagedRef = staged.scoped(tx.parent)

        if (StorageCapability.ATOMIC_RENAME in capabilities) {
            tx = tx.copy(phase = Step4WritePhase.ATOMIC_COMMITTING)
            journal.put(tx)
            val atomic = provider.replaceAtomically(stagedRef, existing.scoped(tx.parent), tx.finalName)
            if (atomic != null) {
                val verified = provider.getMetadata(atomic.reference)
                    ?: throw IllegalStateException("Atomic replacement disappeared")
                if (!proveFinal(provider, tx, verified)) {
                    throw IllegalStateException(
                        "Atomic replacement could not be proven as the staged object; recovery record retained",
                    )
                }
                tx = tx.copy(phase = Step4WritePhase.COMMITTED)
                journal.put(tx)
                journal.remove(tx.id)
                return verified
            }
        }

        val backupName = uniqueInternalName(provider, tx.parent, ".zzbackup-${tx.id}")
        tx = tx.copy(phase = Step4WritePhase.BACKUP_PLANNED, backupName = backupName)
        journal.put(tx)
        val backup = provider.rename(existing.scoped(tx.parent), backupName)

        tx = tx.copy(phase = Step4WritePhase.BACKED_UP)
        journal.put(tx)
        tx = tx.copy(phase = Step4WritePhase.COMMITTING)
        journal.put(tx)
        val committed = provider.rename(stagedRef, tx.finalName)
        val verified = provider.getMetadata(committed.reference)
            ?: throw IllegalStateException("Replacement disappeared")
        if (!proveFinal(provider, tx, verified)) {
            throw IllegalStateException(
                "Replacement final could not be proven as the staged object; original backup retained",
            )
        }

        tx = tx.copy(phase = Step4WritePhase.COMMITTED)
        journal.put(tx)
        if (!provider.delete(backup.scoped(tx.parent))) {
            throw IllegalStateException("Replacement committed but backup cleanup is pending")
        }
        journal.remove(tx.id)
        return verified
    }

    private suspend fun restoreBackup(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        backup: FileEntry,
        staged: FileEntry?,
        unresolved: MutableList<String>,
    ) {
        val unexpectedFinal = runCatching { provider.findChild(tx.parent, tx.finalName) }.getOrNull()
        if (unexpectedFinal != null) {
            unresolved += tx.id
            return
        }
        val restored = runCatching {
            provider.rename(backup.scoped(tx.parent), tx.finalName)
        }.getOrNull()
        if (restored == null) {
            unresolved += tx.id
            return
        }
        if (staged != null && !cleanupStaged(provider, tx, staged)) {
            unresolved += tx.id
            return
        }
        journal.remove(tx.id)
    }

    private suspend fun finishCommitted(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        backup: FileEntry,
        unresolved: MutableList<String>,
    ) {
        val final = runCatching { provider.findChild(tx.parent, tx.finalName) }.getOrNull()
        val proven = final != null && runCatching { proveFinal(provider, tx, final) }.getOrDefault(false)
        if (!proven) {
            unresolved += tx.id
            return
        }
        if (runCatching { provider.delete(backup.scoped(tx.parent)) }.getOrDefault(false)) {
            journal.remove(tx.id)
        } else {
            unresolved += tx.id
        }
    }

    private suspend fun proveFinal(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        final: FileEntry,
    ): Boolean {
        if (tx.expectedBytes != null && final.sizeBytes != null && tx.expectedBytes != final.sizeBytes) {
            return false
        }

        val stagedIdentity = tx.stagedMutationIdentity
        if (stagedIdentity != null) {
            val finalIdentity = runCatching { provider.mutationIdentity(final.reference) }.getOrNull()
                ?: return false
            return finalIdentity == stagedIdentity
        }

        return when (tx.outputKind) {
            Step4OutputKind.FILE -> {
                val expectedDigest = tx.contentSha256 ?: return false
                hashFile(provider, final) == expectedDigest
            }
            Step4OutputKind.DIRECTORY -> proveDirectory(provider, tx, final)
        }
    }

    private suspend fun proveDirectory(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        final: FileEntry,
    ): Boolean {
        if (!final.isDirectory || tx.directoryMembers.isEmpty()) return false
        val location = final.asLocation(tx.parent)
        val live = runCatching { provider.listChildren(location) }.getOrNull() ?: return false
        if (live.any { it.isDirectory }) return false
        val expectedByName = tx.directoryMembers.associateBy { it.name }
        if (live.map { it.name }.toSet() != expectedByName.keys) return false
        for (entry in live) {
            coroutineContext.ensureActive()
            val expected = expectedByName[entry.name] ?: return false
            if (expected.sizeBytes != null && entry.sizeBytes != null && expected.sizeBytes != entry.sizeBytes) {
                return false
            }
            if (hashFile(provider, entry) != expected.sha256) return false
        }
        return true
    }

    private suspend fun collectDirectoryProofs(
        provider: WritableStorageProvider,
        directory: BrowserLocation,
    ): List<Step4MemberProof> {
        val entries = provider.listChildren(directory).sortedBy { it.name }
        if (entries.any { it.isDirectory }) {
            throw IllegalStateException("Transactional directory proof currently requires direct file members")
        }
        return entries.map { entry ->
            coroutineContext.ensureActive()
            Step4MemberProof(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                sha256 = hashFile(provider, entry),
            )
        }
    }

    private suspend fun hashFile(provider: WritableStorageProvider, entry: FileEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        provider.openInputStream(entry.reference).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private suspend fun sameDestinationSnapshot(
        provider: WritableStorageProvider,
        expected: FileEntry,
        current: FileEntry,
        expectedMutationIdentity: String?,
    ): Boolean {
        if (expectedMutationIdentity != null) {
            val currentIdentity = runCatching { provider.mutationIdentity(current.reference) }.getOrNull()
            return currentIdentity != null && currentIdentity == expectedMutationIdentity
        }
        val sameReference = expected.reference.providerId == current.reference.providerId &&
            (
                expected.reference.opaqueId == current.reference.opaqueId ||
                    (expected.reference.uri != null && expected.reference.uri == current.reference.uri) ||
                    (expected.reference.path != null && expected.reference.path == current.reference.path)
                )
        if (!sameReference) return false
        if (expected.sizeBytes != null && current.sizeBytes != null && expected.sizeBytes != current.sizeBytes) return false
        if (
            expected.modifiedAtMillis != null &&
            current.modifiedAtMillis != null &&
            expected.modifiedAtMillis != current.modifiedAtMillis
        ) return false
        return true
    }

    private suspend fun discardStagingAfterFailure(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        staged: FileEntry,
    ) {
        if (cleanupStaged(provider, tx, staged)) journal.remove(tx.id)
    }

    private suspend fun discardUncommitted(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        staged: FileEntry,
    ) {
        if (!cleanupStaged(provider, tx, staged)) {
            throw IllegalStateException("Hidden staged output could not be cleaned; recovery record retained")
        }
        journal.remove(tx.id)
    }

    private suspend fun cleanupUncommittedDirectoryIfSafe(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        staged: FileEntry,
    ) {
        if (tx.phase !in setOf(Step4WritePhase.STAGING, Step4WritePhase.STAGED)) return
        if (cleanupStaged(provider, tx, staged)) journal.remove(tx.id)
    }

    private suspend fun cleanupStaged(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        staged: FileEntry,
    ): Boolean = when (tx.outputKind) {
        Step4OutputKind.FILE -> runCatching {
            provider.delete(staged.scoped(tx.parent)) ||
                provider.findChild(tx.parent, staged.name) == null
        }.getOrDefault(false)
        Step4OutputKind.DIRECTORY -> deleteTree(provider, staged, tx.parent)
    }

    private suspend fun deleteTree(
        provider: WritableStorageProvider,
        entry: FileEntry,
        parent: BrowserLocation,
    ): Boolean {
        return runCatching {
            if (entry.isDirectory) {
                val location = entry.asLocation(parent)
                val children = provider.listChildren(location)
                for (child in children) {
                    coroutineContext.ensureActive()
                    if (!deleteTree(provider, child, location)) return@runCatching false
                }
            }
            provider.delete(entry.scoped(parent)) || provider.findChild(parent, entry.name) == null
        }.getOrDefault(false)
    }

    private suspend fun requireFileStaging(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
    ) {
        val capabilities = provider.capabilities(parent)
        if (
            StorageCapability.CREATE_FILE !in capabilities ||
            StorageCapability.RENAME !in capabilities ||
            StorageCapability.DELETE !in capabilities
        ) {
            throw IllegalStateException(
                "Provider cannot safely finalize staged output without exposing an incomplete final filename",
            )
        }
    }

    private suspend fun copy(
        input: InputStream,
        output: OutputStream,
        onProgress: suspend (Long) -> Unit,
    ) {
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count.toLong()
                onProgress(total)
            }
        }
    }

    private suspend fun uniqueName(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        requested: String,
    ): String {
        val dot = requested.lastIndexOf('.')
        val base = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$extension"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private suspend fun uniqueDirectoryName(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        requested: String,
    ): String {
        if (provider.findChild(parent, requested) == null) return requested
        var index = 1
        while (true) {
            val candidate = "$requested ($index)"
            if (provider.findChild(parent, candidate) == null) return candidate
            index++
        }
    }

    private suspend fun uniqueInternalName(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        base: String,
    ): String {
        var candidate = base
        var index = 0
        while (provider.findChild(parent, candidate) != null) {
            index++
            candidate = "$base-$index"
        }
        return candidate
    }

    private fun FileEntry.scoped(parent: BrowserLocation) =
        ScopedFileReference(reference, parent.rootReference, parent.storageId)

    private fun FileEntry.asLocation(parent: BrowserLocation) = BrowserLocation(
        providerId = reference.providerId,
        id = id,
        displayName = name,
        reference = reference.uri ?: reference.path ?: reference.opaqueId,
        rootReference = parent.rootReference,
        storageId = parent.storageId,
        readable = isReadable,
        writable = isWritable,
    )

    private class DigestCountingOutputStream(
        output: OutputStream,
        private val digest: MessageDigest,
    ) : FilterOutputStream(output) {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            out.write(value)
            digest.update(value.toByte())
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            digest.update(buffer, offset, length)
            count += length.toLong()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 256 * 1024
    }
}
