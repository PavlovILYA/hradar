Implementation: canonical browser origin hradar.localhost:1709, IPv4 loopback listener.
Reuse runtime and Ktor; no proxy/data migration. DesktopAccess holds process capability
and one-time launch code. Native restart/reopen handshake updates the process cookie
shared with all existing same-origin tabs in that browser profile; retry screen keeps
the root URL unchanged. Recovery validates current authority only; no durable bearer.
Marker HMAC challenge/response proves process identity before opening its launch URL.
Ruling after security review: durable HTTP recovery cookie could be stolen by a foreign
listener during downtime; removed in favor of native reauthentication without URL copying.
Tests: one-time code, recovery denial matrix, exact hosts/origins/CSRF, packaged browser
restart with persistent context, conflict/duplicate/shutdown. Dev uses 8080, smoke override.
Version: 0.6.0 from current master 0.5.0, reconcile if Stage C merges first.
