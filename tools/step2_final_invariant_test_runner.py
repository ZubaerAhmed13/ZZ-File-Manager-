from pathlib import Path

patch_path = Path("tools/step2_final_invariant_test_patch.py")
source = patch_path.read_text()
old_guard = '''if text.count(old_size) != 1:
    raise SystemExit(f"entry size marker count: {text.count(old_size)}")
'''
new_guard = '''if text.count(old_size) < 1:
    raise SystemExit("entry size marker missing")
'''
old_replace = 'text = text.replace(old_size, new_size)\n'
new_replace = '''index = text.rfind(old_size)
text = text[:index] + new_size + text[index + len(old_size):]
'''
if source.count(old_guard) != 1:
    raise SystemExit(f"size guard patch count: {source.count(old_guard)}")
if source.count(old_replace) != 1:
    raise SystemExit(f"size replacement patch count: {source.count(old_replace)}")
source = source.replace(old_guard, new_guard).replace(old_replace, new_replace)
exec(compile(source, str(patch_path), "exec"), {"__name__": "__main__"})
