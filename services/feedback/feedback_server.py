#!/usr/bin/env python3
"""Private XAdSkip feedback inbox. Python 3.12+, standard library only."""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import sqlite3
import threading
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from contextlib import contextmanager
from datetime import datetime, timezone
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

MIB = 1024 * 1024
TOTAL_LIMIT = 60 * MIB
IMAGE_LIMIT = 8 * MIB
VIDEO_LIMIT = 30 * MIB
TEXT_LIMITS = {"issueType": 20, "appName": 120, "appVersion": 80,
               "xadskipVersion": 80, "description": 5000, "email": 254,
               "consent": 10, "requestId": 80}
WISH_TEXT_LIMITS = {"description": 5000, "email": 254, "xadskipVersion": 80,
                    "consent": 10, "requestId": 80}
WISH_STATUSES = frozenset({"pending", "planned", "completed"})
ORIGINS = frozenset({"https://xadskip.com", "https://www.xadskip.com", "https://feedback.xadskip.com"})


class FeedbackError(Exception):
    def __init__(self, status: int, code: str, message: str):
        self.status, self.code, self.message = status, code, message
        super().__init__(message)


def fail(code: str, message: str, status: int = 400):
    raise FeedbackError(status, code, message)


@dataclass(frozen=True)
class Attachment:
    original_name: str
    media_type: str
    extension: str
    data: bytes = field(repr=False)

    @property
    def sha256(self):
        return hashlib.sha256(self.data).hexdigest()


@dataclass(frozen=True)
class Submission:
    fields: dict
    attachments: tuple[Attachment, ...]

    @property
    def fingerprint(self):
        values = {k: v for k, v in self.fields.items() if k != "requestId"}
        values["attachments"] = [{"name": a.original_name, "type": a.media_type,
                                  "bytes": len(a.data), "sha256": a.sha256}
                                 for a in self.attachments]
        return hashlib.sha256(json.dumps(values, ensure_ascii=False,
                                        sort_keys=True).encode()).hexdigest()


def detect_media(data: bytes) -> tuple[str, str]:
    # Container signatures and lengths are checked without decoding or executing media.
    if len(data) >= 4 and data.startswith(b"\xff\xd8\xff") and data.endswith(b"\xff\xd9"):
        return "image/jpeg", "jpg"
    if len(data) >= 45 and data.startswith(b"\x89PNG\r\n\x1a\n"):
        pos, first, idat = 8, True, False
        while pos + 12 <= len(data):
            length = int.from_bytes(data[pos:pos + 4], "big")
            kind = data[pos + 4:pos + 8]
            end = pos + 12 + length
            if end > len(data):
                break
            if first and (kind != b"IHDR" or length != 13):
                break
            if first and (not int.from_bytes(data[pos + 8:pos + 12], "big") or
                          not int.from_bytes(data[pos + 12:pos + 16], "big")):
                break
            first = False
            idat |= kind == b"IDAT"
            if kind == b"IEND":
                if length == 0 and end == len(data) and idat:
                    return "image/png", "png"
                break
            pos = end
    if (len(data) >= 20 and data[:4] == b"RIFF" and data[8:12] == b"WEBP"
            and int.from_bytes(data[4:8], "little") + 8 == len(data)
            and data[12:16] in (b"VP8 ", b"VP8L", b"VP8X")):
        return "image/webp", "webp"
    if len(data) >= 24 and data[4:8] == b"ftyp":
        pos, kinds, brands, box_count = 0, set(), set(), 0
        while pos + 8 <= len(data) and box_count < 10_000:
            box_count += 1
            size = int.from_bytes(data[pos:pos + 4], "big")
            kind = data[pos + 4:pos + 8]
            header = 8
            if size == 1:
                if pos + 16 > len(data):
                    break
                size = int.from_bytes(data[pos + 8:pos + 16], "big")
                header = 16
            elif size == 0:
                size = len(data) - pos
            if size < header or pos + size > len(data):
                break
            kinds.add(kind)
            if kind == b"ftyp" and size >= header + 8:
                payload = data[pos + header:pos + size]
                brands.add(payload[:4])
                brands.update(payload[i:i + 4] for i in range(8, len(payload), 4))
            pos += size
        mp4_brands = {b"isom", b"iso2", b"iso3", b"iso4", b"iso5", b"iso6",
                      b"mp41", b"mp42", b"avc1", b"dash", b"M4V ", b"MSNV"}
        if pos == len(data) and b"mdat" in kinds and b"moov" in kinds and brands & mp4_brands:
            return "video/mp4", "mp4"
    if data.startswith(b"\x1a\x45\xdf\xa3"):
        try:
            header_size, width = _vint(data, 4)
            end = 4 + width + header_size
            if end > min(len(data), 4096):
                raise ValueError()
            pos, doctype = 4 + width, None
            while pos < end:
                item_id, id_width = _vint(data, pos, keep_marker=True)
                length, length_width = _vint(data, pos + id_width)
                start = pos + id_width + length_width
                if start + length > end:
                    raise ValueError()
                if item_id == 0x4282:
                    doctype = data[start:start + length]
                pos = start + length
            if doctype == b"webm" and data[end:end + 4] == b"\x18\x53\x80\x67":
                return "video/webm", "webm"
        except (ValueError, IndexError):
            pass
    fail("invalid_media", "附件内容必须是真实的 JPEG、PNG、WebP 图片或 MP4、WebM 视频。")


