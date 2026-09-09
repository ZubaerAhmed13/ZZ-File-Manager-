from pathlib import Path

patch_path = Path("tools/step2_final_invariant_patch.py")
source = patch_path.read_text()
old = '    "    private suspend fun cancelOperation(\\n",\n'
new = '    "    private suspend fun failOperation(input: FileOperation, failure: OperationFailure): FileOperation {\\n",\n'
if source.count(old) != 1:
    raise SystemExit(f"stale engine end marker count: {source.count(old)}")
source = source.replace(old, new)
exec(compile(source, str(patch_path), "exec"), {"__name__": "__main__"})
