from pathlib import Path

engine = Path("app/src/main/java/com/zz/filemanager/core/operation/FileOperationEngine.kt")
text = engine.read_text()

cap_old = """        val capabilities = destinationProvider.capabilities(parent)\n        val safeFinalizationAvailable = StorageCapability.RENAME in capabilities\n        var finalName = leafName(item.destinationRelativePath)\n"""
cap_new = """        val capabilities = destinationProvider.capabilities(parent)\n        val safeFinalizationAvailable = StorageCapability.RENAME in capabilities\n        val safeReplaceAvailable =\n            StorageCapability.ATOMIC_RENAME in capabilities ||\n                (StorageCapability.RENAME in capabilities && StorageCapability.DELETE in capabilities)\n        var finalName = leafName(item.destinationRelativePath)\n"""
assert text.count(cap_old) == 1, "capability anchor changed"
text = text.replace(cap_old, cap_new, 1)

collision_old = """            if (!safeFinalizationAvailable) {\n                collision = collision.copy(\n                    allowedPolicies = collision.allowedPolicies - setOf(CollisionPolicy.REPLACE, CollisionPolicy.KEEP_BOTH),\n                )\n            }\n"""
collision_new = """            collision = when {\n                !safeFinalizationAvailable -> collision.copy(\n                    allowedPolicies = collision.allowedPolicies - setOf(CollisionPolicy.REPLACE, CollisionPolicy.KEEP_BOTH),\n                )\n                !safeReplaceAvailable -> collision.copy(\n                    allowedPolicies = collision.allowedPolicies - CollisionPolicy.REPLACE,\n                )\n                else -> collision\n            }\n"""
assert text.count(collision_old) == 1, "collision capability anchor changed"
text = text.replace(collision_old, collision_new, 1)
engine.write_text(text)

# Add a regression proving Replace is not presented for rename-only providers.
test_path = Path("app/src/test/java/com/zz/filemanager/core/operation/FileOperationSafetyRegressionTest.kt")
test_text = test_path.read_text()
anchor = """    @Test\n    fun providerWithoutRenameNeverCreatesVisiblePartialFinal() = runBlocking {\n"""
new_test = """    @Test\n    fun renameWithoutDeleteDoesNotOfferUnsafeReplace() = runBlocking {\n        val provider = SafetyProvider().apply {\n            directory(\"/src\")\n            directory(\"/dest\")\n            file(\"/src/holiday.mp4\", newHoliday)\n            file(\"/dest/holiday.mp4\", oldHoliday)\n            advertisedCapabilities = setOf(\n                StorageCapability.READ,\n                StorageCapability.WRITE,\n                StorageCapability.CREATE_FILE,\n                StorageCapability.CREATE_DIRECTORY,\n                StorageCapability.RENAME,\n            )\n        }\n        val store = SafetyOperationStore()\n        val controller = FileOperationController(store, OperationExecutionHost { }) { 1L }\n        val engine = FileOperationEngine(store, provider, now = { 1L }, bufferSize = 16, progressIntervalMillis = 1L)\n        val id = controller.enqueueCopy(listOf(provider.source(\"/src/holiday.mp4\")), provider.location(\"/dest\"))\n\n        engine.runAvailable()\n\n        val collision = store.get(id)?.pendingCollision\n        assertEquals(FileOperationState.WAITING_FOR_USER, store.get(id)?.state)\n        assertTrue(collision != null)\n        assertFalse(CollisionPolicy.REPLACE in collision!!.allowedPolicies)\n        assertTrue(CollisionPolicy.KEEP_BOTH in collision.allowedPolicies)\n        assertArrayEquals(oldHoliday, provider.rawBytes(\"/dest/holiday.mp4\"))\n        assertFalse(provider.rawHasHiddenTransferArtifacts())\n    }\n\n"""
assert test_text.count(anchor) == 1, "test anchor changed"
test_text = test_text.replace(anchor, new_test + anchor, 1)
test_path.write_text(test_text)
print("Safe Replace capability policy and regression applied")
