package com.zz.filemanager.core.search

import com.zz.filemanager.core.model.Breadcrumb
import com.zz.filemanager.core.model.BrowserLocation
import com.zz.filemanager.core.model.FileEntry
import com.zz.filemanager.core.model.FileEntryType
import com.zz.filemanager.core.model.FileReference
import com.zz.filemanager.core.storage.StorageProvider
import com.zz.filemanager.core.storage.StorageProviderRegistry
import com.zz.filemanager.core.storage.WritableStorageProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class SearchCoordinatorTest {
    @Test fun matcherSupportsCaseUnicodeExtensionTypeSizeDateAndHiddenFilters() {
        val entry = entry("Résumé 2026.PDF", FileEntryType.PDF, size = 2_000_000L, modified = 200L)
        assertTrue(SearchMatcher.matches(FileSearchQuery(text = "RÉSUMÉ"), entry, entry.name))
        assertTrue(SearchMatcher.matches(FileSearchQuery(extensions = setOf(".pdf")), entry, entry.name))
        assertTrue(SearchMatcher.matches(FileSearchQuery(typeFilter = SearchTypeFilter.DOCUMENTS), entry, entry.name))
        assertTrue(SearchMatcher.matches(FileSearchQuery(minSizeBytes = 1_000_000L, maxSizeBytes = 3_000_000L), entry, entry.name))
        assertTrue(SearchMatcher.matches(FileSearchQuery(modifiedAfter = 100L, modifiedBefore = 300L), entry, entry.name))
        assertFalse(SearchMatcher.matches(FileSearchQuery(maxSizeBytes = 100L), entry, entry.name))
        assertFalse(SearchMatcher.matches(FileSearchQuery(includeHidden = false), entry(".secret"), ".secret"))
    }

    @Test fun relevanceIsPredictable() {
        assertTrue(SearchMatcher.relevance("invoice", "invoice", "invoice") > SearchMatcher.relevance("invoice", "invoice-old.pdf", "invoice-old.pdf"))
        assertTrue(SearchMatcher.relevance("invoice", "my-invoice.pdf", "my-invoice.pdf") > SearchMatcher.relevance("invoice", "file.pdf", "folder/invoice/file.pdf"))
    }

    @Test fun recursiveTraversalStreamsBatchesAndExcludesReservedNamespace() = runTest {
        val root = location("root")
        val folder = entry("folder", FileEntryType.DIRECTORY)
        val provider = FakeProvider(mapOf(
            "root" to listOf(entry("invoice.pdf", FileEntryType.PDF), folder, entry(SearchCoordinator.RESERVED_RECYCLE_DIRECTORY, FileEntryType.DIRECTORY)),
            "root/folder" to listOf(entry("invoice-2.pdf", FileEntryType.PDF)),
        ))
        val coordinator = SearchCoordinator(FakeRegistry(provider), SearchRootSource { listOf(root) }, batchSize = 1)
        val updates = coordinator.search(FileSearchQuery("invoice", SearchScope.CURRENT_FOLDER_RECURSIVE), root).toList()
        val results = updates.filterIsInstance<SearchUpdate.Batch>().flatMap { it.results }
        assertEquals(listOf("invoice.pdf", "invoice-2.pdf"), results.map { it.name })
        assertEquals(2L, updates.filterIsInstance<SearchUpdate.Completed>().single().totalMatches)
    }

    @Test fun nonRecursiveSearchDoesNotDescend() = runTest {
        val root = location("root")
        val provider = FakeProvider(mapOf("root" to listOf(entry("folder", FileEntryType.DIRECTORY)), "root/folder" to listOf(entry("invoice.pdf"))))
        val updates = SearchCoordinator(FakeRegistry(provider), SearchRootSource { listOf(root) })
            .search(FileSearchQuery("invoice", SearchScope.CURRENT_FOLDER), root).toList()
        assertEquals(0L, updates.filterIsInstance<SearchUpdate.Completed>().single().totalMatches)
    }

    @Test fun oneProviderFailurePreservesOtherResults() = runTest {
        val good = FakeProvider(mapOf("good" to listOf(entry("invoice.pdf"))))
        val registry = object : StorageProviderRegistry {
            override fun providerFor(providerId: String): StorageProvider = if (providerId == "good") good else error("unavailable")
            override fun writableProviderFor(providerId: String): WritableStorageProvider? = null
        }
        val roots = SearchRootSource { listOf(location("good", "good"), location("bad", "bad")) }
        val updates = SearchCoordinator(registry, roots).search(FileSearchQuery("invoice", SearchScope.ALL_ACCESSIBLE_LOCATIONS), null).toList()
        assertEquals(1L, updates.filterIsInstance<SearchUpdate.Completed>().single().totalMatches)
        assertEquals(1, updates.filterIsInstance<SearchUpdate.Completed>().single().failures.size)
    }

    @Test fun hundredThousandLogicalEntriesRemainBatchBounded() = runTest {
        val root = location("root")
        val entries = List(100_000) { index -> entry("file-$index.txt") }
        val updates = SearchCoordinator(FakeRegistry(FakeProvider(mapOf("root" to entries))), SearchRootSource { listOf(root) }, batchSize = 128)
            .search(FileSearchQuery("file", SearchScope.CURRENT_FOLDER), root).toList()
        val batches = updates.filterIsInstance<SearchUpdate.Batch>()
        assertTrue(batches.all { it.results.size <= 128 })
        assertEquals(100_000L, updates.filterIsInstance<SearchUpdate.Completed>().single().totalMatches)
    }

    private class FakeRegistry(private val provider: StorageProvider) : StorageProviderRegistry {
        override fun providerFor(providerId: String): StorageProvider = provider
        override fun writableProviderFor(providerId: String): WritableStorageProvider? = null
    }
    private class FakeProvider(private val children: Map<String, List<FileEntry>>) : StorageProvider {
        override val id = "fake"
        override suspend fun listChildren(location: BrowserLocation): List<FileEntry> = children[location.reference].orEmpty()
        override suspend fun getMetadata(item: FileReference): FileEntry? = null
        override suspend fun openInputStream(item: FileReference) = ByteArrayInputStream(ByteArray(0))
        override suspend fun exists(item: FileReference) = true
        override suspend fun resolveParent(location: BrowserLocation): BrowserLocation? = null
        override suspend fun breadcrumbs(location: BrowserLocation): List<Breadcrumb> = emptyList()
    }
    companion object {
        private fun location(reference: String, provider: String = "fake") = BrowserLocation(provider, reference, reference, reference, reference.substringBefore('/'), reference.substringBefore('/'), true, true)
        private fun entry(name: String, type: FileEntryType = FileEntryType.TEXT, size: Long? = 10L, modified: Long? = 10L): FileEntry {
            val path = if (name == "folder") "root/folder" else "root/$name"
            return FileEntry("fake:$path", FileReference("fake", "fake:$path", path = path), name, name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }, null, type, size, modified, null, name.startsWith('.'), true, true, null, "root", null)
        }
    }
}
