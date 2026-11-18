#!/usr/bin/env python3
"""Split the already-reviewed command for Aliyun's command content limit."""
import ast
import base64
import gzip
import hashlib
import json
from pathlib import Path
import shlex

root = Path(__file__).resolve().parent
original = (root / "deploy-command.txt").read_text().strip()
arguments = shlex.split(original)
assert arguments[:4] == ["sudo", "-n", "/usr/bin/python3", "-c"]
calls = [node for node in ast.walk(ast.parse(arguments[4]))
         if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
         and node.func.attr == "b64decode"]
assert len(calls) == 1
encoded = ast.literal_eval(calls[0].args[0])
compressed = base64.b64decode(encoded, validate=True)
expanded = gzip.decompress(compressed)
assert expanded == (root / "deploy-expanded.py").read_bytes()
compressed_hash = hashlib.sha256(compressed).hexdigest()
expanded_hash = hashlib.sha256(expanded).hexdigest()
split = (len(encoded) // 8) * 4
first, second = encoded[:split], encoded[split:]
first_bytes = base64.b64decode(first, validate=True)
first_hash = hashlib.sha256(first_bytes).hexdigest()
temporary = "/var/tmp/xadskip-feedback-deploy-" + compressed_hash[:24] + ".part1"

first_logic = '''assert os.geteuid() == 0
try:
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
except FileExistsError:
    fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as file:
        st = os.fstat(file.fileno())
        assert stat.S_ISREG(st.st_mode) and st.st_uid == 0 and st.st_nlink == 1 and stat.S_IMODE(st.st_mode) == 0o600
        assert file.read(len(d) + 1) == d, "Unexpected existing deployment fragment"
else:
    with os.fdopen(fd, "wb") as file:
        os.fchmod(file.fileno(), 0o600)
        file.write(d)
        file.flush()
        os.fsync(file.fileno())
print("DEPLOY_FRAGMENT_1_READY", len(d), hashlib.sha256(d).hexdigest(), flush=True)
'''
first_python = ("import os,stat,base64,hashlib;p=" + repr(temporary)
                + ";d=base64.b64decode(" + repr(first) + ",validate=True);exec(" + repr(first_logic) + ")")

second_logic = '''assert os.geteuid() == 0
fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW)
with os.fdopen(fd, "rb") as file:
    st = os.fstat(file.fileno())
    assert stat.S_ISREG(st.st_mode) and st.st_uid == 0 and st.st_nlink == 1 and stat.S_IMODE(st.st_mode) == 0o600
    assert st.st_size == first_size
    first = file.read(first_size + 1)
assert hashlib.sha256(first).hexdigest() == first_hash, "Fragment checksum mismatch"
packed = first + base64.b64decode(second, validate=True)
assert hashlib.sha256(packed).hexdigest() == compressed_hash, "Combined package checksum mismatch"
script = gzip.decompress(packed)
assert hashlib.sha256(script).hexdigest() == expanded_hash, "Expanded script checksum mismatch"
print("DEPLOY_SCRIPT_VERIFIED", expanded_hash, flush=True)
exec(compile(script, "<reviewed-feedback-deploy>", "exec"))
os.unlink(p)
'''
second_python = ("import os,stat,base64,gzip,hashlib;p=" + repr(temporary)
                 + ";first_size=" + str(len(first_bytes)) + ";first_hash=" + repr(first_hash)
                 + ";compressed_hash=" + repr(compressed_hash) + ";expanded_hash=" + repr(expanded_hash)
                 + ";second=" + repr(second) + ";exec(" + repr(second_logic) + ")")

manifest = {"temporaryPath": temporary, "expandedScriptSha256": expanded_hash,
            "compressedSha256": compressed_hash, "commands": []}
for index, python in enumerate((first_python, second_python), 1):
    compile(python, "split-command-" + str(index), "exec")
    command = "sudo -n /usr/bin/python3 -c " + shlex.quote(python)
    assert "\n" not in command and len(command.encode()) < 25000
    path = root / ("deploy-command-" + str(index) + ".txt")
    path.write_text(command + "\n")
    manifest["commands"].append({"path": str(path), "bytes": len(command.encode()),
                                  "sha256": hashlib.sha256(command.encode()).hexdigest()})
(root / "split-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
