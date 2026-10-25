#!/usr/bin/env python3
"""Courts for the recall review workbench on the synthetic specimen only (rule 14d): packet
pins, the four candidate signals, the store's gate, calibration and export, and the server's
custody guards (loopback host, single-use token, cookie, no request log, POST-only text,
no-store, CSP).
Run: python3 -m unittest discover -s tools/review_workbench
"""
import contextlib
import csv
import http.client
import io
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import candidates  # noqa: E402
import packet  # noqa: E402
import server  # noqa: E402
import store  # noqa: E402
import synth  # noqa: E402

TMP = tempfile.TemporaryDirectory()
SPEC = Path(TMP.name) / "spec"
synth.build(SPEC, media=False)
for p in (1, 2):
    (SPEC / f"film-part{p}.webm").write_bytes(
        bytes(range(256)) * 40
    )  # stand-in bytes for Range
MANIFEST = SPEC / "manifest.json"
_n = iter(range(10_000))


def private():
    return Path(TMP.name) / f"private-{next(_n)}"


def fresh_store():
    return store.Store(packet.Packet(MANIFEST), private())


def done_store():
    s = fresh_store()
    for r in s.p.rows:
        res = {m["id"]: "unknown" for m in s.mentions[r["row"]]}
        s.source_row(r["row"], r["names"], [], res, True)
    return s


def span_of(words, phrase):
    toks = phrase.split()
    for i in range(len(words) - len(toks) + 1):
        if [w.lower() for w in words[i : i + len(toks)]] == toks:
            return i + 1, i + len(toks)
    raise AssertionError(phrase)


REF = {
    "other_coref_spans": [],
    "reference_form": "pronoun",
    "claim_role": "agent",
    "occurrence_group": "g1",
    "person_reference": "yes",
    "referent": "Mara",
}


class PacketCourt(unittest.TestCase):
    def test_index_carries_no_recall_text(self):
        p = packet.Packet(MANIFEST)
        values = set()

        def walk(x):
            if isinstance(x, dict):
                for v in x.values():
                    walk(v)
            elif isinstance(x, list):
                for v in x:
                    walk(v)
            elif isinstance(x, str):
                values.add(x.lower())

        index = p.index()
        walk(index)
        vocabulary = {v for vs in packet.DECLARED.values() for v in vs} | set(
            index["columns"]
        )
        leaked = values & {w.lower() for w in p.recall_text()}
        self.assertLessEqual(
            leaked, vocabulary
        )  # only declared values such as "no" coincide
        self.assertTrue(p.recall_text())

    def test_pin_mismatch_is_refused(self):
        m = json.loads(MANIFEST.read_text())
        m["inputs"]["recall"]["sha256"] = "0" * 64
        bad = SPEC / "bad-manifest.json"
        bad.write_text(json.dumps(m))
        with self.assertRaisesRegex(packet.PacketError, "recall"):
            packet.Packet(bad)

    def test_rows_without_tr_keep_their_part(self):
        p = packet.Packet(MANIFEST)
        self.assertEqual({r["part"] for r in p.rows}, {1, 2})
        self.assertTrue(all(r["scenes"] for r in p.rows))


