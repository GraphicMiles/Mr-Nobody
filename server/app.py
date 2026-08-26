#!/usr/bin/env python3
"""Mr Nobody update server.

A small, dependency-free HTTP service (deployed to Render, see render.yaml
and README.md). It serves release metadata and records anonymous active device
counts on update checks.

Endpoints
---------
GET /              website/index.html (the landing page)
GET /update.json   the release metadata the app checks on launch (+ records ping)
GET /stats         HTML dashboard of active devices and version distribution
GET /stats.json    JSON metrics (DAU, WAU, MAU, total unique devices)
GET /health        {"status": "ok"} (Render health check)
GET /<other>       any other static file under website/ (fonts, images, ...)

Security & Privacy model
------------------------
* Metadata only. This service never serves executable code.
* Anonymous device counting: install IDs are SHA-256 hashed before storage.
* No personal data, hardware identifiers, or location data stored.
* Database is local SQLite with zero external dependencies.
"""

import json
import mimetypes
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

# Load metrics store
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from metrics import MetricsStore

SITE = (ROOT.parent / "website").resolve()
MAX_BODY = 1024 * 1024  # 1 MiB hard cap on anything this service serves

UPDATE_JSON_HEADERS = {"Cache-Control": "no-cache"}
STATIC_HEADERS = {"Cache-Control": "public, max-age=3600"}

# Initialize SQLite metrics store
METRICS_DB_PATH = os.environ.get("METRICS_DB_PATH", str(ROOT / "metrics.db"))
metrics = MetricsStore(METRICS_DB_PATH)


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
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _send(self, code, body, headers=None):
        self.send_response(code)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not getattr(self, "_head_only", False):
            self.wfile.write(body)

    def _json(self, code, payload, extra=None):
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if extra:
            headers.update(extra)
        self._send(code, json.dumps(payload, indent=2).encode("utf-8"), headers)

    def do_GET(self):
        self._head_only = False
        self._handle_get()

    def do_HEAD(self):
        self._head_only = True
        self._handle_get()

    def _handle_get(self):
        try:
            self._route()
        except BrokenPipeError:
            pass
        except Exception:
            import traceback
            traceback.print_exc()
            try:
                self._json(500, {"error": "internal error"})
            except Exception:
                pass

    def _route(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/health":
            self._json(200, {"status": "ok"})
            return

        # Metrics endpoints
        if path == "/stats.json":
            summary = metrics.get_summary()
            self._json(200, summary, UPDATE_JSON_HEADERS)
            return

        if path == "/stats":
            html = metrics.render_html()
            headers = {
                "Content-Type": "text/html; charset=utf-8",
                "Cache-Control": "no-cache"
            }
            self._send(200, html.encode("utf-8"), headers)
            return

        # The app's update endpoint. When the app checks for updates,
        # record the ping to count active devices.
        if path == "/update.json":
            query = parse_qs(parsed.query)
            install_id = (
                query.get("id", [None])[0]
                or query.get("install_id", [None])[0]
                or self.headers.get("X-Install-ID")
            )
            app_version = (
                query.get("v", [None])[0]
                or query.get("version", [None])[0]
                or self.headers.get("X-App-Version")
            )
            client_ip = self.headers.get(
                "X-Forwarded-For", self.client_address[0]
            ).split(",")[0].strip()
            user_agent = self.headers.get("User-Agent", "")

            # Record anonymous device ping (non-blocking, never fails the request)
            try:
                metrics.record_ping(
                    install_id=install_id,
                    app_version=app_version,
                    client_ip=client_ip,
                    user_agent=user_agent
                )
            except Exception as e:
                sys.stderr.write("Metrics recording error: %s\n" % e)

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

    # No mutating methods allowed
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
