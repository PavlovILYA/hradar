# Telegram documents (0.11)

## Scope and data flow

Documents are owner-local data. Telegram search still searches Telegram messages;
**Documents** searches filenames and text of explicitly downloaded, processed local
files. It does not search every file in an account or fetch history implicitly.

Select one chat/forum topic in Documents, request a bounded discovery page, inspect
its supported PDF/DOCX/TXT entries, then explicitly download. Continue discovery
only by pressing the continuation action. This metadata discovery does not import
an archive or subscribe/join a chat. A publication dialog offers the same explicit
preparation/download flow. New monitoring matches retain attachment metadata; they
never trigger downloads automatically.

The owner-only `DocumentService` uses `SourceReadService` for current source/scope
proof and the existing gateway for `DownloadFile`/`GetFile`/`CancelDownloadFile`.
The TDLib callback thread never parses files or blocks for download completion.
Unsupported photo/video/audio/file formats retain their existing message metadata.
Protected Telegram content cannot be downloaded by this feature.

There is one bounded queue and one worker. Cancel stops the worker/transfer; retry
is explicit. In-progress jobs become interrupted errors after restart instead of
silently downloading again. FLOOD_WAIT creates a persisted retry deadline; network
or parser failures never produce infinite retries.

## Storage, privacy and limits

The existing `~/.hradar/data/research.sqlite` advances atomically from schema13 to14.
`documents` contains publication snapshots, TDLib identity, processing state and
bounded extracted text; `document_fts` is owner-only FTS5. No existing message/archive,
bookmark, project, subscription, cursor or match table is rewritten by migration14.
The original content is separate from extracted SQLite text. Older metadata-only
snapshots and the legacy public-search adapter need a selected chat/source refresh
before the download controls are available; no scope is silently inferred.

Original blobs live under `~/.hradar/documents/<sha256>`. Generated names, regular-file
and symlink checks, private directory0700/file0600 permissions and atomic completion
avoid trusting Telegram filenames/paths. Original display names are sanitized.
Content hashes and reliable remote identity allow blob reuse while preserving
separate publication references. Local filesystem paths are never API fields.
File responses use protected owner-local routes, attachment disposition, no-store,
CSP sandbox and nosniff; the browser saves/opens them using its normal file handling.
No OS shell command is constructed from a filename.

Limits: 25MiB per original, 500MiB combined managed/native document-file budget, one simultaneous job and a
20-entry waiting queue, 10,000 metadata records, 200,000 extracted characters per
record and20million characters of total extracted text. Before a download the budget
counts existing managed originals and native TDLib document files, reserving room
for transfer and atomic copying (unknown sizes reserve25MiB twice). The UI reports
managed originals separately. Existing TDLib files are never deleted by this feature;
if that directory already exceeds the budget, new downloads are refused.
Discovery uses bounded existing source pagination; no exhaustive scan is claimed.

PDFBox3.0.8 extracts the embedded PDF layer (maximum200 pages). DOCX plain text is
read from bounded `word/document.xml` using standard ZIP and hardened SAX with DTD
and external entity access disabled. TXT accepts strict UTF8, UTF16 BOM and a
validated Cyrillic Windows1251 fallback; unsupported/binary data fails safely.
Scanned PDFs show that recognition is required; no OCR is attempted. Empty files
and corrupt/unsupported content have distinct visible outcomes.

Parsing runs in a disposable JVM with128MiB heap,32MiB direct-memory cap and20-second
wall-clock timeout. Its environment is cleared; home/temp/IPC are private temporary
paths and stdout/stderr are discarded. Exceptions returned to the UI contain no
extracted content. Original documents remain available if extraction fails.
No document download/text routes or index are exposed to Bot/MCP; no cloud/AI calls.

FTS5 uses unicode token matching for filename/content with AND between query words,
not Telegram query syntax, stemming or semantic matching. Results include the
original publication, date, filename and bounded highlighted excerpt. Re-extraction
is explicit and updates the same record. A separate scrollable text dialog avoids
putting a full resume in the publication's compact details.

## Migration and recovery

Migration14 is a single SQLite transaction; failed DDL rolls back version and newly
created tables, preserving old data. Tests inject a collision, retry and reopen.
Never downgrade `PRAGMA user_version` on a user's database or remove documents to
make an older app start. Older versions must refuse schema14. Before an authorized
real-profile source update, stop the sole backend cleanly and make a consistent
SQLite backup with owner-only permissions outside Git. Keep that backup and all
originals. Recovery uses a verified backup in a separately selected profile with a
compatible app, rather than overwriting current files or deleting later work.

## Manual acceptance on the actual Telegram account

Automatic checks use only synthetic documents and temporary profiles. They do not
prove real Telegram download permissions, remote file availability or macOS opening.
With exactly one authorized backend:

1. Use a chosen QA chat/topic with a self-created PDF containing “Spring Boot” and a
   caption that omits those words. Discover one page; verify nothing downloads yet.
2. Explicitly download, inspect progress/status, then search Spring Boot locally.
   Check excerpt, source chat/date, original Telegram link, file download/open and
   separate extracted-text viewer. Repeat with DOCX and supported TXT encodings.
3. Try a scanned PDF, empty TXT and damaged test file; originals remain available,
   status is clear, the app stays responsive. Try a file above25MiB and protected
   chat content: refusal must be clear.
4. Cancel an active download, retry, disconnect/reconnect and restart once. Confirm
   interrupted work needs explicit retry, completed content remains searchable and
   publication links survive. Repeat one document in another message and verify
   two publication references with one managed blob.
5. Discover only an explicitly chosen next page. Verify unselected chat/topic content
   is absent. Receive a monitored document message; metadata appears but no automatic
   download occurs. Confirm Bot/MCP cannot read the document index or extracted text.
6. Check the update indicator by mouse/touch and keyboard: toggle, outside click,
   Escape, change section, return focus and real reconnect status.

OCR, Whisper/audio transcription, embeddings and AI interpretation are future stages,
not hidden capabilities of this version. They need separate resource/privacy design.
