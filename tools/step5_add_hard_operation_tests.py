from pathlib import Path

path = Path("app/src/test/java/com/zz/filemanager/core/operation/FileOperationSafetyRegressionTest.kt")
text = path.read_text()

anchor = '''    private suspend fun replaceFixture(): ReplaceFixture {\n'''
tests = r'''    @Test
    fun remoteMoveNeverDeletesSourceBeforeDestinationProven() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/authoritative.bin", ByteArray(96) { it.toByte() })
            failWriteAfterBytes = 8
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueMove(listOf(provider.source("/src/authoritative.bin")), provider.location("/dest"))

        FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L).runAvailable()

        assertTrue(provider.rawHas("/src/authoritative.bin"))
        assertFalse(provider.rawHas("/dest/authoritative.bin"))
        assertEquals(FileOperationState.FAILED, store.get(id)?.state)
    }

    @Test
    fun downloadProcessDeathNeverExposesPartialFinal() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/remote")
            directory("/local")
            file("/remote/download.bin", ByteArray(128) { (it % 251).toByte() })
            onFirstWrite = { throw CancellationException("simulated process death") }
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/remote/download.bin")), provider.location("/local"))
        val engine = FileOperationEngine(store, provider, bufferSize = 16, progressIntervalMillis = 0L)

        var killed = false
        try { engine.runAvailable() } catch (_: CancellationException) { killed = true }
        assertTrue(killed)
        store.markHostExecutionInterrupted(100L)

        assertFalse(provider.rawHas("/local/download.bin"))
        assertTrue(provider.rawHasHiddenTransferArtifacts())
        assertEquals(FileOperationState.INTERRUPTED, store.get(id)?.state)
    }

    @Test
    fun uploadProcessDeathNeverExposesPartialRemoteFinal() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/local")
            directory("/remote")
            file("/local/upload.bin", ByteArray(128) { (it % 199).toByte() })
            onFirstWrite = { throw CancellationException("simulated process death") }
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueCopy(listOf(provider.source("/local/upload.bin")), provider.location("/remote"))
        val engine = FileOperationEngine(store, provider, bufferSize = 16, progressIntervalMillis = 0L)

        var killed = false
        try { engine.runAvailable() } catch (_: CancellationException) { killed = true }
        assertTrue(killed)
        store.markHostExecutionInterrupted(100L)

        assertFalse(provider.rawHas("/remote/upload.bin"))
        assertTrue(provider.rawHasHiddenTransferArtifacts())
        assertEquals(FileOperationState.INTERRUPTED, store.get(id)?.state)
    }

    @Test
    fun remoteReplacePreservesKnownGoodDestinationUntilCommitProof() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.failWriteAfterBytes = 8

        fixture.resolveReplaceAndRun()

        assertArrayEquals(oldHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertEquals(FileOperationState.FAILED, fixture.store.get(fixture.id)?.state)
    }

    @Test
    fun usbRemovalDuringMovePreservesSource() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/phone")
            directory("/usb")
            file("/phone/keep-me.bin", ByteArray(128) { it.toByte() })
            disappearAfterFirstWrite = true
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val id = controller.enqueueMove(listOf(provider.source("/phone/keep-me.bin")), provider.location("/usb"))

        FileOperationEngine(store, provider, bufferSize = 16, progressIntervalMillis = 0L).runAvailable()

        assertTrue(provider.rawHas("/phone/keep-me.bin"))
        assertFalse(provider.rawHas("/usb/keep-me.bin"))
        assertEquals(FileOperationState.FAILED, store.get(id)?.state)
    }

    @Test
    fun networkTimeoutDoesNotDeadlockOperationQueue() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/first.bin", byteArrayOf(1, 2, 3))
            file("/src/second.bin", byteArrayOf(4, 5, 6))
            failReadOnceWith = StorageAccessException.Timeout()
        }
        val store = SafetyOperationStore()
        val controller = FileOperationController(store, OperationExecutionHost {})
        val first = controller.enqueueCopy(listOf(provider.source("/src/first.bin")), provider.location("/dest"))
        val second = controller.enqueueCopy(listOf(provider.source("/src/second.bin")), provider.location("/dest"))

        FileOperationEngine(store, provider, bufferSize = 8, progressIntervalMillis = 0L).runAvailable()

        assertTrue(store.get(first)?.state?.isTerminal == true)
        assertEquals(FileOperationState.COMPLETED, store.get(second)?.state)
        assertTrue(provider.rawHas("/dest/second.bin"))
    }

'''
assert text.count(anchor) == 1, text.count(anchor)
text = text.replace(anchor, tests + anchor)

old = '''    var failReadAfterBytes: Int? = null\n    var disappearAfterFirstWrite: Boolean = false\n'''
new = '''    var failReadAfterBytes: Int? = null\n    var failReadOnceWith: Throwable? = null\n    var disappearAfterFirstWrite: Boolean = false\n'''
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)

old = '''    override suspend fun openInputStream(item: FileReference): InputStream {\n        ensureAvailable()\n        val node = node(item) ?: throw StorageAccessException.Unavailable()\n'''
new = '''    override suspend fun openInputStream(item: FileReference): InputStream {\n        ensureAvailable()\n        failReadOnceWith?.let { failure ->\n            failReadOnceWith = null\n            throw failure\n        }\n        val node = node(item) ?: throw StorageAccessException.Unavailable()\n'''
assert text.count(old) == 1, text.count(old)
text = text.replace(old, new)

path.write_text(text)
print("added hard operation tests")
