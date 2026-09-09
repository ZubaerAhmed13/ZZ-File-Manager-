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
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class Step4WritePhase { STAGED, ATOMIC_COMMITTING, BACKUP_PLANNED, BACKED_UP, COMMITTING, COMMITTED }

data class Step4WriteTransaction(
    val id: String,
    val parent: BrowserLocation,
    val finalName: String,
    val stagedName: String,
    val backupName: String?,
    val expectedBytes: Long?,
    val phase: Step4WritePhase,
)

sealed interface SafeWriteResult {
    data class Written(val entry: FileEntry) : SafeWriteResult
    data class Skipped(val existing: FileEntry) : SafeWriteResult
}

/**
 * Small durable journal used by Step 4 staged writes. It is intentionally separate from the
 * Step 2 operation journal because archive extraction/editor saves are not FileOperation queue
 * items. Destructive rename boundaries are persisted so process death cannot make the safety
 * backup untraceable.
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
            })
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private fun decode(json: JSONObject): Step4WriteTransaction? = runCatching {
        Step4WriteTransaction(
            id = json.getString("id"),
            parent = BrowserLocationCodec.decode(json.getString("parent")) ?: return null,
            finalName = json.getString("finalName"),
            stagedName = json.getString("stagedName"),
            backupName = if (json.isNull("backupName")) null else json.getString("backupName"),
            expectedBytes = if (json.isNull("expectedBytes")) null else json.getLong("expectedBytes"),
            phase = Step4WritePhase.valueOf(json.getString("phase")),
        )
    }.getOrNull()

    private companion object { const val KEY = "transactions" }
}

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
    ): SafeWriteResult = withContext(Dispatchers.IO) {
        val provider = providers.writableProviderFor(parent.providerId) ?: throw IllegalStateException("Destination is read-only")
        val existing = provider.findChild(parent, requestedName)
        when {
            existing == null -> SafeWriteResult.Written(writeFresh(provider, parent, requestedName, mimeType, input, expectedBytes, onProgress))
            collisionPolicy == CollisionPolicy.SKIP -> SafeWriteResult.Skipped(existing)
            collisionPolicy == CollisionPolicy.KEEP_BOTH -> {
                val unique = uniqueName(provider, parent, requestedName)
                SafeWriteResult.Written(writeFresh(provider, parent, unique, mimeType, input, expectedBytes, onProgress))
            }
            collisionPolicy == CollisionPolicy.MERGE -> throw IllegalArgumentException("MERGE is only valid for directories")
            else -> SafeWriteResult.Written(replace(provider, parent, existing, requestedName, mimeType, input, expectedBytes, onProgress))
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
            val backup = tx.backupName?.let { runCatching { provider.findChild(tx.parent, it) }.getOrNull() }
            val finalVerified = final != null && (tx.expectedBytes == null || final.sizeBytes == tx.expectedBytes)
            when (tx.phase) {
                Step4WritePhase.STAGED -> {
                    // No destructive boundary occurred yet. The original final remains authoritative.
                    staged?.let { runCatching { provider.delete(it.scoped(tx.parent)) } }
                    journal.remove(tx.id)
                }
                Step4WritePhase.ATOMIC_COMMITTING -> when {
                    staged == null && finalVerified -> journal.remove(tx.id)
                    staged != null && final != null -> {
                        runCatching { provider.delete(staged.scoped(tx.parent)) }
                        journal.remove(tx.id)
                    }
                    else -> unresolved += tx.id
                }
                Step4WritePhase.BACKUP_PLANNED -> when {
                    backup == null && final != null -> {
                        staged?.let { runCatching { provider.delete(it.scoped(tx.parent)) } }
                        journal.remove(tx.id)
                    }
                    backup != null && final == null -> restoreBackup(provider, tx, backup, staged, unresolved)
                    backup != null && finalVerified && staged == null -> finishCommitted(provider, tx, backup, unresolved)
                    else -> unresolved += tx.id
                }
                Step4WritePhase.BACKED_UP, Step4WritePhase.COMMITTING -> when {
                    backup != null && final == null -> restoreBackup(provider, tx, backup, staged, unresolved)
                    backup != null && finalVerified && staged == null -> finishCommitted(provider, tx, backup, unresolved)
                    else -> unresolved += tx.id
                }
                Step4WritePhase.COMMITTED -> when {
                    finalVerified && backup != null -> finishCommitted(provider, tx, backup, unresolved)
                    finalVerified && backup == null -> journal.remove(tx.id)
                    else -> unresolved += tx.id
                }
            }
        }
        unresolved
    }

    private suspend fun writeFresh(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        name: String,
        mimeType: String?,
        input: InputStream,
        expectedBytes: Long?,
        onProgress: suspend (Long) -> Unit,
    ): FileEntry {
        val created = provider.createFile(parent, name, mimeType)
        val scoped = created.scoped(parent)
        try {
            val copied = copy(input, provider.openOutputStream(scoped, truncate = true), onProgress)
            if (expectedBytes != null && copied != expectedBytes) throw IllegalStateException("Written byte count does not match source")
            val verified = provider.getMetadata(created.reference) ?: throw IllegalStateException("Written file disappeared")
            if (expectedBytes != null && verified.sizeBytes != expectedBytes) throw IllegalStateException("Written file size verification failed")
            return verified
        } catch (cancelled: CancellationException) {
            runCatching { provider.delete(scoped) }
            throw cancelled
        } catch (error: Throwable) {
            runCatching { provider.delete(scoped) }
            throw error
        }
    }

    private suspend fun replace(
        provider: WritableStorageProvider,
        parent: BrowserLocation,
        existing: FileEntry,
        finalName: String,
        mimeType: String?,
        input: InputStream,
        expectedBytes: Long?,
        onProgress: suspend (Long) -> Unit,
    ): FileEntry {
        val capabilities = provider.capabilities(parent)
        if (StorageCapability.CREATE_FILE !in capabilities || StorageCapability.RENAME !in capabilities || StorageCapability.DELETE !in capabilities) {
            throw IllegalStateException("Provider cannot safely replace this file; use Keep Both or Save As")
        }
        val id = UUID.randomUUID().toString()
        val stagedName = uniqueInternalName(provider, parent, ".zzstage-$id")
        val staged = provider.createFile(parent, stagedName, mimeType)
        val stagedRef = staged.scoped(parent)
        try {
            val copied = copy(input, provider.openOutputStream(stagedRef, truncate = true), onProgress)
            if (expectedBytes != null && copied != expectedBytes) throw IllegalStateException("Staged byte count does not match source")
            val stagedVerified = provider.getMetadata(staged.reference) ?: throw IllegalStateException("Staged file disappeared")
            if (expectedBytes != null && stagedVerified.sizeBytes != expectedBytes) throw IllegalStateException("Staged size verification failed")
            var tx = Step4WriteTransaction(id, parent, finalName, stagedName, null, expectedBytes, Step4WritePhase.STAGED)
            journal.put(tx)

            tx = tx.copy(phase = Step4WritePhase.ATOMIC_COMMITTING)
            journal.put(tx)
            val atomic = provider.replaceAtomically(stagedRef, existing.scoped(parent), finalName)
            if (atomic != null) {
                val verified = provider.getMetadata(atomic.reference) ?: throw IllegalStateException("Atomic replacement disappeared")
                if (expectedBytes != null && verified.sizeBytes != expectedBytes) throw IllegalStateException("Atomic replacement size verification failed")
                journal.remove(id)
                return verified
            }

            // replaceAtomically promises no mutation when returning null. Continue with a
            // reversible, journaled backup/commit sequence.
            val backupName = uniqueInternalName(provider, parent, ".zzbackup-$id")
            tx = tx.copy(phase = Step4WritePhase.BACKUP_PLANNED, backupName = backupName)
            journal.put(tx)
            val backup = provider.rename(existing.scoped(parent), backupName)
            tx = tx.copy(phase = Step4WritePhase.BACKED_UP)
            journal.put(tx)
            tx = tx.copy(phase = Step4WritePhase.COMMITTING)
            journal.put(tx)
            val committed = try {
                provider.rename(stagedRef, finalName)
            } catch (error: Throwable) {
                runCatching { provider.rename(backup.scoped(parent), finalName) }
                throw error
            }
            val verified = provider.getMetadata(committed.reference) ?: throw IllegalStateException("Replacement disappeared")
            if (expectedBytes != null && verified.sizeBytes != expectedBytes) {
                // Keep the backup and durable ledger: startup reconciliation will not delete
                // ambiguous data.
                throw IllegalStateException("Replacement verification failed; original backup retained")
            }
            tx = tx.copy(phase = Step4WritePhase.COMMITTED)
            journal.put(tx)
            if (!provider.delete(backup.scoped(parent))) throw IllegalStateException("Replacement committed but backup cleanup is pending")
            journal.remove(id)
            return verified
        } catch (cancelled: CancellationException) {
            if (journal.list().none { it.id == id }) runCatching { provider.delete(stagedRef) }
            throw cancelled
        } catch (error: Throwable) {
            if (journal.list().none { it.id == id }) runCatching { provider.delete(stagedRef) }
            throw error
        }
    }

    private suspend fun restoreBackup(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        backup: FileEntry,
        staged: FileEntry?,
        unresolved: MutableList<String>,
    ) {
        val restored = runCatching { provider.rename(backup.scoped(tx.parent), tx.finalName) }.getOrNull()
        if (restored == null) {
            unresolved += tx.id
            return
        }
        staged?.let { runCatching { provider.delete(it.scoped(tx.parent)) } }
        journal.remove(tx.id)
    }

    private suspend fun finishCommitted(
        provider: WritableStorageProvider,
        tx: Step4WriteTransaction,
        backup: FileEntry,
        unresolved: MutableList<String>,
    ) {
        if (runCatching { provider.delete(backup.scoped(tx.parent)) }.getOrDefault(false)) journal.remove(tx.id)
        else unresolved += tx.id
    }

    private suspend fun copy(input: InputStream, output: java.io.OutputStream, onProgress: suspend (Long) -> Unit): Long {
        input.use { source ->
            output.use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    destination.write(buffer, 0, count)
                    total += count.toLong()
                    onProgress(total)
                }
                destination.flush()
                return total
            }
        }
    }

    private suspend fun uniqueName(provider: WritableStorageProvider, parent: BrowserLocation, requested: String): String {
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

    private suspend fun uniqueInternalName(provider: WritableStorageProvider, parent: BrowserLocation, base: String): String {
        var candidate = base
        var index = 0
        while (provider.findChild(parent, candidate) != null) {
            index++
            candidate = "$base-$index"
        }
        return candidate
    }

    private fun FileEntry.scoped(parent: BrowserLocation) = ScopedFileReference(reference, parent.rootReference, parent.storageId)
}
