#!/usr/bin/env python3
"""Create a small, hash-pinned bootstrap for a reviewed public Git commit."""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("commit", help="Full commit SHA already pushed to the public repository")
args = parser.parse_args()
assert re.fullmatch(r"[a-f0-9]{40}", args.commit), "A full GitHub commit SHA is required"
root = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(root / "build-command.py")], check=True, capture_output=True)
manifest = json.loads((root / "package-manifest.json").read_text())
template = (root / "install-feedback.py").read_bytes()
expected = {name: {**item, "source": "services/feedback/feedback_server.py" if name == "feedback_server.py"
                   else "web/feedback/" + name.removeprefix("site/")}
            for name, item in manifest["files"].items()}
expected["installer"] = {"source": "services/feedback/deploy/install-feedback.py",
                          "bytes": len(template), "sha256": hashlib.sha256(template).hexdigest()}
settings = {"commit": args.commit, "expected": expected,
            "packageSha256": manifest["packageSha256"],
            "expandedScriptSha256": manifest["expandedScriptSha256"]}
bootstrap = '''import base64,gzip,hashlib,io,json,urllib.request,zipfile
SETTINGS = __SETTINGS__
commit = SETTINGS["commit"]
url = "https://codeload.github.com/luke-opus/yinxing-assistant/zip/" + commit
limit = 128 * 1024 * 1024
request = urllib.request.Request(url, headers={"User-Agent": "Yinxing-reviewed-deployment/1"})
with urllib.request.urlopen(request, timeout=60) as response:
    archive = response.read(limit + 1)
assert len(archive) <= limit, "Commit archive exceeds download limit"
prefix = "yinxing-assistant-" + commit + "/"
verified = {}
with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
    assert len(bundle.infolist()) < 100000, "Unexpected archive entry count"
    names = bundle.namelist()
    for name, expected in SETTINGS["expected"].items():
        source = prefix + expected["source"]
        assert names.count(source) == 1, "Required source missing or duplicated: " + source
        info = bundle.getinfo(source)
        assert not info.is_dir() and (info.external_attr >> 16) & 0o170000 != 0o120000
        assert info.file_size == expected["bytes"], "Source size mismatch: " + source
        data = bundle.read(info)
        assert hashlib.sha256(data).hexdigest() == expected["sha256"], "Source hash mismatch: " + source
        verified[name] = data.decode("utf-8")
template = verified.pop("installer")
payload = json.dumps(dict(sorted(verified.items())), ensure_ascii=False, separators=(",", ":")).encode()
assert hashlib.sha256(payload).hexdigest() == SETTINGS["packageSha256"], "Package hash mismatch"
packed = bytearray(gzip.compress(payload, compresslevel=9, mtime=0))
# Python 3.11/3.12 may put the host OS byte in gzip headers. The reviewed
# package uses the platform-independent header emitted by Python 3.9/3.13.
packed[9] = 255
script = template.replace("__PAYLOAD_BASE64__", base64.b64encode(packed).decode())
script = script.replace("__PACKAGE_SHA256__", SETTINGS["packageSha256"]).encode()
assert hashlib.sha256(script).hexdigest() == SETTINGS["expandedScriptSha256"], "Expanded installer hash mismatch"
print("GITHUB_SOURCES_AND_INSTALLER_VERIFIED", commit, SETTINGS["expandedScriptSha256"], flush=True)
exec(compile(script, "<reviewed-feedback-deploy>", "exec"))
'''.replace("__SETTINGS__", repr(settings))
compile(bootstrap, "github-bootstrap", "exec")
packed = base64.b64encode(gzip.compress(bootstrap.encode(), mtime=0)).decode()
python = "import base64,gzip;exec(compile(gzip.decompress(base64.b64decode(" + repr(packed) + ")), '<verified-github-bootstrap>', 'exec'))"
command = "sudo -n /usr/bin/python3 -c " + shlex.quote(python)
assert len(command.encode()) < 8000 and "\n" not in command
(root / "deploy-github-command.txt").write_text(command + "\n")
(root / "deploy-github-bootstrap.py").write_text(bootstrap)
result = {"commit": args.commit, "commandBytes": len(command.encode()),
          "commandSha256": hashlib.sha256(command.encode()).hexdigest(),
          "packageSha256": manifest["packageSha256"],
          "expandedScriptSha256": manifest["expandedScriptSha256"]}
(root / "github-command-manifest.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))
