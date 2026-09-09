from pathlib import Path

path = Path("app/src/test/java/com/zz/filemanager/core/operation/FileOperationSafetyRegressionTest.kt")
text = path.read_text()

insert_marker = "    private suspend fun replaceFixture(): ReplaceFixture {\n"
if text.count(insert_marker) != 1:
    raise SystemExit(f"test insertion marker count: {text.count(insert_marker)}")

tests = '''    @Test
    fun replacePostCommitVerificationFailureNeverReportsCompleted() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.metadataSizeOverride = { path, actual ->
            if (path == "/dest/holiday.mp4" && actual == newHoliday.size.toLong()) actual + 1L else actual
        }

        fixture.resolveReplaceAndRun()

        val interrupted = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.INTERRUPTED, interrupted?.state)
        assertFalse(interrupted?.state == FileOperationState.COMPLETED)
        assertEquals(null, interrupted?.completedAtMillis)
        assertEquals(ReplacePhase.COMMITTING, interrupted?.items?.single()?.replacePhase)
        assertTrue(interrupted?.items?.single()?.replaceBackupReference != null)
        assertTrue(interrupted?.items?.single()?.resultReference != null)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())

        fixture.provider.metadataSizeOverride = null
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        val completed = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.COMPLETED, completed?.state)
        assertEquals(ReplacePhase.NONE, completed?.items?.single()?.replacePhase)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

    @Test
    fun finishFromItemsCannotCompleteWithRunningItem() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/running.bin", byteArrayOf(1, 2, 3, 4))
        }
        val store = SafetyOperationStore()
        val source = provider.source("/src/running.bin")
        val item = OperationItem(
            id = "running:0",
            source = source,
            state = OperationItemState.RUNNING,
            destinationRelativePath = source.name,
        )
        val operation = FileOperation(
            id = "running",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = provider.location("/dest"),
            createdAtMillis = 1L,
        )
        store.enqueue(operation)
        val engine = FileOperationEngine(store, provider, now = { 2L })

        engine.finishFromItems(operation)

        val saved = store.get(operation.id)
        assertEquals(FileOperationState.INTERRUPTED, saved?.state)
        assertEquals(null, saved?.completedAtMillis)
        assertEquals(OperationItemState.RUNNING, saved?.items?.single()?.state)
        assertFalse(saved?.state == FileOperationState.COMPLETED)
    }

    @Test
    fun finishFromItemsCannotCompleteWithUnresolvedReplaceLedger() = runBlocking {
        val provider = SafetyProvider().apply {
            directory("/src")
            directory("/dest")
            file("/src/holiday.mp4", newHoliday)
        }
        val store = SafetyOperationStore()
        val source = provider.source("/src/holiday.mp4")
        val item = OperationItem(
            id = "replace-unresolved:0",
            source = source,
            state = OperationItemState.COMPLETED,
            destinationRelativePath = source.name,
            replacePhase = ReplacePhase.COMMITTING,
            replaceFinalName = "holiday.mp4",
            replaceBackupName = ".zzreplace-backup-test",
        )
        val operation = FileOperation(
            id = "replace-unresolved",
            type = FileOperationType.COPY,
            state = FileOperationState.RUNNING,
            items = listOf(item),
            destination = provider.location("/dest"),
            createdAtMillis = 1L,
        )
        store.enqueue(operation)
        val engine = FileOperationEngine(store, provider, now = { 2L })

        engine.finishFromItems(operation)

        val saved = store.get(operation.id)
        assertEquals(FileOperationState.INTERRUPTED, saved?.state)
        assertEquals(null, saved?.completedAtMillis)
        assertEquals(ReplacePhase.COMMITTING, saved?.items?.single()?.replacePhase)
        assertEquals(".zzreplace-backup-test", saved?.items?.single()?.replaceBackupName)
        assertFalse(saved?.state == FileOperationState.COMPLETED)
    }

    @Test
    fun replaceProcessDeathAfterStagedToFinalMutationBeforeCommittedJournalSave() = runBlocking {
        val fixture = replaceFixture()
        fixture.provider.renameFaults += RenameFault(
            predicate = { from, to -> from.startsWith(".zzpart-") && to == "holiday.mp4" },
            afterMutation = true,
            throwable = CancellationException("simulated process death after staged-to-final rename"),
        )

        fixture.controller.resolveCollision(fixture.id, CollisionPolicy.REPLACE, applyToAll = false)
        var interruptedByProcessDeath = false
        try {
            fixture.engine.runAvailable()
        } catch (_: CancellationException) {
            interruptedByProcessDeath = true
        }

        assertTrue("Failure injection must hit the staged-to-final mutation window", interruptedByProcessDeath)
        val journaled = fixture.store.get(fixture.id)
        assertEquals(ReplacePhase.COMMITTING, journaled?.items?.single()?.replacePhase)
        assertTrue(journaled?.items?.single()?.partialOutput != null)
        assertTrue(journaled?.items?.single()?.replaceBackupReference != null)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())

        fixture.provider.renameFaults.clear()
        fixture.store.forceState(fixture.id, FileOperationState.INTERRUPTED)
        fixture.controller.resume(fixture.id)
        fixture.engine.runAvailable()

        val completed = fixture.store.get(fixture.id)
        assertEquals(FileOperationState.COMPLETED, completed?.state)
        assertEquals(ReplacePhase.NONE, completed?.items?.single()?.replacePhase)
        assertArrayEquals(newHoliday, fixture.provider.rawBytes("/dest/holiday.mp4"))
        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())
    }

'''
text = text.replace(insert_marker, tests + insert_marker)

field_marker = "    var onFirstWrite: (() -> Unit)? = null\n"
if text.count(field_marker) != 1:
    raise SystemExit(f"provider field marker count: {text.count(field_marker)}")
text = text.replace(
    field_marker,
    field_marker + "    var metadataSizeOverride: ((String, Long) -> Long)? = null\n",
)

old_size = "        sizeBytes = if (node.directory) null else node.logicalSize ?: node.data.size.toLong(),\n"
if text.count(old_size) != 1:
    raise SystemExit(f"entry size marker count: {text.count(old_size)}")
new_size = '''        sizeBytes = if (node.directory) {
            null
        } else {
            val actualSize = node.logicalSize ?: node.data.size.toLong()
            metadataSizeOverride?.invoke(node.path, actualSize) ?: actualSize
        },
'''
text = text.replace(old_size, new_size)

path.write_text(text)
