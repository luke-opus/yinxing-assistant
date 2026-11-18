#!/usr/bin/env python3
"""Package only approved public application source, with no cloud credentials."""
import base64
import gzip
import hashlib
import json
from pathlib import Path
import re
import shlex

root = Path(__file__).resolve().parents[1]
# Support the working bundle and the public repository layout with the same
# reviewed script. Both include only source bytes, never server-side data.
backend = root / "server/feedback_server.py"
site = root / "site"
if not backend.is_file():
    backend = root / "feedback_server.py"
    site = root.parents[1] / "web/feedback"
assert backend.is_file() and site.is_dir(), "Feedback source tree not found"
files = {"feedback_server.py": backend}
for path in sorted(site.iterdir()):
    # Publish only reviewed static text assets, never backups, private media,
    # symlinks, hidden files or server configuration accidentally left here.
    if path.suffix in (".html", ".css", ".js", ".svg"):
        assert not path.is_symlink() and path.is_file(), path.name
        assert re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*\.(html|css|js|svg)", path.name), path.name
        files["site/" + path.name] = path
assert {"site/feedback.html", "site/feedback.css", "site/feedback.js", "site/icon.svg"} <= files.keys()
snapshots = {name: path.read_bytes() for name, path in sorted(files.items())}
payload = json.dumps({name: data.decode("utf-8") for name, data in snapshots.items()},
                     ensure_ascii=False, separators=(",", ":")).encode()
package_hash = hashlib.sha256(payload).hexdigest()
script = (root / "deploy/install-feedback.py").read_text()
script = script.replace("__PAYLOAD_BASE64__", base64.b64encode(gzip.compress(payload, mtime=0)).decode())
script = script.replace("__PACKAGE_SHA256__", package_hash)
compile(script, "install-feedback.py", "exec")
packed = base64.b64encode(gzip.compress(script.encode(), mtime=0)).decode()
python = "import base64,gzip;exec(compile(gzip.decompress(base64.b64decode(" + repr(packed) + ")), '<reviewed-feedback-deploy>', 'exec'))"
command = "sudo -n /usr/bin/python3 -c " + shlex.quote(python)
(root / "deploy/deploy-command.txt").write_text(command + "\n")
(root / "deploy/deploy-expanded.py").write_text(script)
manifest = {"packageSha256": package_hash, "commandBytes": len(command.encode()),
            "commandSha256": hashlib.sha256(command.encode()).hexdigest(),
            "expandedScriptSha256": hashlib.sha256(script.encode()).hexdigest(),
            "files": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                      for name, data in snapshots.items()}}
(root / "deploy/package-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest, indent=2))
