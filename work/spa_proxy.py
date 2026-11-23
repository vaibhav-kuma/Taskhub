#!/usr/bin/env python3
"""Tiny same-origin dev proxy: serves web/ and forwards /api to the Spring backend.

Used only to verify the browser client locally (nginx does this in Docker).
    python3 work/spa_proxy.py 8090 http://127.0.0.1:8080
"""
import http.server
import os
import socketserver
import sys
import urllib.error
import urllib.request

WEB_ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")
UPSTREAM = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8080"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8090


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=WEB_ROOT, **kwargs)

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        url = UPSTREAM + self.path
        req = urllib.request.Request(url, data=body, method=self.command)
        for header in ("Content-Type", "Accept", "Authorization", "X-Request-Id", "X-Forwarded-For", "X-Forwarded-Proto"):
            if self.headers.get(header):
                req.add_header(header, self.headers[header])
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                payload = response.read()
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() in ("content-type", "x-request-id", "retry-after"):
                        self.send_header(key, value)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(payload)
        except urllib.error.HTTPError as error:
            payload = error.read()
            self.send_response(error.code)
            self.send_header("Content-Type", error.headers.get("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as error:  # noqa: BLE001
            message = f'{{"status":502,"code":"BAD_GATEWAY","message":"{error}"}}'.encode()
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(message)))
            self.end_headers()
            self.wfile.write(message)

    def do_GET(self):
        if self.path.startswith("/api/"):
            self._proxy()
        else:
            super().do_GET()

    def do_POST(self):
        self._proxy()

    def do_PATCH(self):
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def do_OPTIONS(self):
        self._proxy()

    def log_message(self, fmt, *args):
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))


if __name__ == "__main__":
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.ThreadingTCPServer(("127.0.0.1", PORT), Handler) as httpd:
        print(f"SPA proxy on http://127.0.0.1:{PORT} -> {UPSTREAM}", flush=True)
        httpd.serve_forever()
