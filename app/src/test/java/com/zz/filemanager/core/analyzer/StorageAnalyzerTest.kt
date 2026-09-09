package com.zz.filemanager.core.analyzer

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAnalyzerTest {
    @Test
    fun scansOneHundredThousandLogicalEntriesWithLongAccountingAndBoundedResults() = runTest {
        val provider = LargeLogicalProvider()
        val analyzer = StorageAnalyzer(Registry(provider))
        val result = analyzer.scan(provider.root, topLimit = 25, retainedEmptyLimit = 10)

        assertTrue(result.complete)
        assertEquals(100_000L, result.progress.filesScanned)
        assertEquals(101L, result.progress.directoriesScanned)
        assertEquals(40_000_000_000L, result.progress.bytesAccounted)
        assertTrue(result.progress.bytesAccounted > 32L * 1024L * 1024L * 1024L)
        assertEquals(25, result.largestFiles.size)
        assertTrue(result.largestFolders.size <= 25)
        assertEquals(40_000_000_000L, result.categoryBytes.getValue(StorageCategory.DOCUMENTS))
    }

    @Test
    fun sampledHashCollisionIsNotAcceptedAsDuplicateButExactCopiesAre() = runTest {
        val size = 300_000
        val exact = ByteArray(size) { index -> (index % 251).toByte() }
        val collisionA = ByteArray(size)
        val collisionB = ByteArray(size)
        collisionA[90_000] = 1
        collisionB[100_000] = 1

        val provider = ContentProvider(
            linkedMapOf(
                "exact-a.bin" to exact,
                "exact-b.bin" to exact.copyOf(),
                "sample-collision-a.bin" to collisionA,
                "sample-collision-b.bin" to collisionB,
            ),
        )
        val groups = StorageAnalyzer(Registry(provider)).findDuplicates(provider.root)

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().members.size)
        assertEquals(setOf("exact-a.bin", "exact-b.bin"), groups.single().members.map { it.entry.name }.toSet())
    }

    @Test
    fun scanHonorsCoroutineCancellation() = runTest {
        val provider = LargeLogicalProvider()
        val analyzer = StorageAnalyzer(Registry(provider))
        val job = launch { analyzer.scan(provider.root) }
        yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    private class Registry(private val provider: StorageProvider) : StorageProviderRegistry {
        override fun providerFor(providerId: String): StorageProvider {
            require(providerId == provider.id)
            return provider
        }

        override fun writableProviderFor(providerId: String): WritableStorageProvider? = null
    }

    private class LargeLogicalProvider : StorageProvider {
        override val id: String = "logical"
        val root = BrowserLocation(id, "root", "Logical root", "root", "root", "logical", readable = true, writable = false)

        override suspend fun listChildren(location: BrowserLocation): List<FileEntry> {
            yield()
            return if (location.reference == "root") {
                (0 until 100).map { directoryIndex -> directory("d$directoryIndex") }
            } else {
                val prefix = location.reference
                (0 until 1_000).map { fileIndex -> file("$prefix/f$fileIndex.txt", 400_000L, FileEntryType.TEXT) }
            }
        }

        override suspend fun getMetadata(item: FileReference): FileEntry? = null
        override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(ByteArray(0))
        override suspend fun exists(item: FileReference): Boolean = true
        override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
        override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()

        private fun directory(name: String) = FileEntry(
            id = name,
            reference = FileReference(id, name),
            name = name,
            extension = null,
            mimeType = null,
            type = FileEntryType.DIRECTORY,
            sizeBytes = null,
            modifiedAtMillis = null,
            createdAtMillis = null,
            isHidden = false,
            isReadable = true,
            isWritable = false,
            childCount = 1_000,
            storageId = "logical",
            thumbnailKey = null,
        )

        private fun file(path: String, size: Long, type: FileEntryType) = FileEntry(
            id = path,
            reference = FileReference(id, path),
            name = path.substringAfterLast('/'),
            extension = path.substringAfterLast('.', "").takeIf { it.isNotBlank() },
            mimeType = "text/plain",
            type = type,
            sizeBytes = size,
            modifiedAtMillis = null,
            createdAtMillis = null,
            isHidden = false,
            isReadable = true,
            isWritable = false,
            childCount = null,
            storageId = "logical",
            thumbnailKey = null,
        )
    }

    private class ContentProvider(private val contents: LinkedHashMap<String, ByteArray>) : StorageProvider {
        override val id: String = "content"
        val root = BrowserLocation(id, "root", "Duplicates", "root", "root", "content", readable = true, writable = false)

        override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = contents.map { (name, bytes) ->
            FileEntry(
                id = name,
                reference = FileReference(id, name),
                name = name,
                extension = "bin",
                mimeType = "application/octet-stream",
                type = FileEntryType.GENERIC,
                sizeBytes = bytes.size.toLong(),
                modifiedAtMillis = null,
                createdAtMillis = null,
                isHidden = false,
                isReadable = true,
                isWritable = false,
                childCount = null,
                storageId = "content",
                thumbnailKey = null,
            )
        }

        override suspend fun getMetadata(item: FileReference): FileEntry? = null
        override suspend fun openInputStream(item: FileReference): InputStream = ByteArrayInputStream(contents.getValue(item.opaqueId))
        override suspend fun exists(item: FileReference): Boolean = contents.containsKey(item.opaqueId)
        override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
        override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    }
}