def _vint(data: bytes, offset: int, keep_marker=False):
    first = data[offset]
    if first == 0:
        raise ValueError()
    width = 9 - first.bit_length()
    if width > 8 or offset + width > len(data):
        raise ValueError()
    value = int.from_bytes(data[offset:offset + width], "big")
    return (value if keep_marker else value & ((1 << (7 * width)) - 1)), width


def safe_original_name(value: str) -> str:
    name = value.replace("\\", "/").rsplit("/", 1)[-1]
    name = "".join(c for c in name if c.isalnum() or c in "._ -")[:120]
    return name.strip(" .") or "attachment"


def parse_submission(content_type: str, body: bytes, kind="feedback") -> Submission:
    if kind not in {"feedback", "wish"}:
        raise ValueError("unknown submission kind")
    text_limits = WISH_TEXT_LIMITS if kind == "wish" else TEXT_LIMITS
    if len(body) > TOTAL_LIMIT:
        fail("request_too_large", "本次上传总大小不能超过 60 MiB。", 413)
    if len(content_type) > 512 or not content_type.isascii() or "\r" in content_type or "\n" in content_type:
        fail("invalid_multipart", "上传格式不正确。")
    headers = b"MIME-Version: 1.0\r\nContent-Type: " + content_type.encode("ascii") + b"\r\n\r\n"
    envelope = BytesParser(policy=policy.default).parsebytes(headers)
    boundary = envelope.get_boundary()
    if (envelope.get_content_type() != "multipart/form-data" or not boundary or
            not boundary.isascii() or len(boundary) > 70 or not boundary.strip()):
        fail("invalid_multipart", "请使用 multipart/form-data 上传。")
    # Reject part-count bombs before creating MIME objects for attacker-controlled parts.
    if body.count(b"--" + boundary.encode("ascii")) > len(text_limits) + 5:
        fail("invalid_multipart", "上传字段或附件数量过多。")
    msg = BytesParser(policy=policy.default).parsebytes(headers + body)
    if (msg.get_content_type() != "multipart/form-data" or not msg.is_multipart()
            or msg.defects or not boundary or len(boundary) > 70):
        fail("invalid_multipart", "请使用 multipart/form-data 上传。")
    fields, attachments = {}, []
    for part in msg.iter_parts():
        if part.defects or part.is_multipart() or part.get_content_disposition() != "form-data":
            fail("invalid_multipart", "附件格式不正确。")
        name = part.get_param("name", header="content-disposition")
        payload = part.get_payload(decode=True)
        if payload is None or part.defects:
            fail("invalid_multipart", "上传内容不完整。")
        if name == "attachments":
            if len(attachments) >= 4 or part.get_filename() is None:
                fail("attachment_count", "最多上传 3 张图片和 1 个视频。")
            if len(payload) > VIDEO_LIMIT:
                fail("attachment_too_large", "单个视频不能超过 30 MiB。", 413)
            media_type, extension = detect_media(payload)
            if media_type.startswith("image/") and len(payload) > IMAGE_LIMIT:
                fail("attachment_too_large", "单张图片不能超过 8 MiB。", 413)
            attachments.append(Attachment(safe_original_name(part.get_filename()),
                                          media_type, extension, payload))
        else:
            if name not in text_limits or name in fields or part.get_filename() is not None:
                fail("invalid_field", "表单字段不正确或重复。")
            if len(payload) > text_limits[name] * 4:
                fail("field_too_long", f"{name} 内容过长。")
            try:
                value = payload.decode("utf-8").strip()
            except UnicodeDecodeError:
                fail("invalid_field", "表单文字需要使用 UTF-8 编码。")
            if len(value) > text_limits[name] or any(ord(c) < 32 and c not in "\n\r\t" for c in value):
                fail("invalid_field", "表单文字过长或包含无效字符。")
            if name != "description" and any(c in value for c in "\n\r\t"):
                fail("invalid_field", "表单字段不能包含换行。")
            fields[name] = value
    if kind == "feedback":
        if fields.get("issueType") not in {"missed", "wrong_click", "other"}:
            fail("invalid_issue_type", "请选择问题类型。")
        if not fields.get("appName"):
            fail("missing_app_name", "请填写发生问题的应用名称。")
    elif not fields.get("description"):
        fail("missing_description", "请写下你的愿望或改进建议。")
    if fields.get("consent") != "true":
        fail("consent_required", "请先同意提交内容和附件。")
    request_id = fields.get("requestId", "")
    if request_id and not re.fullmatch(r"[A-Za-z0-9_-]{16,80}", request_id):
        fail("invalid_request_id", "提交编号格式不正确。")
    email = fields.get("email", "")
    if email and not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email):
        fail("invalid_email", "邮箱格式不正确。")
    images = sum(a.media_type.startswith("image/") for a in attachments)
    videos = len(attachments) - images
    if kind == "feedback" and not attachments:
        fail("attachment_count", "请至少上传 1 个附件，最多 3 张图片和 1 个视频。")
    if images > 3 or videos > 1:
        fail("attachment_count", "最多上传 3 张图片和 1 个视频。")
    normalized = {name: fields.get(name, "") for name in text_limits}
    return Submission(normalized, tuple(attachments))


