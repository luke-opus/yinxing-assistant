#!/usr/bin/env python3
"""Reviewed installer. Filled with public application bytes by build-command.py.

Only the feedback release/unit/private data and an appended Caddy virtual host
are changed. Existing Family Atlas service/data and the default Caddy unit are
never restarted, reconfigured or opened. No credentials are embedded.
"""
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import shlex
import shutil
import signal
import sqlite3
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

PAYLOAD = "__PAYLOAD_BASE64__"
PACKAGE_SHA256 = "__PACKAGE_SHA256__"
EDGE = "family-atlas-edge.service"
CONFIG = Path("/etc/family-atlas/Caddyfile")
BASE = Path("/opt/xadskip-feedback")
DATA = Path("/var/lib/xadskip-feedback")
UNIT = Path("/etc/systemd/system/xadskip-feedback.service")
SERVICE = "xadskip-feedback.service"
DOMAIN = "feedback.xadskip.com"
IP = "8.217.11.144"
BEGIN = "# BEGIN XADSKIP FEEDBACK MANAGED VHOST"
END = "# END XADSKIP FEEDBACK MANAGED VHOST"
VHOST = """
# BEGIN XADSKIP FEEDBACK MANAGED VHOST
feedback.xadskip.com {
    tls {
        issuer acme {
            dir https://acme-v02.api.letsencrypt.org/directory
        }
    }
    encode zstd gzip
    header {
        X-Content-Type-Options nosniff
        Referrer-Policy no-referrer
        -Server
    }
    request_body {
        max_size 62914560
    }
    @feedback_api path /api/health /api/feedback /api/wishes
    handle @feedback_api {
        reverse_proxy 127.0.0.1:8768 {
            header_up X-Real-IP {remote_host}
            transport http {
                dial_timeout 5s
                response_header_timeout 200s
            }
        }
    }
    handle /api/* {
        respond 404
    }
    handle {
        root * /opt/xadskip-feedback/current/site
        @feedback_home path /
        rewrite @feedback_home /feedback.html
        file_server
    }
}
# END XADSKIP FEEDBACK MANAGED VHOST
"""
UNIT_TEXT = """[Unit]
Description=Yinxing Assistant private feedback inbox
After=network.target

[Service]
Type=simple
User=xadskip-feedback
Group=xadskip-feedback
WorkingDirectory=/opt/xadskip-feedback/current
Environment=XADSKIP_FEEDBACK_DATA=/var/lib/xadskip-feedback
ExecStart=/usr/bin/python3 /opt/xadskip-feedback/current/feedback_server.py serve
Restart=on-failure
RestartSec=3
UMask=0077
StateDirectory=xadskip-feedback
StateDirectoryMode=0700
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/xadskip-feedback
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=1G
TasksMax=32
TimeoutStopSec=15

[Install]
WantedBy=multi-user.target
"""


def run(args, check=True, timeout=30, **kwargs):
    result = subprocess.run(args, check=False, capture_output=True, text=True,
                            timeout=timeout, **kwargs)
    if check and result.returncode:
        raise RuntimeError(args[0] + " failed: " + result.stderr[-3000:])
    return result


def prop(name):
    return run(["systemctl", "show", EDGE, "--property=" + name, "--value"]).stdout.strip()


def health(url, host=None):
    command = ["curl", "--silent", "--show-error", "--max-time", "8", "--noproxy", "*",
               "--write-out", "\n%{http_code}"]
    if url == "http://127.0.0.1:3000/api/health":
        command += ["--header", "Host: familyatlas.cn"]
    if host:
        command += ["--resolve", host + ":443:127.0.0.1"]
    result = run(command + [url], check=False, timeout=10)
    body, _, status = result.stdout.rpartition("\n")
    return result.returncode == 0 and status == "200", body


def write_atomic(path, data, mode=0o644, uid=0, gid=0):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".xadskip-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as file:
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
            os.fchmod(file.fileno(), mode)
            os.fchown(file.fileno(), uid, gid)
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_DIRECTORY | os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def switch_current(target):
    temporary = BASE / (".current-" + uuid.uuid4().hex)
    try:
        temporary.symlink_to(target)
        os.replace(temporary, BASE / "current")
    finally:
        temporary.unlink(missing_ok=True)


