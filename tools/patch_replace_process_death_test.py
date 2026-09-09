from pathlib import Path

path = Path("app/src/test/java/com/zz/filemanager/core/operation/FileOperationSafetyRegressionTest.kt")
text = path.read_text()

anchor = """    @Test\n    fun providerWithoutRenameNeverCreatesVisiblePartialFinal() = runBlocking {\n"""
test = """    @Test\n    fun replaceProcessDeathAfterBackupMutationRestoresOldThenCompletesReplacement() = runBlocking {\n        val fixture = replaceFixture()\n        fixture.provider.renameFaults += RenameFault(\n            predicate = { from, to -> from == \"holiday.mp4\" && to.startsWith(\".zzreplace-backup-\") },\n            afterMutation = true,\n            throwable = CancellationException(\"simulated process death after destination-to-backup rename\"),\n        )\n\n        fixture.controller.resolveCollision(fixture.id, CollisionPolicy.REPLACE, applyToAll = false)\n        var interrupted = false\n        try {\n            fixture.engine.runAvailable()\n        } catch (_: CancellationException) {\n            interrupted = true\n        }\n\n        assertTrue(\"Failure injection must hit the Replace transaction window\", interrupted)\n        val journaled = fixture.store.get(fixture.id)\n        assertEquals(ReplacePhase.BACKUP_PLANNED, journaled?.items?.single()?.replacePhase)\n        assertFalse(fixture.provider.rawHas(\"/dest/holiday.mp4\"))\n        assertTrue(fixture.provider.rawHasHiddenTransferArtifacts())\n\n        fixture.provider.renameFaults.clear()\n        fixture.store.forceState(fixture.id, FileOperationState.INTERRUPTED)\n        fixture.controller.resume(fixture.id)\n        fixture.engine.runAvailable()\n\n        assertEquals(FileOperationState.COMPLETED, fixture.store.get(fixture.id)?.state)\n        assertArrayEquals(newHoliday, fixture.provider.rawBytes(\"/dest/holiday.mp4\"))\n        assertFalse(fixture.provider.rawHasHiddenTransferArtifacts())\n        assertEquals(ReplacePhase.NONE, fixture.store.get(fixture.id)?.items?.single()?.replacePhase)\n    }\n\n"""
assert text.count(anchor) == 1, "test insertion anchor changed"
text = text.replace(anchor, test + anchor, 1)
path.write_text(text)
print("Replace process-death regression inserted")
