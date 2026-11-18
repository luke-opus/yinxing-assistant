# 银杏助手反馈与许愿池服务

Python 3.12 standard library only. No pip packages or public administration service.

## Interface

- `GET /api/health` → `200 {"ok":true}`.
- `POST /api/feedback`, `multipart/form-data`:
  - `issueType`: `missed`, `wrong_click`, or `other`.
  - `appName`: required, at most 120 characters.
  - `appVersion`, `xadskipVersion`: optional, at most 80 characters each.
  - `description`: optional, at most 5,000 characters.
  - `email`: optional, at most 254 characters, valid address syntax.
  - `consent`: literal `true`.
  - `requestId`: optional, 16–80 ASCII letters/digits/underscore/hyphen. Client-generated UUID recommended. Reuse it for retries of the **same** submission.
  - `attachments`: repeated file field; at least one file. Up to three JPEG/PNG/WebP images (8 MiB each), and up to one MP4/WebM video (30 MiB). Whole HTTP body maximum 60 MiB.
- New success: `201 {"ok":true,"feedbackId":"32 character opaque id","createdAt":"UTC ISO timestamp","duplicate":false}`.
- Same requestId and identical normalized content: `200`, original id/time, `duplicate:true`. Changed content with same id: `409`.
- Errors: `{ "ok": false, "error": { "code": "...", "message": "Chinese user-facing message" } }`.
- Browser origins allowed: `https://feedback.xadskip.com`, `https://xadskip.com`, `https://www.xadskip.com`. Native app requests without Origin work.
- There is **no** public feedback lookup, attachment URL or admin endpoint. A feedback id is only a receipt.

### Wishes

- `POST /api/wishes`, `multipart/form-data`:
  - `description`: required nonblank text, at most 5,000 characters.
  - `email`: optional, at most 254 characters, valid address syntax.
  - `xadskipVersion`: optional, at most 80 characters.
  - `consent`: literal `true`.
  - `requestId`: same syntax as feedback; client-generated UUID recommended, retained for retries of unchanged content.
  - `attachments`: **optional**. A text-only wish is valid. If attached, the same three-image/one-video/type/size limits apply.
- Genuine saved receipt: `201 {"ok":true,"wishId":"32 character private receipt id","createdAt":"UTC ISO timestamp","status":"pending_review","duplicate":false}`. Retry returns `200` with the original receipt and `duplicate:true`. This receipt records initial submission, not later review status.
- Idempotency is isolated by submission type: the same requestId can identify one feedback and one wish; changed content for the same type remains a `409` conflict.
- The submitted description, contact details, version, and all attachments remain private. Submission does **not** publish anything.
- `GET /api/wishes?limit=20&cursor=OPAQUE_CURSOR` returns `{"ok":true,"items":[...],"nextCursor":null}`. Omit `cursor` on the first page. `limit` is 1–50, default 20; use the returned cursor unchanged when non-null.
- Each item contains **only** `id`, `description`, `status`, `developerNote`, `createdAt`, `updatedAt`. These describe the reviewed publication: public id is separate from the private receipt; `createdAt` is its first approval time. `description` and `developerNote` are explicitly entered by the developer. No raw submission fields, attachment metadata/URLs, email, IP, user identifier, or private receipt id are returned.
- Public statuses are `pending` (reviewed, awaiting consideration), `planned`, and `completed` (frontend shows ✓). Private `pending_review` submissions never appear in the list.
- Text is plain text, not HTML. Clients must use safe text rendering such as `textContent`.
- There is no public wish-detail, review, status-write, upload-download, or administration endpoint. Only the local CLI over existing server access can approve or complete a wish.

## Run

```sh
python3 feedback_server.py serve --data /var/lib/xadskip-feedback
```

The service binds **127.0.0.1:8768**. Run as a dedicated unprivileged user, using the systemd template. Data must be outside every nginx web root; use `/var/lib/xadskip-feedback`. Directories are 0700, files 0600, service umask 0077.

Environment:

- `XADSKIP_FEEDBACK_DATA`: data directory.
- `XADSKIP_FEEDBACK_PORT`: local port, default 8768.
- `XADSKIP_FEEDBACK_QUOTA_BYTES`: total private storage quota, default 2 GiB, including DB/WAL and headroom.
- `XADSKIP_FEEDBACK_RESERVE_BYTES`: minimum filesystem free space left after upload, default 256 MiB.