def verify_synthetic():
    # A generated one-pixel PNG, no person/device/customer data.
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")
    boundary = "xadskip-deploy-" + PACKAGE_SHA256[:16]
    fields = {"issueType": "other", "appName": "Deployment verification (synthetic)",
              "description": "Synthetic image verifies private feedback storage; no personal data.",
              "consent": "true", "requestId": "deploy-" + PACKAGE_SHA256[:32]}
    chunks = []
    for key, value in fields.items():
        chunks.append(("--" + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n' + value + "\r\n").encode())
    chunks += [("--" + boundary + '\r\nContent-Disposition: form-data; name="attachments"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n').encode(), png,
               ("\r\n--" + boundary + "--\r\n").encode()]
    body = b"".join(chunks)
    request = urllib.request.Request("https://" + DOMAIN + "/api/feedback", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                 "Origin": "https://" + DOMAIN})
    with urllib.request.urlopen(request, timeout=30) as response:
        assert response.status in (200, 201), "Feedback submission failed"
        receipt = json.load(response)
    feedback_id = receipt.get("feedbackId", "")
    assert receipt.get("ok") is True and re.fullmatch("[a-f0-9]{32}", feedback_id)
    assert not any(key.lower().endswith("url") for key in receipt), "Private media URL exposed"
    with sqlite3.connect("file:" + str(DATA / "feedback.sqlite3") + "?mode=ro", uri=True) as db:
        row = db.execute("SELECT attachments_json FROM feedback WHERE id=?", (feedback_id,)).fetchone()
    attachments = json.loads(row[0])
    private_file = DATA / "attachments" / feedback_id / attachments[0]["filename"]
    assert hashlib.sha256(private_file.read_bytes()).hexdigest() == hashlib.sha256(png).hexdigest()
    assert stat.S_IMODE(private_file.stat().st_mode) == 0o600
    assert stat.S_IMODE(DATA.stat().st_mode) == 0o700
    denied = []
    for path in ("/api/feedback/" + feedback_id, "/attachments/" + feedback_id + "/" + private_file.name,
                 "/feedback.sqlite3", "/server/feedback_server.py"):
        try:
            urllib.request.urlopen("https://" + DOMAIN + path, timeout=10)
        except urllib.error.HTTPError as error:
            assert error.code == 404, (path, error.code)
            denied.append(path)
        else:
            raise RuntimeError("Private/nonpublic path unexpectedly readable: " + path)
    return {"feedbackId": feedback_id, "privateFileMode": "0600", "privateDirectoryMode": "0700",
            "privateReadTests": len(denied), "syntheticOnly": True}


def verify_wishes():
    assert health("https://" + DOMAIN + "/wishes.html")[0], "Wish page unavailable"
    boundary = "xadskip-wish-" + PACKAGE_SHA256[:16]
    fields = {"description": "Synthetic deployment verification; no personal data. Do not publish.",
              "consent": "true", "requestId": str(uuid.UUID(PACKAGE_SHA256[:32]))}
    body = b"".join(("--" + boundary + '\r\nContent-Disposition: form-data; name="' + key +
                     '"\r\n\r\n' + value + "\r\n").encode() for key, value in fields.items())
    body += ("--" + boundary + "--\r\n").encode()
    request = urllib.request.Request("https://" + DOMAIN + "/api/wishes", data=body,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                 "Origin": "https://" + DOMAIN})
    with urllib.request.urlopen(request, timeout=30) as response:
        assert response.status in (200, 201)
        receipt = json.load(response)
    wish_id = receipt.get("wishId", "")
    assert receipt.get("ok") is True and re.fullmatch("[a-f0-9]{32}", wish_id)
    assert receipt.get("status") == "pending_review", "New wishes must not publish automatically"
    with urllib.request.urlopen("https://" + DOMAIN + "/api/wishes?limit=20", timeout=15) as response:
        public = json.load(response)
    assert public.get("ok") is True and isinstance(public.get("items"), list)
    assert wish_id not in json.dumps(public), "Pending private wish exposed publicly"
    return {"wishId": wish_id, "wishPageHealth": 200, "pendingWishHidden": True}


def has_saved_wishes():
    database = DATA / "feedback.sqlite3"
    if not database.exists():
        return False
    try:
        with sqlite3.connect("file:" + str(database) + "?mode=ro", uri=True) as db:
            exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='wishes'").fetchone()
            return bool(exists and db.execute("SELECT 1 FROM wishes LIMIT 1").fetchone())
    except sqlite3.Error:
        # A legacy feedback-only binary may delete unrecognized wish media on
        # startup. If the database cannot be checked, never start that binary.
        return True


