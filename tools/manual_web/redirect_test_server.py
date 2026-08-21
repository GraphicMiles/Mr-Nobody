#!/usr/bin/env python3
"""Serve the controlled on-device redirect test without adding another doc file."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PAGE = b'''<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mr Nobody redirect-control test</title>
<style>
  body { max-width: 42rem; margin: 2rem auto; padding: 0 1rem; font: 16px/1.5 sans-serif; }
  a, button { display: block; margin: .8rem 0; padding: .7rem; }
</style>
<h1>Redirect-control test</h1>
<p>This checked-in server controls the trigger. A blocked destination does not need to respond: its request must be refused locally.</p>
<h2>Must remain allowed</h2>
<a href="/?ordinary=same-tab">Ordinary same-site link</a>
<a href="/?ordinary=new-window" target="_blank">Ordinary target=_blank link (open in this tab)</a>
<h2>Must be blocked with a notice</h2>
<a href="https://googleadservices.com/pagead/aclk?sa=L">Listed top-level ad host</a>
<a href="https://www.google-analytics.com/analytics.js">Listed top-level tracker host</a>
<a href="https://stake.com/casino">Cross-site Stake redirect</a>
<a href="https://www.bet9ja.com/sports">Cross-site Bet9ja redirect</a>
<a href="https://betnaija.com/offer">Cross-site betnaija redirect</a>
<a href="/redirect-bet">Server redirect to Bet9ja</a>
<form method="post" action="https://sports.bet9ja.com/">
  <button type="submit">POST navigation to Bet9ja</button>
</form>
<button onclick="location.href='https://stake.com/casino'">Scripted main-page betting redirect</button>
<button onclick="window.open('https://stake.com/casino','_blank')">Scripted advertising popup</button>
<p id="marker"></p>
<script>document.getElementById('marker').textContent = 'Loaded controlled page: ' + location.href;</script>
'''


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/redirect-bet"):
            self.send_response(302)
            self.send_header("Location", "https://sports.bet9ja.com/?btag=controlled-test")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)

    def log_message(self, format, *args):
        print("redirect-test:", format % args)


if __name__ == "__main__":
    print("Serving redirect test at http://0.0.0.0:8765/")
    ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