class Store:
    def __init__(self, directory: Path, quota_bytes=2 * 1024 * MIB, reserve_bytes=256 * MIB):
        self.directory = directory.resolve()
        self.quota_bytes, self.reserve_bytes = quota_bytes, reserve_bytes
        self.lock = threading.Lock()
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.directory.chmod(0o700)
        self.uploads = self.directory / "attachments"
        self.uploads.mkdir(exist_ok=True, mode=0o700)
        self.uploads.chmod(0o700)
        self.db_path = self.directory / "feedback.sqlite3"
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS feedback (
                id TEXT PRIMARY KEY, created_at TEXT NOT NULL,
                request_key TEXT UNIQUE, fingerprint TEXT NOT NULL,
                fields_json TEXT NOT NULL, attachments_json TEXT NOT NULL,
                total_bytes INTEGER NOT NULL)""")
            # Additive schema: old feedback rows and attachment locations stay intact.
            db.execute("""CREATE TABLE IF NOT EXISTS wishes (
                id TEXT PRIMARY KEY, created_at TEXT NOT NULL,
                request_key TEXT UNIQUE, fingerprint TEXT NOT NULL,
                fields_json TEXT NOT NULL, attachments_json TEXT NOT NULL,
                total_bytes INTEGER NOT NULL,
                public_id TEXT UNIQUE, approved_at TEXT, public_description TEXT,
                status TEXT NOT NULL DEFAULT 'pending'
                    CHECK(status IN ('pending','planned','completed')),
                developer_note TEXT NOT NULL DEFAULT '', updated_at TEXT,
                CHECK ((approved_at IS NULL AND public_id IS NULL AND public_description IS NULL)
                    OR (approved_at IS NOT NULL AND public_id IS NOT NULL
                        AND public_description IS NOT NULL AND updated_at IS NOT NULL)))""")
            db.execute("CREATE INDEX IF NOT EXISTS wishes_public_order ON wishes(approved_at DESC, public_id DESC)")
        self.db_path.chmod(0o600)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.db_path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def recover(self):
        # Call once at server startup, while holding the exclusive service lock.
        with self.lock, self.connect() as db:
            retained = {row[0] for row in db.execute("SELECT id FROM feedback UNION SELECT id FROM wishes")}
            for directory in self.uploads.iterdir():
                if directory.is_dir() and (directory.name.startswith(".pending-") or
                                           (re.fullmatch(r"[a-f0-9]{32}", directory.name)
                                            and directory.name not in retained)):
                    shutil.rmtree(directory)

    def usage(self):
        return sum(path.stat().st_size for path in self.directory.rglob("*") if path.is_file())

    def save(self, submission: Submission):
        return self._save(submission, "feedback")

    def save_wish(self, submission: Submission):
        return self._save(submission, "wish")

    def _save(self, submission: Submission, kind):
        table = "wishes" if kind == "wish" else "feedback"
        request_id = submission.fields.get("requestId")
        request_key = hashlib.sha256(request_id.encode()).hexdigest() if request_id else None
        fingerprint = submission.fingerprint
        total = sum(len(a.data) for a in submission.attachments)
        with self.lock, self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if request_key:
                previous = db.execute(f"SELECT * FROM {table} WHERE request_key=?", (request_key,)).fetchone()
                if previous:
                    if previous["fingerprint"] != fingerprint:
                        fail("request_id_conflict", "同一提交编号的内容发生变化，请重新提交。", 409)
                    return _receipt(kind, previous["id"], previous["created_at"], True)
            # Include database/journal and reserve headroom for committing metadata.
            needed = total + 256 * 1024
            if self.usage() + needed > self.quota_bytes or shutil.disk_usage(self.directory).free < needed + self.reserve_bytes:
                fail("storage_full", "反馈存储暂时已满，请稍后再试。", 507)
            feedback_id = uuid.uuid4().hex
            created_at = datetime.now(timezone.utc).isoformat()
            pending = self.uploads / (".pending-" + feedback_id)
            final = self.uploads / feedback_id
            pending.mkdir(mode=0o700)
            try:
                entries = []
                for index, attachment in enumerate(submission.attachments, 1):
                    filename = f"attachment-{index}.{attachment.extension}"
                    path = pending / filename
                    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    with os.fdopen(fd, "wb") as out:
                        out.write(attachment.data)
                        out.flush()
                        os.fsync(out.fileno())
                    entries.append({"filename": filename, "originalName": attachment.original_name,
                                    "mediaType": attachment.media_type, "bytes": len(attachment.data),
                                    "sha256": attachment.sha256})
                _sync_directory(pending)
                pending.rename(final)
                _sync_directory(self.uploads)
                stored_fields = {k: v for k, v in submission.fields.items() if k != "requestId"}
                db.execute(f"INSERT INTO {table} (id,created_at,request_key,fingerprint,fields_json,attachments_json,total_bytes) VALUES (?,?,?,?,?,?,?)", (
                    feedback_id, created_at, request_key, fingerprint,
                    json.dumps(stored_fields, ensure_ascii=False),
                    json.dumps(entries, ensure_ascii=False), total))
                db.commit()
            except BaseException:
                db.rollback()
                shutil.rmtree(pending, ignore_errors=True)
                shutil.rmtree(final, ignore_errors=True)
                raise
            return _receipt(kind, feedback_id, created_at, False)

    def public_wishes(self, limit=20, cursor=None):
        if not 1 <= limit <= 50:
            fail("invalid_query", "每页数量需要在 1 到 50 之间。")
        values = []
        where = "approved_at IS NOT NULL"
        if cursor:
            approved_at, public_id = _decode_cursor(cursor)
            where += " AND (approved_at,public_id) < (?,?)"
            values.extend((approved_at, public_id))
        values.append(limit + 1)
        with self.connect() as db:
            # Deliberate projection: never select private fields or attachment metadata.
            rows = db.execute(f"""SELECT public_id,approved_at,public_description,status,developer_note,updated_at
                FROM wishes WHERE {where} ORDER BY approved_at DESC,public_id DESC LIMIT ?""", values).fetchall()
        visible = rows[:limit]
        next_cursor = None
        if len(rows) > limit:
            last = visible[-1]
            next_cursor = _encode_cursor(last["approved_at"], last["public_id"])
        return {"ok": True, "items": [_public_wish(row) for row in visible], "nextCursor": next_cursor}


def _receipt(kind, identity, created_at, duplicate):
    value = {"ok": True, "wishId" if kind == "wish" else "feedbackId": identity,
             "createdAt": created_at, "duplicate": duplicate}
    if kind == "wish":
        value["status"] = "pending_review"
    return value


def _public_wish(row):
    return {"id": row["public_id"], "description": row["public_description"],
            "status": row["status"], "developerNote": row["developer_note"],
            "createdAt": row["approved_at"], "updatedAt": row["updated_at"]}


def _encode_cursor(approved_at, public_id):
    raw = json.dumps([approved_at, public_id], separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _decode_cursor(cursor):
    try:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,256}", cursor):
            raise ValueError()
        value = json.loads(base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True))
        if not isinstance(value, list) or len(value) != 2 or not all(isinstance(v, str) for v in value):
            raise ValueError()
        stamp, identity = value
        if not re.fullmatch(r"[a-f0-9]{32}", identity) or len(stamp) > 40:
            raise ValueError()
        if datetime.fromisoformat(stamp).tzinfo != timezone.utc:
            raise ValueError()
        return stamp, identity
    except (ValueError, TypeError, binascii.Error, UnicodeDecodeError):
        fail("invalid_cursor", "列表页码已失效，请重新加载。")


def _sync_directory(directory: Path):
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


class RateLimiter:
    def __init__(self, per_ip=5, global_limit=100, window=600, max_ips=4096):
        self.per_ip, self.global_limit, self.window, self.max_ips = per_ip, global_limit, window, max_ips
        self.lock = threading.Lock()
        self.entries, self.global_events = {}, []

    def allow(self, ip):
        now = time.monotonic()
        with self.lock:
            cutoff = now - self.window
            self.global_events = [stamp for stamp in self.global_events if stamp > cutoff]
            self.entries = {key: [stamp for stamp in times if stamp > cutoff]
                            for key, times in self.entries.items() if times and times[-1] > cutoff}
            if len(self.global_events) >= self.global_limit:
                return False
            times = self.entries.get(ip, [])
            if len(times) >= self.per_ip or (ip not in self.entries and len(self.entries) >= self.max_ips):
                return False
            self.entries[ip] = times + [now]
            self.global_events.append(now)
            return True


class FeedbackServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, store, limiter=None):
        self.store, self.limiter = store, limiter or RateLimiter()
        self.read_limiter = RateLimiter(per_ip=120, global_limit=2000)
        self.workers = threading.BoundedSemaphore(8)
        self.upload_slots = threading.BoundedSemaphore(2)
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.workers.acquire(blocking=False):
            request.settimeout(2)
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\nContent-Length: 0\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.workers.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.workers.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "XAdSkipFeedback"
    sys_version = ""

    def setup(self):
        self.request.settimeout(30)
        super().setup()

    def log_message(self, *_):
        pass  # Never include user text, filenames, email or request URLs in access logs.

    def _respond(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        origin = self.headers.get("Origin")
        if origin in ORIGINS:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
        if status == 429:
            self.send_header("Retry-After", "600")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(body)

    def do_GET(self):
        try:
            if self.path == "/api/health":
                self._respond(200, {"ok": True})
                return
            if len(self.path) > 512:
                fail("invalid_query", "列表请求过长。")
            url = urlsplit(self.path)
            if url.path != "/api/wishes" or url.scheme or url.netloc or url.fragment:
                fail("not_found", "接口不存在。", 404)
            if not self.server.read_limiter.allow(self._client_ip()):
                fail("rate_limited", "查看过于频繁，请稍后再试。", 429)
            try:
                query = parse_qs(url.query, keep_blank_values=True, strict_parsing=True, max_num_fields=2) if url.query else {}
            except ValueError:
                fail("invalid_query", "列表参数不正确。")
            if set(query) - {"limit", "cursor"} or any(len(v) != 1 for v in query.values()):
                fail("invalid_query", "列表参数不正确。")
            limit = query.get("limit", ["20"])[0]
            if not re.fullmatch(r"[1-9][0-9]?", limit):
                fail("invalid_query", "每页数量需要在 1 到 50 之间。")
            cursor = query.get("cursor", [None])[0]
            if cursor == "":
                fail("invalid_cursor", "列表页码已失效，请重新加载。")
            self._respond(200, self.server.store.public_wishes(int(limit), cursor))
        except FeedbackError as error:
            self._respond(error.status, {"ok": False, "error": {"code": error.code, "message": error.message}})
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            self._respond(500, {"ok": False, "error": {"code": "internal_error", "message": "愿望列表暂时无法读取，请稍后再试。"}})

    def do_OPTIONS(self):
        if self.path not in {"/api/feedback", "/api/wishes"} or self.headers.get("Origin") not in ORIGINS:
            self._respond(403, {"ok": False, "error": {"code": "origin_denied", "message": "来源不允许。"}})
            return
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", self.headers["Origin"])
        self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS" if self.path == "/api/wishes" else "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
        self.send_header("Content-Length", "0")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _client_ip(self):
        peer = ipaddress.ip_address(self.client_address[0])
        # nginx must replace (not forward) this header. It is trusted only on loopback.
        real_ip = self.headers.get("X-Real-IP") if peer.is_loopback else None
        try:
            return str(ipaddress.ip_address(real_ip)) if real_ip else str(peer)
        except ValueError:
            return str(peer)

    def do_POST(self):
        acquired = False
        try:
            if self.path not in {"/api/feedback", "/api/wishes"}:
                fail("not_found", "接口不存在。", 404)
            kind = "wish" if self.path == "/api/wishes" else "feedback"
            origin = self.headers.get("Origin")
            if origin is not None and origin not in ORIGINS:
                fail("origin_denied", "来源不允许。", 403)
            if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding"):
                fail("unsupported_encoding", "请使用普通文件上传。")
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or not re.fullmatch(r"[0-9]{1,10}", lengths[0]):
                fail("length_required", "上传内容缺少有效长度。", 411)
            length = int(lengths[0])
            if length <= 0 or length > TOTAL_LIMIT:
                fail("request_too_large", "本次上传总大小不能超过 60 MiB。", 413)
            if not self.server.limiter.allow(self._client_ip()):
                fail("rate_limited", "提交过于频繁，请 10 分钟后再试。", 429)
            acquired = self.server.upload_slots.acquire(blocking=False)
            if not acquired:
                fail("busy", "当前上传繁忙，请稍后再试。", 503)
            deadline, remaining, chunks = time.monotonic() + 180, length, []
            while remaining:
                if time.monotonic() > deadline:
                    fail("upload_timeout", "上传超时，请重新提交。", 408)
                chunk = self.rfile.read1(min(64 * 1024, remaining))
                if not chunk:
                    fail("incomplete_upload", "上传中断，请重试。")
                chunks.append(chunk)
                remaining -= len(chunk)
            body = b"".join(chunks)
            del chunks
            submission = parse_submission(self.headers.get("Content-Type", ""), body, kind)
            del body
            result = self.server.store.save_wish(submission) if kind == "wish" else self.server.store.save(submission)
            self._respond(200 if result["duplicate"] else 201, result)
        except FeedbackError as error:
            self._respond(error.status, {"ok": False, "error": {"code": error.code, "message": error.message}})
        except (socket.timeout, TimeoutError):
            self._respond(408, {"ok": False, "error": {"code": "upload_timeout", "message": "上传超时，请重试。"}})
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            self._respond(500, {"ok": False, "error": {"code": "internal_error", "message": "反馈暂时无法保存，请稍后重试。"}})
        finally:
            if acquired:
                self.server.upload_slots.release()


def admin(directory: Path, command: str, feedback_id=None, output=None, limit=20):
    db_path = directory / "feedback.sqlite3"
    db = sqlite3.connect(f"file:{db_path.resolve()}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    try:
        if command == "list":
            rows = db.execute("SELECT id,created_at,fields_json,total_bytes FROM feedback ORDER BY created_at DESC LIMIT ?",
                              (max(1, min(limit, 1000)),)).fetchall()
            return [{"id": r["id"], "createdAt": r["created_at"], "issueType": json.loads(r["fields_json"])["issueType"],
                     "appName": json.loads(r["fields_json"])["appName"], "bytes": r["total_bytes"]} for r in rows]
        if not feedback_id or not re.fullmatch(r"[a-f0-9]{32}", feedback_id):
            raise ValueError("feedback id must be the 32-character id returned by list")
        row = db.execute("SELECT * FROM feedback WHERE id=?", (feedback_id,)).fetchone()
        if row is None:
            raise ValueError("feedback not found")
        result = {"id": row["id"], "createdAt": row["created_at"], "fields": json.loads(row["fields_json"]),
                  "attachments": json.loads(row["attachments_json"])}
        if command == "export":
            if not output:
                raise ValueError("--output is required")
            destination = Path(output)
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(descriptor, "wb") as target, zipfile.ZipFile(target, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("feedback.json", json.dumps(result, ensure_ascii=False, indent=2))
                    for item in result["attachments"]:
                        filename = item["filename"]
                        if not re.fullmatch(r"attachment-[1-4]\.(jpg|png|webp|mp4|webm)", filename):
                            raise ValueError("invalid stored filename")
                        source = directory / "attachments" / feedback_id / filename
                        if hashlib.sha256(source.read_bytes()).hexdigest() != item["sha256"]:
                            raise ValueError("attachment checksum mismatch")
                        archive.write(source, filename)
            except BaseException:
                destination.unlink(missing_ok=True)
                raise
            return {"exported": str(destination), "feedbackId": feedback_id}
        return result
    finally:
        db.close()


def _review_text(path, required=False, maximum=5000):
    if path is None:
        if required:
            raise ValueError("--public-text-file is required; private submission text is never published automatically")
        return None
    with Path(path).open("rb") as source:
        raw = source.read(maximum * 4 + 1)
    if len(raw) > maximum * 4:
        raise ValueError("reviewed text file is too large")
    value = raw.decode("utf-8").strip()
    if (len(value) > maximum or (required and not value)
            or any(ord(c) < 32 and c not in "\n\r\t" for c in value)):
        raise ValueError("reviewed text is empty, too long, or contains invalid characters")
    return value


def wish_admin(directory: Path, command: str, wish_id=None, output=None, limit=20,
               public_text_file=None, developer_note_file=None, status=None):
    """Local operator CLI only. Publication requires explicitly supplied reviewed text."""
    if command not in {"list-wishes", "show-wish", "export-wish", "approve-wish", "set-wish-status"}:
        raise ValueError("unknown wish command")
    writable = command in {"approve-wish", "set-wish-status"}
    db_path = (directory / "feedback.sqlite3").resolve()
    db = sqlite3.connect(f"file:{db_path}?mode={'rw' if writable else 'ro'}", uri=True, timeout=10)
    db.row_factory = sqlite3.Row
    try:
        if command == "list-wishes":
            rows = db.execute("SELECT id,created_at,approved_at,status,total_bytes FROM wishes ORDER BY created_at DESC LIMIT ?",
                              (max(1, min(limit, 1000)),)).fetchall()
            return [{"id": row["id"], "createdAt": row["created_at"],
                     "status": row["status"] if row["approved_at"] else "pending_review",
                     "bytes": row["total_bytes"]} for row in rows]
        if not wish_id or not re.fullmatch(r"[a-f0-9]{32}", wish_id):
            raise ValueError("wish id must be the 32-character private receipt id returned by list-wishes")
        if writable:
            # Serialize review/status edits with each other and submissions, without replacing private fields.
            db.execute("BEGIN IMMEDIATE")
        row = db.execute("SELECT * FROM wishes WHERE id=?", (wish_id,)).fetchone()
        if row is None:
            raise ValueError("wish not found")
        if writable:
            if status is not None and status not in WISH_STATUSES:
                raise ValueError("status must be pending, planned, or completed")
            note = _review_text(developer_note_file, maximum=2000)
            now = datetime.now(timezone.utc).isoformat()
            if command == "approve-wish":
                reviewed = _review_text(public_text_file, required=True)
                db.execute("""UPDATE wishes SET public_id=?,approved_at=?,public_description=?,
                    status=?,developer_note=?,updated_at=? WHERE id=?""", (
                    row["public_id"] or uuid.uuid4().hex, row["approved_at"] or now, reviewed,
                    status or row["status"], note if note is not None else row["developer_note"], now, wish_id))
            else:
                if not row["approved_at"]:
                    raise ValueError("approve the reviewed public text before changing its public status")
                if status is None:
                    raise ValueError("--status is required")
                if public_text_file is not None:
                    raise ValueError("use approve-wish to replace reviewed public text")
                db.execute("UPDATE wishes SET status=?,developer_note=?,updated_at=? WHERE id=?",
                           (status, note if note is not None else row["developer_note"], now, wish_id))
            row = db.execute("SELECT * FROM wishes WHERE id=?", (wish_id,)).fetchone()
            db.commit()
            return {"ok": True, "wishId": wish_id, "public": _public_wish(row)}
        result = {"id": row["id"], "createdAt": row["created_at"],
                  "fields": json.loads(row["fields_json"]), "attachments": json.loads(row["attachments_json"]),
                  "status": row["status"] if row["approved_at"] else "pending_review"}
        if row["approved_at"]:
            result["public"] = _public_wish(row)
        if command == "export-wish":
            if not output:
                raise ValueError("--output is required")
            destination = Path(output)
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(descriptor, "wb") as target, zipfile.ZipFile(target, "w", zipfile.ZIP_STORED) as archive:
                    archive.writestr("wish.json", json.dumps(result, ensure_ascii=False, indent=2))
                    for item in result["attachments"]:
                        filename = item["filename"]
                        if not re.fullmatch(r"attachment-[1-4]\.(jpg|png|webp|mp4|webm)", filename):
                            raise ValueError("invalid stored filename")
                        source = directory / "attachments" / wish_id / filename
                        if hashlib.sha256(source.read_bytes()).hexdigest() != item["sha256"]:
                            raise ValueError("attachment checksum mismatch")
                        archive.write(source, filename)
            except BaseException:
                destination.unlink(missing_ok=True)
                raise
            return {"exported": str(destination), "wishId": wish_id}
        return result
    finally:
        db.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("serve", "list", "show", "export", "list-wishes", "show-wish",
                                            "export-wish", "approve-wish", "set-wish-status"))
    parser.add_argument("feedback_id", nargs="?")
    parser.add_argument("--data", type=Path, default=Path(os.environ.get("XADSKIP_FEEDBACK_DATA", "/var/lib/xadskip-feedback")))
    parser.add_argument("--port", type=int, default=int(os.environ.get("XADSKIP_FEEDBACK_PORT", "8768")))
    parser.add_argument("--output")
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument("--public-text-file", type=Path)
    parser.add_argument("--developer-note-file", type=Path)
    parser.add_argument("--status", choices=sorted(WISH_STATUSES))
    args = parser.parse_args()
    os.umask(0o077)
    if args.command != "serve":
        if args.command.endswith("wish") or args.command in {"list-wishes", "set-wish-status"}:
            result = wish_admin(args.data, args.command, args.feedback_id, args.output, args.limit,
                                args.public_text_file, args.developer_note_file, args.status)
        else:
            result = admin(args.data, args.command, args.feedback_id, args.output, args.limit)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    import fcntl
    args.data.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (args.data / "service.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        store = Store(args.data,
                      quota_bytes=int(os.environ.get("XADSKIP_FEEDBACK_QUOTA_BYTES", str(2 * 1024 * MIB))),
                      reserve_bytes=int(os.environ.get("XADSKIP_FEEDBACK_RESERVE_BYTES", str(256 * MIB))))
        store.recover()
        server = FeedbackServer(("127.0.0.1", args.port), store)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


if __name__ == "__main__":
    main()
