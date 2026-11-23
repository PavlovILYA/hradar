# Telegram files 0.11 design

The user's detailed specification is authoritative. Explicit owner-local document discovery,
download, extraction and search extend existing sources/messages, not Telegram search semantics.
PR19 merged f6ed9af; one feature branch, Draft PR, no publication/merge/tag.

Approach: extend same SQLite with forward atomic migration and separate document rows linked
to ResearchMessage source snapshots. Original blobs use SHA256 names; publications remain distinct.
No Bot/MCP routes. Existing single gateway executes asynchronous DownloadFile and GetFile polling,
CancelDownloadFile on cancellation, not blocking callbacks. Re-fetch exact scoped message before
accepting download; client-supplied file IDs/paths are never authority. One concurrent job, bounded
queue/file size/total managed disk, no automatic resumption or infinite retries. Restart interrupts
jobs visibly; retry explicit. FLOOD_WAIT is visible with retry deadline.

Use local immutable originals and extracted text under profile documents, private permissions,
no symlinks or untrusted names, atomic writes. TDLib's returned path must be a regular file under
its session/files directory. Reuse proven remote identity and content hashes, keep publication links.
PDFBox handles embedded PDF text. DOCX uses bounded ZIP/document.xml and hardened XML reader.
TXT supports UTF8/BOM UTF16/validated Windows1251 fallback. Extraction runs in a child JVM with
heap and wall-clock limits; no secrets/stdout/stderr content, no external services.

SQLite FTS5 indexes bounded extracted text and filename. Local search explicitly covers only
processed downloads; results retain publication, snippet and highlights. Discovery uses existing
SourceReadService.recent (one bounded page, explicit continuation). User confirms discovered
supported records individually/in bounded batches; no implicit archive/history import.

UI: Documents under Local data, local search option from search screen; attachment controls in
shared dialog; separate scrollable text view; fetch file via protected owner-local endpoint without
exposing filesystem path. SSE controlled popover closes outside/Escape/navigation and preserves
focus/status. Missing links become subdued and project selection collapsible.

Alternatives rejected: full Tika/POI format suite is larger than PDF/DOCX/TXT MVP; cloud extraction
violates local privacy; mixing remote and local result sets risks implying all Telegram files searched.
OCR, audio transcription, vectors/AI deferred. No destructive data cleanup.

Verification: synthetic PDF/DOCX/TXT, fake TDLib, temporary SQLite/profile; lifecycle, limits,
path/scope isolation, deduplication, restart/migration/recovery, FTS, protected routes, UI regression.
Physical Telegram and actual documents remain manual. Hosted pipelines inspect once, no waiting.