def main():
    assert os.geteuid() == 0, "Run with sudo -n python3"
    assert tuple(__import__("sys").version_info[:2]) >= (3, 12)
    assert prop("ActiveState") == "active", "Existing family edge is not active"
    pid = int(prop("MainPID"))
    argv = Path(f"/proc/{pid}/cmdline").read_bytes().split(b"\0")
    assert b"run" in argv and b"--resume" not in argv and b"--config" in argv
    assert argv[argv.index(b"--config") + 1].decode() == str(CONFIG), "Unexpected active Caddyfile"
    assert b"--adapter" in argv and argv[argv.index(b"--adapter") + 1] == b"caddyfile"
    caddy = argv[0].decode()
    version = run([caddy, "version"]).stdout.strip()
    match = re.search(r"v?(\d+)\.(\d+)\.(\d+)", version)
    assert match and tuple(map(int, match.groups())) >= (2, 11, 0), "SIGUSR1 reload requires Caddy 2.11+"
    original = CONFIG.read_bytes()
    config_stat = CONFIG.stat()
    old_json = json.loads(run([caddy, "adapt", "--config", str(CONFIG), "--adapter", "caddyfile"]).stdout)
    assert old_json.get("admin", {}).get("disabled") is True, "Expected existing admin-off file workflow"
    family_app_pid = run(["systemctl", "show", "family-atlas.service", "--property=MainPID", "--value"]).stdout.strip()
    assert health("http://127.0.0.1:3000/api/health")[0], "Existing family app unhealthy"
    assert health("https://familyatlas.cn/api/health", "familyatlas.cn")[0], "Existing family TLS unhealthy"
    import socket
    addresses = {x[4][0] for x in socket.getaddrinfo(DOMAIN, 443, socket.AF_INET, socket.SOCK_STREAM)}
    assert addresses == {IP}, "Create feedback.xadskip.com A record before deployment: " + repr(addresses)
    raw = gzip.decompress(base64.b64decode(PAYLOAD))
    assert hashlib.sha256(raw).hexdigest() == PACKAGE_SHA256, "Payload checksum mismatch"
    files = json.loads(raw)
    assert {"feedback_server.py", "site/feedback.html", "site/feedback.css", "site/feedback.js", "site/icon.svg"} <= files.keys()
    assert all(name == "feedback_server.py" or re.fullmatch(r"site/[A-Za-z0-9][A-Za-z0-9_-]*\.(html|css|js|svg)", name)
               for name in files), "Only approved static text assets may be deployed"
    assert all(isinstance(content, str) for content in files.values())
    current = BASE / "current"
    assert not current.exists() or current.is_symlink(), "Unexpected existing deployment layout"
    old_target = os.readlink(current) if current.is_symlink() else None
    old_unit = UNIT.read_bytes() if UNIT.exists() else None
    old_running = run(["systemctl", "is-active", "--quiet", SERVICE], check=False).returncode == 0
    old_enabled = run(["systemctl", "is-enabled", "--quiet", SERVICE], check=False).returncode == 0
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()) + "-" + str(os.getpid())
    backup = Path("/var/backups/xadskip-feedback/deploy") / stamp
    backup.mkdir(parents=True, mode=0o700)
    os.chmod(backup, 0o700)
    write_atomic(backup / "Caddyfile.before", original, 0o600)
    if old_unit:
        write_atomic(backup / "feedback-unit.before", old_unit, 0o600)
    write_atomic(backup / "before.json", json.dumps({"caddyPid": pid, "familyAppPid": family_app_pid,
        "caddyConfigSha256": hashlib.sha256(original).hexdigest(), "oldTarget": old_target,
        "oldRunning": old_running, "oldEnabled": old_enabled}).encode(), 0o600)
    text = original.decode()
    if BEGIN in text:
        assert text.count(BEGIN) == text.count(END) == 1
        prefix, rest = text.split(BEGIN, 1)
        _, suffix = rest.split(END, 1)
        candidate = (prefix.rstrip() + "\n" + suffix.lstrip()).rstrip() + "\n" + VHOST
    else:
        assert DOMAIN not in text, "Existing unmanaged feedback vhost must be reviewed"
        candidate = text.rstrip() + "\n" + VHOST
    candidate_bytes = candidate.encode()
    staged_config = CONFIG.with_name("Caddyfile.xadskip-stage-" + stamp)
    write_atomic(staged_config, candidate_bytes, stat.S_IMODE(config_stat.st_mode), config_stat.st_uid, config_stat.st_gid)
    edge_user = prop("User")
    assert edge_user, "Expected dedicated Caddy service user"
    environment = [entry for entry in shlex.split(prop("Environment")) if entry.split("=", 1)[0] in ("HOME", "XDG_DATA_HOME", "XDG_CONFIG_HOME")]
    try:
        run(["runuser", "-u", edge_user, "--", "env"] + environment + [caddy, "validate", "--config", str(staged_config), "--adapter", "caddyfile"], timeout=45)
    finally:
        staged_config.unlink(missing_ok=True)
    release = BASE / "releases" / PACKAGE_SHA256[:20]
    release.mkdir(parents=True, mode=0o755, exist_ok=True)
    for parent in (BASE, BASE / "releases", release):
        os.chmod(parent, 0o755)
    for name, content in files.items():
        path = release / name
        path.parent.mkdir(mode=0o755, exist_ok=True)
        data = content.encode()
        if path.exists():
            assert path.read_bytes() == data, "Immutable release mismatch"
        else:
            write_atomic(path, data)
    try:
        account = pwd.getpwnam("xadskip-feedback")
    except KeyError:
        run(["useradd", "--system", "--user-group", "--home-dir", str(DATA), "--shell", "/usr/sbin/nologin", "xadskip-feedback"])
        account = pwd.getpwnam("xadskip-feedback")
    DATA.mkdir(mode=0o700, exist_ok=True)
    os.chown(DATA, account.pw_uid, account.pw_gid)
    os.chmod(DATA, 0o700)
    changed_config = False
    try:
        switch_current(release)
        write_atomic(UNIT, UNIT_TEXT.encode())
        run(["systemctl", "daemon-reload"])
        run(["systemctl", "enable", SERVICE])
        run(["systemctl", "restart", SERVICE])
        for _ in range(20):
            if health("http://127.0.0.1:8768/api/health")[0]:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Feedback backend did not become healthy")
        assert CONFIG.read_bytes() == original, "Caddy configuration changed during deployment"
        write_atomic(CONFIG, candidate_bytes, stat.S_IMODE(config_stat.st_mode), config_stat.st_uid, config_stat.st_gid)
        changed_config = True
        os.kill(pid, signal.SIGUSR1)
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            if health("https://" + DOMAIN + "/api/health", DOMAIN)[0]:
                break
            time.sleep(2)
        else:
            raise RuntimeError("Feedback TLS route unavailable; restoring prior Caddyfile")
        assert int(prop("MainPID")) == pid, "Existing Caddy process changed unexpectedly"
        assert run(["systemctl", "show", "family-atlas.service", "--property=MainPID", "--value"]).stdout.strip() == family_app_pid
        assert health("http://127.0.0.1:3000/api/health")[0]
        assert health("https://familyatlas.cn/api/health", "familyatlas.cn")[0]
        assert health("https://" + DOMAIN + "/")[0]
        receipt = {**verify_synthetic(), **verify_wishes()}
        result = {"ok": True, "url": "https://" + DOMAIN + "/", "releaseSha256": PACKAGE_SHA256,
            "backup": str(backup), "caddyPidUnchanged": pid, "familyAppPidUnchanged": family_app_pid,
            "familyLocalHealth": 200, "familyTlsHealth": 200, "feedbackHealth": 200, **receipt}
        write_atomic(backup / "result.json", json.dumps(result, ensure_ascii=False, indent=2).encode(), 0o600)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except BaseException:
        if changed_config:
            if CONFIG.read_bytes() != candidate_bytes:
                raise RuntimeError("Concurrent Caddy edit detected; automated rollback stopped; backup=" + str(backup))
            write_atomic(CONFIG, original, stat.S_IMODE(config_stat.st_mode), config_stat.st_uid, config_stat.st_gid)
            if int(prop("MainPID")) == pid:
                os.kill(pid, signal.SIGUSR1)
        # Wishes introduce private attachment ownership that legacy feedback
        # recovery does not understand. Once a wish is saved, keep this new
        # loopback-only backend even if the public Caddy change is rolled back.
        retain_new_backend = has_saved_wishes()
        if not retain_new_backend:
            run(["systemctl", "stop", SERVICE], check=False)
            if old_target:
                switch_current(old_target)
            elif current.is_symlink():
                current.unlink()
            if not old_enabled:
                run(["systemctl", "disable", SERVICE], check=False)
            if old_unit is not None:
                write_atomic(UNIT, old_unit)
            elif UNIT.exists():
                UNIT.rename(backup / "feedback-unit.failed")
            run(["systemctl", "daemon-reload"])
            if old_running:
                run(["systemctl", "start", SERVICE], check=False)
        print(json.dumps({"ok": False, "rolledBackCaddy": changed_config, "backup": str(backup),
            "familyLocalHealthy": health("http://127.0.0.1:3000/api/health")[0],
            "familyTlsHealthy": health("https://familyatlas.cn/api/health", "familyatlas.cn")[0],
            "privateFeedbackDataRetained": True,
            "newLoopbackBackendRetainedToProtectWishes": retain_new_backend}), flush=True)
        raise


main()
