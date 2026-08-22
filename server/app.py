#!/usr/bin/env python3
"""Mr Nobody update server.

A small, dependency-free HTTP service (deployed to Render, see render.yaml
and README.md). It has exactly one job: tell the app what the latest
release is.

Endpoints
---------
GET /              website/index.html (the landing page)
GET /update.json   the release metadata the app checks on launch
GET /health        {"status": "ok"} (Render health check)
GET /<other>       any other static file under website/ (fonts, images, ...)

Security model
--------------
* Metadata only. This service never serves executable code and never
  instructs the app to run anything. An update is a signed APK the user
  installs through Android's normal package installer, which verifies the
  signature itself.
* No writes, no state, no user input: every mutating method is refused.
* Static paths are resolved under website/ and path traversal is rejected.
* update.json is served with no-cache so a new release is visible on the
  app's next launch check; everything else may be cached for an hour.
"""

import json
import mimetypes
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parent
SITE = (ROOT.parent / "website").resolve()
MAX_BODY = 1024 * 1024  # 1 MiB hard cap on anything this service serves

UPDATE_JSON_HEADERS = {"Cache-Control": "no-cache"}
STATIC_HEADERS = {"Cache-Control": "public, max-age=3600"}


def _site_file(raw_path):
    """Map a request path to a file under SITE, or None (404 / traversal)."""
    rel = unquote(urlparse(raw_path).path)
    if rel in ("", "/"):
        return SITE / "index.html"
    rel = rel.lstrip("/")
    target = (SITE / rel).resolve()
    if not target.is_file() or not target.is_relative_to(SITE):
        return None
    return target


class Handler(BaseHTTPRequestHandler):
    server_version = "MrNobodyUpdate/1.0"

    # HTTP/1.1 with keep-alive: Render's router pools origin connections.
    # The default HTTP/1.0 + Connection: close makes the pool route a
    # request onto a connection we just closed, which the router reports
    # as a 404 "no-server". Every response below carries Content-Length,
    # so keep-alive framing is unambiguous.
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _send(self, code, body, headers=None):
        self.send_response(code)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        # HEAD must send the headers (incl. the real Content-Length) but no
        # body. The flag is checked here — after end_headers() — so the
        # headers themselves are not swallowed.
        if not getattr(self, "_head_only", False):
            self.wfile.write(body)

    def _json(self, code, payload, extra=None):
        headers = {"Content-Type": "application/json"}
        if extra:
            headers.update(extra)
        self._send(code, json.dumps(payload).encode("utf-8"), headers)

    def do_GET(self):
        self._head_only = False
        self._handle_get()

    def do_HEAD(self):
        # Same routing as GET without a body, for well-behaved clients.
        self._head_only = True
        self._handle_get()

    def _handle_get(self):
        try:
            self._route()
        except BrokenPipeError:
            pass  # client went away; nothing to send
        except Exception:
            # Never let one bad request take the worker down: answer 500
            # and keep serving. The traceback lands in the service logs.
            import traceback
            traceback.print_exc()
            try:
                self._json(500, {"error": "internal error"})
            except Exception:
                pass

    def _route(self):
        path = urlparse(self.path).path
        if path == "/health":
            self._json(200, {"status": "ok"})
            return

        # The app's update endpoint. website/update.json is the single
        # source of truth — the landing page links to it and the app polls
        # it, so both always agree.
        if path == "/update.json":
            target = SITE / "update.json"
            if not target.is_file():
                self._json(404, {"error": "update.json missing"})
                return
            body = target.read_bytes()
            if len(body) > MAX_BODY:
                self._json(500, {"error": "update.json too large"})
                return
            headers = {"Content-Type": "application/json"}
            headers.update(UPDATE_JSON_HEADERS)
            self._send(200, body, headers)
            return

        target = _site_file(self.path)
        if target is None:
            self._json(404, {"error": "not found"})
            return
        body = target.read_bytes()
        if len(body) > MAX_BODY:
            self._json(413, {"error": "file too large"})
            return
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        headers = {"Content-Type": ctype}
        headers.update(STATIC_HEADERS)
        self._send(200, body, headers)

    # No writes, no dynamic state: refuse everything that mutates.
    def do_POST(self):
        self._json(405, {"error": "method not allowed"})

    do_PUT = do_POST
    do_DELETE = do_POST
    do_PATCH = do_POST


def main():
    port = int(os.environ.get("PORT", "8080"))
    if not (SITE / "index.html").is_file():
        print("refusing to start: website/index.html not found", file=sys.stderr)
        sys.exit(1)
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("Mr Nobody update server listening on 0.0.0.0:%d" % port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