SQLite metadata and random attachment directories are committed together with a staging folder. Each file and directory is fsynced before metadata commit. Failure removes partial files; startup under an exclusive service lock cleans orphan staging/uncommitted directories. Existing committed feedback is retained. The wishes table is added without modifying the feedback table; recovery retains both kinds' committed attachment folders. Back up the whole private data directory while the service is stopped, or take a coordinated SQLite backup and matching attachment snapshot. After wishes have been stored, do not run the old feedback-only binary: its old recovery routine does not recognize wish attachments. Keep this server version or a newer compatible version when rolling back the static website.

Uploads are limited to two parsers and eight total request workers. Per-process submission limit shared by feedback and wishes: five requests per IP per ten minutes, and 100 global requests per ten minutes. Invalid uploads count too. The public wish list has a separate limit of 120 reads per IP and 2,000 globally per ten minutes, with bounded pagination. Limits reset on service restart; use ingress limits as an additional persistent-configuration boundary. HTTP uploads have a 30-second socket timeout and a 180-second read deadline. The reverse proxy should buffer complete client requests before proxying.

The reverse proxy must **replace** `X-Real-IP` with the remote address, never forward a client-supplied value. Do not expose port 8768 publicly. Proxy exact paths `/api/feedback`, `/api/wishes`, and `/api/health`; retain wish query parameters. The actual deployment uses Caddy; an nginx example is also included. Keep the 60 MiB request body limit, buffering and client timeouts. Serve static frontend files from a separate web root; never expose the private data directory.

Media is checked by content signatures and container boundaries, not the supplied filename/MIME label. No codec or media executable is invoked. This is not a full media decoder or malware scan. The 60-second short-video guidance is checked by the browser; the standard-library server enforces video byte size/container limits, not decoded duration. Review submissions using an up-to-date viewer. Attachments are never automatically published, including after wish approval.

## Local administration over SSH

Run as root or the dedicated service user. Output contains user-submitted private information.

```sh
python3 feedback_server.py list --data /var/lib/xadskip-feedback --limit 20
python3 feedback_server.py show FEEDBACK_ID --data /var/lib/xadskip-feedback
python3 feedback_server.py export FEEDBACK_ID --data /var/lib/xadskip-feedback --output /root/feedback-FEEDBACK_ID.zip
```

Exports include `feedback.json` and original media bytes with server-generated names; SHA-256 is checked. Export files are private (0600), and existing output paths are never overwritten. Nothing is mailed automatically.

### Review and complete wishes

Use the same existing authorized server account; no new public password or administration secret is created. First review the private original locally:

```sh
python3 feedback_server.py list-wishes --data /var/lib/xadskip-feedback --limit 20
python3 feedback_server.py show-wish WISH_ID --data /var/lib/xadskip-feedback
python3 feedback_server.py export-wish WISH_ID --data /var/lib/xadskip-feedback --output /root/wish-WISH_ID.zip
```

The `WISH_ID` is the private receipt/list id, not the public list id. Exports contain `wish.json` plus original media, with the same checksum and no-overwrite protections as feedback exports.

Write reviewed public text to a UTF-8 file (maximum 5,000 characters). Remove names, contact details and any private information before approving. The CLI deliberately has **no fallback** to the original submitted description; `--public-text-file` must be explicit and nonblank. Optional public developer notes are read from a UTF-8 file of at most 2,000 characters. Use private file permissions for working notes.

```sh
python3 feedback_server.py approve-wish WISH_ID --data /var/lib/xadskip-feedback --public-text-file /root/reviewed-wish.txt
python3 feedback_server.py set-wish-status WISH_ID --data /var/lib/xadskip-feedback --status planned --developer-note-file /root/public-note.txt
python3 feedback_server.py set-wish-status WISH_ID --data /var/lib/xadskip-feedback --status completed --developer-note-file /root/public-note.txt
```

Approval defaults to public `pending`; `--status planned` or `--status completed` may be supplied intentionally at approval. An unapproved wish cannot be made public merely by changing its status. Re-running `approve-wish` replaces only the explicitly reviewed public text and optionally status/note, preserving the original private submission, public id and first approval time. Omit a note file to retain the existing public note; pass an empty file to clear it. A status change is recorded with a new public update time. No automated completion claim is made from receiving the suggestion alone.

## Validation

```sh
python3 -m unittest -v test_feedback_server.py
```

Tests use real localhost HTTP multipart requests and a temporary SQLite/filesystem store. They cover the existing feedback behavior plus text-only wishes, media privacy, additive old-database migration, typed concurrent idempotency, required review text, independent public/private identities, status CLI round trips, explicit safe public projection, cursor validation/pagination, private exports, startup recovery for both types, and absence of public private-data or administration endpoints.
