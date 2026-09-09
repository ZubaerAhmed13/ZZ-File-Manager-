from pathlib import Path

path = Path("app/src/main/java/com/zz/filemanager/core/operation/ReplaceTransactionCoordinator.kt")
text = path.read_text()
original = text

import_anchor = "import com.zz.filemanager.core.storage.WritableStorageProvider\nimport java.io.IOException\n"
import_replacement = "import com.zz.filemanager.core.storage.WritableStorageProvider\nimport kotlinx.coroutines.CancellationException\nimport java.io.IOException\n"
assert text.count(import_anchor) == 1, "import anchor changed"
text = text.replace(import_anchor, import_replacement, 1)

backup_old = """        val backup = try {\n            provider.rename(existingRef, backupName)\n        } catch (error: Throwable) {\n            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)\n        }\n"""
backup_new = """        val backup = try {\n            provider.rename(existingRef, backupName)\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (error: Throwable) {\n            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)\n        }\n"""
assert text.count(backup_old) == 1, "backup rename catch anchor changed"
text = text.replace(backup_old, backup_new, 1)

commit_old = """        val committed = try {\n            provider.rename(staged, finalName)\n        } catch (error: Throwable) {\n            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)\n        }\n"""
commit_new = """        val committed = try {\n            provider.rename(staged, finalName)\n        } catch (cancelled: CancellationException) {\n            throw cancelled\n        } catch (error: Throwable) {\n            return recoverAfterMutationError(currentOperation, currentItem, provider, parent, error)\n        }\n"""
assert text.count(commit_old) == 1, "commit rename catch anchor changed"
text = text.replace(commit_old, commit_new, 1)

assert text != original, "patch made no changes"
path.write_text(text)
print("Replace cancellation safety patch applied")
