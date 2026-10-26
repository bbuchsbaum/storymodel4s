#!/usr/bin/env python3
"""Recall review workbench server: loopback only, single-use token exchanged for a cookie, no
request log, text only in POST bodies, no-store and a strict CSP (plan constraint 1).

Usage: python3 -I server.py MANIFEST PRIVATE_DIR [--port N]
  Prints one URL with a single-use token. Open it in a dedicated browser profile.
  --token T and --port are for the synthetic specimen's automated checks; --token is refused
  for any packet whose manifest is not marked synthetic.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse
import json
import mimetypes
import os
import re
import secrets
import subprocess
import sys
import threading

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from candidates import Candidates  # noqa: E402
from packet import Packet  # noqa: E402
from store import Store, StoreError  # noqa: E402

STATIC = HERE / "static"
COOKIE = "rw_session"
CSP = (
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; "
    "media-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; "
    "base-uri 'none'; form-action 'none'"
)
MEDIA_TYPES = {".webm": "video/webm", ".m4v": "video/mp4", ".mp4": "video/mp4"}


class App:
    def __init__(self, manifest, private_dir, token=None):
        self.packet = Packet(manifest)
        if token is not None and not self.packet.manifest.get("synthetic"):
            raise SystemExit("--token is only for synthetic packets")
        self.store = Store(self.packet, private_dir)
        self.cands = Candidates(self.packet)
        self.token = token or secrets.token_urlsafe(24)
        self.session = None
        self.lock = threading.Lock()
        self.frames = Path(private_dir) / "frames"
        self.frames.mkdir(mode=0o700, exist_ok=True)

    def exchange(self, token):
        """Single use: the first matching token mints the session and is then void."""
        with self.lock:
            if self.token is None or not secrets.compare_digest(token, self.token):
                return None
            self.token = None
            self.session = secrets.token_urlsafe(32)
            return self.session

    def state(self):
        s = self.store
        src = s.source_state()
        return {
            "recallOpen": s.recall_open(),
            "calibrationWindows": s.calibration_windows(),
            "maskedRows": sorted(s.masked_rows()) if s.recall_open() else [],
            "maskedWindows": s.masked_windows() if s.recall_open() else [],
            "revealedWindows": sorted(s.revealed_windows()),
            "source": {
                "rows": src["rows"],
                "scenes": src["scenes"],
                "unnamed": src["unnamed"],
                "mentions": s.mentions,
            },
            "people": s.people() if s.recall_open() else [],
            "windows": {str(k): v for k, v in s.windows_read().items()},
            "decisions": s.decisions(),
            "calibration": s.calibration(),
            "lastEvent": s.events[-1]["seq"],
        }

    def api(self, path, body):
        s = self.store
        if path == "/api/index":
            # Calibration-window words carry no timing or nominations in the index at all; the
            # client never holds them (v3 function review, B9).
            idx = self.packet.index()
            cal = s.calibration_rows()
            for w in idx["words"]:
                if w["row"] in cal:
                    w.update(t=None, tr=None, boundary=False, cal=True, nom={"one": [], "zero": [], "legacy": []})
            return idx
        if path == "/api/film-text":
            return self.packet.film_text()
        if path == "/api/recall-text":
            if not s.recall_open():
                raise StoreError("the source pass must be complete before recall opens")
            s.note_recall_opened()  # exposure order is recorded for the export
            return {"words": self.packet.recall_text()}
        if path == "/api/state":
            return self.state()
        if path == "/api/search":
            sp = body.get("span")
            win = body.get("window")
            if s.recall_open() and s.suggestions_blind(
                body.get("id"), tuple(sp) if sp else None, int(win) if win is not None else None
            ):
                raise StoreError(
                    "search is hidden in a calibration window and while a calibration item is open"
                )
            hits = self.cands.search(str(body.get("q", "")))
            # Only a recall-pass search (with a window, span or decision) is a recall exposure.
            if s.recall_open() and any(x is not None for x in (win, sp, body.get("id"))):
                s.searched(body.get("id"), str(body.get("q", "")), sp, win, [h["row"] for h in hits])
            return {"hits": hits}
        if path == "/api/source/row":
            s.source_row(
                int(body["row"]),
                body.get("present", []),
                body.get("unnamed", []),
                body.get("mentions", {}),
                bool(body.get("done")),
            )
        elif path == "/api/source/unnamed":
            e = s.create_unnamed(body.get("label"), body.get("scene"))
            return {"uid": e["uid"], "state": self.state()}
        elif path == "/api/candidates":
            if not s.recall_open():
                raise StoreError("the source pass must be complete before recall opens")
            did = body.get("id")
            # The server decides blindness; the client's flag can only add to it.
            span = (int(body["first"]), int(body["last"]))
            hidden = bool(body.get("hidden")) or s.suggestions_blind(did, span)
            out = self.cands.for_span(span[0], span[1], masked=s.masked_rows())
            s.shown(
                did,
                out["span"],
                [] if hidden else [m["row"] for m in out["moments"]],
                hidden,
            )
            if hidden:
                out["moments"] = []
                out["nominations"] = {"one": [], "zero": [], "legacy": []}
                out.pop("considered", None)
            out["hidden"] = hidden
            return out
        elif path == "/api/window":
            s.read_window(int(body["window"]), bool(body.get("value", True)))
        elif path == "/api/person":
            e = s.create_person(body.get("label"))
            return {"id": e["pid"], "state": self.state()}
        elif path == "/api/person/rename":
            s.rename_person(body.get("id"), body.get("label"))
        elif path == "/api/decision/start":
            sp = body.get("span")
            e = s.start_decision([int(sp[0]), int(sp[1])] if sp else None)
            return {
                "id": e["id"],
                "calibration": e["calibration"],
                "state": self.state(),
            }
        elif path == "/api/decision/reference":
            s.commit_reference(body["id"], body["fields"])
        elif path == "/api/calibration/reveal":
            s.reveal_window(int(body["window"]))
        elif path == "/api/decision/source":
            s.commit_source(body["id"], body["fields"])
        elif path == "/api/decision/delete":
            s.delete(body["id"])
        elif path == "/api/duplicates":
            return {
                "ids": s.duplicates(
                    body.get("id"),
                    int(body["first"]),
                    int(body["last"]),
                    body.get("referent_ids") or [],
                )
            }
        elif path == "/api/undo":
            e = s.undo()
            undone = {k: e.get(k) for k in ("kind", "id", "row", "window")}
            return {"undone": undone, "state": self.state()}
        elif path == "/api/export":
            out = s.write_export()
            return {
                "folder": out.name,
                "written": [p.name for p in sorted(out.iterdir())],
                "state": self.state(),
            }
        else:
            raise KeyError(path)
        return {"state": self.state()}

    def frame(self, part, ms):
        path = self.frames / f"p{part}-{ms}.jpg"
        if not path.exists():
            tmp = path.with_suffix(".tmp.jpg")
            subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-y",
                    "-ss",
                    f"{ms / 1000:.3f}",
                    "-i",
                    str(self.packet.media[part]),
                    "-frames:v",
                    "1",
                    "-vf",
                    "scale=320:-2",
                    "-q:v",
                    "5",
                    str(tmp),
                ],
                check=True,
            )
            os.chmod(tmp, 0o600)
            tmp.replace(path)
        return path.read_bytes()


def handler(app, port):
    hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class H(BaseHTTPRequestHandler):
        server_version = "review-workbench"
        sys_version = ""

        def log_message(
            self, *args
        ):  # no request log: lines would carry ids and tokens
            pass

        def log_error(self, *args):
            pass

        def send_error(self, code, message=None, explain=None):
            # Errors raised by the base class (bad method, bad request line) get the same
            # no-store and CSP headers as every other response (Codex review follow-up).
            self.close_connection = True
            self._send(code, (message or "error").encode("utf-8", "replace"))

        def _headers(self, code, ctype, length, extra=()):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Security-Policy", CSP)
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Cross-Origin-Resource-Policy", "same-origin")
            for k, v in extra:
                self.send_header(k, v)
            self.end_headers()

        def _send(self, code, body: bytes, ctype="text/plain; charset=utf-8", extra=()):
            self._headers(code, ctype, len(body), extra)
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, code, obj):
            self._send(code, json.dumps(obj).encode(), "application/json")

        def _guard(self):
            if self.headers.get("Host") not in hosts:
                self._send(421, b"wrong host")
                return False
            return True

        def _authed(self):
            cookie = self.headers.get("Cookie", "")
            m = re.search(rf"(?:^|;\s*){COOKIE}=([A-Za-z0-9_-]+)", cookie)
            return bool(
                app.session and m and secrets.compare_digest(m.group(1), app.session)
            )

        def do_GET(self):
            if not self._guard():
                return
            path, _, query = self.path.partition("?")
            if path == "/" and query.startswith("token="):
                sid = app.exchange(query[len("token=") :])
                if sid is None:
                    return self._send(
                        403,
                        b"This link has been used. Restart the server for a new one.",
                    )
                cookie = f"{COOKIE}={sid}; HttpOnly; SameSite=Strict; Path=/"
                return self._send(
                    303, b"", extra=[("Location", "/"), ("Set-Cookie", cookie)]
                )
            if not self._authed():
                return self._send(
                    403, b"Open the link printed in the server's terminal."
                )
            if path == "/":
                path = "/static/index.html"
            if path.startswith("/static/"):
                f = (STATIC / path[len("/static/") :]).resolve()
                if STATIC not in f.parents or not f.is_file():
                    return self._send(404, b"not found")
                ctype = mimetypes.guess_type(f.name)[0] or "application/octet-stream"
                if ctype.startswith("text/") or ctype.endswith("javascript"):
                    ctype += "; charset=utf-8"
                return self._send(200, f.read_bytes(), ctype)
            m = re.fullmatch(r"/media/(\d+)", path)
            if m and m.group(1) in app.packet.media:
                return self._media(app.packet.media[m.group(1)])
            m = re.fullmatch(r"/frame/(\d+)/(\d+)\.jpg", path)
            if m and m.group(1) in app.packet.media:
                return self._send(
                    200, app.frame(m.group(1), int(m.group(2))), "image/jpeg"
                )
            self._send(404, b"not found")

        def _media(self, f: Path):
            size = f.stat().st_size
            ctype = MEDIA_TYPES.get(f.suffix, "application/octet-stream")
            rng = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
            if rng and (rng.group(1) or rng.group(2)):
                if rng.group(1):
                    a = int(rng.group(1))
                    b = min(int(rng.group(2)) if rng.group(2) else size - 1, size - 1)
                else:
                    a, b = max(0, size - int(rng.group(2))), size - 1
                if a > b or a >= size:
                    return self._send(
                        416, b"", extra=[("Content-Range", f"bytes */{size}")]
                    )
                code, extra = 206, [("Content-Range", f"bytes {a}-{b}/{size}")]
            else:
                a, b, code, extra = 0, size - 1, 200, []
            self._headers(code, ctype, b - a + 1, [("Accept-Ranges", "bytes"), *extra])
            with open(f, "rb") as fh:
                fh.seek(a)
                left = b - a + 1
                while left > 0:
                    chunk = fh.read(min(left, 1 << 16))
                    if not chunk:
                        break
                    try:
                        self.wfile.write(chunk)
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    left -= len(chunk)

        def do_POST(self):
            if not self._guard():
                return
            if not self._authed():
                return self._send(403, b"forbidden")
            origin = self.headers.get("Origin")
            if origin is not None and origin.split("://", 1)[-1] not in hosts:
                return self._send(403, b"cross-origin request refused")
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
                with app.lock:
                    out = app.api(self.path, body)
                self._json(200, out)
            except StoreError as e:
                self._json(409, {"error": str(e)})
            except KeyError:
                self._json(404, {"error": "unknown request"})
            except (ValueError, TypeError) as e:
                self._json(400, {"error": type(e).__name__})

    return H


class Server(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = (
        64  # the UI loads its modules in parallel; a backlog of 5 resets some
    )

    def handle_error(self, request, client_address):
        # Never print a traceback: its locals and messages could carry request bodies.
        print(f"request failed: {sys.exc_info()[0].__name__}", file=sys.stderr)


def serve(manifest, private_dir, port=0, token=None, announce=True):
    app = App(manifest, private_dir, token)
    srv = Server(("127.0.0.1", port), None)
    port = srv.server_address[1]
    srv.RequestHandlerClass = handler(app, port)
    if announce:
        print(f"http://127.0.0.1:{port}/?token={app.token}", flush=True)
    return srv, app


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("manifest")
    ap.add_argument("private_dir")
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--token")
    a = ap.parse_args(argv)
    srv, _ = serve(a.manifest, a.private_dir, a.port, a.token)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