class CandidateCourt(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.p = packet.Packet(MANIFEST)
        cls.c = candidates.Candidates(cls.p)
        cls.words = cls.p.recall_text()

    def kinds(self, out, row):
        m = next((m for m in out["moments"] if m["row"] == row), None)
        return {s["kind"] for s in m["signals"]} if m else set()

    def test_dialogue_echo_points_at_the_spoken_row(self):
        a, b = span_of(self.words, "i have no brother")
        out = self.c.for_span(a, b)
        self.assertEqual(out["moments"][0]["row"], 12)
        self.assertIn("echo", self.kinds(out, 12))

    def test_function_word_runs_are_not_echoes(self):
        # "on the rocks" recurs in row 8's dialogue but names nothing.
        a, b = span_of(self.words, "on the rocks")
        out = self.c.for_span(a, b)
        self.assertNotIn("echo", self.kinds(out, 8))

    def test_nomination_reaches_every_row_of_the_scene(self):
        a, b = span_of(self.words, "climbs outside to fix it")
        out = self.c.for_span(a, b)
        self.assertIn(5, out["nominations"]["one"])
        for row in self.p.scenes[4]["rows"]:
            self.assertIn(
                "nomination", self.kinds(self.c.for_span(a, b, limit=50), row)
            )

    def test_candidates_never_name_persons(self):
        a, b = span_of(self.words, "she saves him")
        blob = json.dumps(self.c.for_span(a, b))
        for person in self.p.people:
            self.assertNotIn(person, blob)

    def test_search_finds_annotation_and_dialogue(self):
        hits = self.c.search("lantern rocks")
        self.assertEqual(hits[0]["row"], 20)
        self.assertTrue(any(h["kind"] == "line" for h in self.c.search("north road")))


class StoreCourt(unittest.TestCase):
    def test_recall_is_closed_until_every_source_row_is_done(self):
        s = fresh_store()
        with self.assertRaisesRegex(store.StoreError, "source pass"):
            s.start_decision()
        rows = s.p.rows
        for r in rows[:-1]:
            s.source_row(
                r["row"],
                r["names"],
                [],
                {m["id"]: "unknown" for m in s.mentions[r["row"]]},
                True,
            )
        self.assertFalse(s.recall_open())
        last = rows[-1]
        s.source_row(
            last["row"],
            last["names"],
            [],
            {m["id"]: "unknown" for m in s.mentions[last["row"]]},
            True,
        )
        self.assertTrue(s.recall_open())

    def test_a_row_is_done_only_with_every_mention_resolved(self):
        s = fresh_store()
        row = next(r for r in s.p.rows if s.mentions[r["row"]])
        with self.assertRaisesRegex(store.StoreError, "every mention"):
            s.source_row(row["row"], row["names"], [], {}, True)

    def test_present_names_come_from_the_annotation(self):
        s = fresh_store()
        with self.assertRaisesRegex(store.StoreError, "Name - All"):
            s.source_row(1, ["Invented Person"], [], {}, False)

    def test_calibration_items_are_the_first_five_then_every_tenth(self):
        s = done_store()
        flags = [s.start_decision()["calibration"] for _ in range(21)]
        self.assertEqual(
            [i + 1 for i, f in enumerate(flags) if f], [1, 2, 3, 4, 5, 10, 20]
        )

    def test_calibration_revision_needs_a_reveal_and_is_counted(self):
        s = done_store()
        a, b = span_of(s.p.recall_text(), "she grabs it")
        did = s.start_decision()["id"]
        s.commit_reference(did, {**REF, "first_word": a, "last_word": a})
        with self.assertRaisesRegex(store.StoreError, "reveal"):
            s.commit_reference(
                did, {**REF, "first_word": a, "last_word": a, "referent": "Tobias"}
            )
        s.reveal(did)
        s.commit_reference(
            did, {**REF, "first_word": a, "last_word": a, "referent": "Tobias"}
        )
        cal = s.calibration()
        self.assertEqual((cal["items"], cal["revised"]), (1, 1))
        self.assertEqual(cal["byForm"]["pronoun"], {"items": 1, "revised": 1})

    def test_declared_values_are_enforced(self):
        s = done_store()
        did = s.start_decision()["id"]
        with self.assertRaisesRegex(store.StoreError, "reference_form"):
            s.commit_reference(
                did, {**REF, "first_word": 1, "last_word": 1, "reference_form": "noun"}
            )
        with self.assertRaisesRegex(store.StoreError, "referent"):
            s.commit_reference(
                did, {**REF, "first_word": 1, "last_word": 1, "referent": "Nobody"}
            )

    def test_spans_may_cross_windows(self):
        s = done_store()
        w = s.p.windows[0]
        did = s.start_decision()["id"]
        s.commit_reference(
            did, {**REF, "first_word": w["last"], "last_word": w["last"] + 1}
        )
        self.assertIn(did, s.decisions())

    def test_undo_appends_and_restores(self):
        s = done_store()
        did = s.start_decision()["id"]
        s.commit_reference(did, {**REF, "first_word": 3, "last_word": 3})
        n = len(s.events)
        s.undo()
        self.assertEqual(len(s.events), n + 1)
        self.assertNotIn(did, s.decisions())

    def test_duplicates_overlap_with_the_same_referent(self):
        s = done_store()
        d1 = s.start_decision()["id"]
        s.commit_reference(d1, {**REF, "first_word": 3, "last_word": 5})
        self.assertEqual(s.duplicates(None, 5, 6, "Mara"), [d1])
        self.assertEqual(s.duplicates(None, 5, 6, "Tobias"), [])

    def test_export_matches_the_template_and_withholds_the_referent(self):
        s = done_store()
        did = s.start_decision()["id"]
        s.commit_reference(did, {**REF, "first_word": 3, "last_word": 3})
        s.commit_source(
            did,
            {
                "source_scene_ids": [1],
                "source_anchor_ids": ["row-1"],
                "source_resolvable": "yes",
                "reference_outcome": "supported",
                "ambiguity_reason": "",
            },
        )
        s.read_window(1)
        out = s.export()
        header = next(csv.reader(io.StringIO(out["decisions.csv"])))
        self.assertEqual(header, synth.DECISION_COLS)
        self.assertNotIn("Mara", out["decisions.csv"])
        self.assertFalse(out["referentExported"])
        row = list(csv.DictReader(io.StringIO(out["decisions.csv"])))[0]
        self.assertEqual(
            (row["reviewer"], row["reference_outcome"]),
            ("synthetic-reviewer", "supported"),
        )
        windows = list(csv.DictReader(io.StringIO(out["review-windows.csv"])))
        self.assertEqual(windows[0]["reviewed"], "yes")

    def test_log_and_private_dir_modes(self):
        s = fresh_store()
        self.assertEqual(s.dir.stat().st_mode & 0o777, 0o700)
        self.assertEqual(s.path.stat().st_mode & 0o777, 0o600)

    def test_a_log_from_another_packet_is_refused(self):
        s = fresh_store()
        head = json.loads(s.path.read_text().splitlines()[0])
        head["manifest"] = "f" * 64
        s.path.write_text(json.dumps(head) + "\n")
        with self.assertRaisesRegex(store.StoreError, "different packet"):
            store.Store(packet.Packet(MANIFEST), s.dir)


class ServerCourt(unittest.TestCase):
    def setUp(self):
        self.err = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()):
            self.srv, self.app = server.serve(MANIFEST, private(), announce=False)
        self.port = self.srv.server_address[1]
        self.token = self.app.token
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def req(self, method, path, body=None, host=None, cookie=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        h = {"Host": host or f"127.0.0.1:{self.port}", **(headers or {})}
        if cookie:
            h["Cookie"] = cookie
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        c.request(method, path, body=data, headers=h)
        r = c.getresponse()
        out = (r.status, dict(r.getheaders()), r.read())
        c.close()
        return out

    def login(self):
        status, headers, _ = self.req("GET", f"/?token={self.token}")
        self.assertEqual(status, 303)
        return headers["Set-Cookie"].split(";")[0], headers["Set-Cookie"]

    def test_foreign_host_is_refused(self):
        self.assertEqual(self.req("GET", "/", host="evil.example")[0], 421)

    def test_no_cookie_no_access(self):
        self.assertEqual(self.req("GET", "/")[0], 403)
        self.assertEqual(self.req("POST", "/api/index", {})[0], 403)

    def test_token_is_single_use_and_cookie_is_strict(self):
        cookie, raw = self.login()
        self.assertIn("HttpOnly", raw)
        self.assertIn("SameSite=Strict", raw)
        self.assertEqual(self.req("GET", f"/?token={self.token}")[0], 403)
        self.assertEqual(self.req("GET", "/", cookie=cookie)[0], 200)

    def test_every_response_is_no_store_with_csp(self):
        cookie, _ = self.login()
        for method, path, body in [
            ("GET", "/", None),
            ("POST", "/api/index", {}),
            ("GET", "/x", None),
        ]:
            _, headers, _ = self.req(method, path, body, cookie=cookie)
            self.assertEqual(headers["Cache-Control"], "no-store")
            self.assertIn("default-src 'self'", headers["Content-Security-Policy"])

    def test_text_is_never_served_by_get(self):
        cookie, _ = self.login()
        for path in ("/api/recall-text", "/api/film-text", "/api/search"):
            self.assertEqual(self.req("GET", path, cookie=cookie)[0], 404)

    def test_recall_text_waits_for_the_source_pass(self):
        cookie, _ = self.login()
        status, _, body = self.req("POST", "/api/recall-text", {}, cookie=cookie)
        self.assertEqual(status, 409)
        self.assertIn(b"source pass", body)

    def test_cross_origin_post_is_refused(self):
        cookie, _ = self.login()
        status = self.req(
            "POST",
            "/api/index",
            {},
            cookie=cookie,
            headers={"Origin": "http://evil.example"},
        )[0]
        self.assertEqual(status, 403)

    def test_requests_write_nothing_to_the_terminal(self):
        err, out = io.StringIO(), io.StringIO()
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
            cookie, _ = self.login()
            self.req(
                "POST", "/api/search", {"q": "lantern secret-query"}, cookie=cookie
            )
            self.req("GET", "/missing", cookie=cookie)
        self.assertEqual(err.getvalue() + out.getvalue(), "")

    def test_media_supports_ranges(self):
        cookie, _ = self.login()
        status, headers, body = self.req(
            "GET", "/media/1", cookie=cookie, headers={"Range": "bytes=10-19"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(headers["Content-Range"], f"bytes 10-19/{256 * 40}")
        self.assertEqual(body, bytes(range(10, 20)))


if __name__ == "__main__":
    unittest.main()
