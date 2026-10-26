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
    "referent_status": "identified",
    "referent_ids": [store.Store.annotation_id("Mara")],
}
TOBIAS = {"referent_status": "identified", "referent_ids": [store.Store.annotation_id("Tobias")]}


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

    def test_every_row_is_reachable_from_some_scene(self):
        # The complete-all gate needs every row; the source pass reaches rows only via scenes.
        p = packet.Packet(MANIFEST)
        in_scenes = {r for sc in p.scenes for r in sc["rows"]}
        self.assertEqual(in_scenes, {r["row"] for r in p.rows})
        tr_less = [r for r in p.rows if r.get("sceneByTime")]
        self.assertEqual(len(tr_less), 1)
        self.assertEqual(tr_less[0]["scenes"], [5])

    def test_the_gate_opens_by_walking_scenes(self):
        s = fresh_store()
        for sc in s.p.scenes:
            for row in sc["rows"]:
                r = next(x for x in s.p.rows if x["row"] == row)
                s.source_row(row, r["names"], [], {m["id"]: "unknown" for m in s.mentions[row]}, True)
        self.assertTrue(s.recall_open())

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

    def test_a_person_reference_needs_a_referent(self):
        s = done_store()
        did = s.start_decision()["id"]
        with self.assertRaisesRegex(store.StoreError, "exactly one"):
            s.commit_reference(
                did, {**REF, "first_word": 33, "last_word": 33, "referent_ids": []}
            )
        with self.assertRaisesRegex(store.StoreError, "requires person"):
            s.commit_reference(
                did, {**REF, "first_word": 33, "last_word": 33, "person_reference": "no"}
            )
        s.commit_reference(
            did,
            {
                **REF,
                "first_word": 33,
                "last_word": 33,
                "referent_status": "not_person",
                "referent_ids": [],
                "person_reference": "no",
            },
        )

    def test_declared_values_are_enforced(self):
        s = done_store()
        did = s.start_decision()["id"]
        with self.assertRaisesRegex(store.StoreError, "reference_form"):
            s.commit_reference(
                did, {**REF, "first_word": 31, "last_word": 31, "reference_form": "noun"}
            )
        with self.assertRaisesRegex(store.StoreError, "unknown referent id"):
            s.commit_reference(
                did, {**REF, "first_word": 31, "last_word": 31, "referent_ids": ["ann-nobody"]}
            )

    def test_spans_may_cross_windows(self):
        s = done_store()
        w = s.p.windows[1]
        did = s.start_decision()["id"]
        s.commit_reference(
            did, {**REF, "first_word": w["last"], "last_word": w["last"] + 1}
        )
        self.assertIn(did, s.decisions())

    def test_undo_appends_and_restores(self):
        s = done_store()
        did = s.start_decision()["id"]
        s.commit_reference(did, {**REF, "first_word": 33, "last_word": 33})
        n = len(s.events)
        s.undo()
        self.assertEqual(len(s.events), n + 1)
        self.assertNotIn(did, s.decisions())

    def test_duplicates_overlap_with_the_same_referent(self):
        s = done_store()
        d1 = s.start_decision()["id"]
        s.commit_reference(d1, {**REF, "first_word": 33, "last_word": 35})
        self.assertEqual(s.duplicates(None, 35, 36, REF["referent_ids"]), [d1])
        self.assertEqual(s.duplicates(None, 35, 36, TOBIAS["referent_ids"]), [])

    def test_export_is_schema_v2_with_ids_not_labels(self):
        s = done_store()
        did = s.start_decision()["id"]
        s.commit_reference(did, {**REF, "first_word": 33, "last_word": 33})
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
        self.assertEqual(header, synth.DECISION_COLS + ["referent_status", "referent_ids"])
        self.assertNotIn("Mara", out["decisions.csv"])  # labels live only in the registry
        row = list(csv.DictReader(io.StringIO(out["decisions.csv"])))[0]
        self.assertEqual(row["referent_status"], "identified")
        self.assertEqual(json.loads(row["referent_ids"]), REF["referent_ids"])
        reg = json.loads(out["referents.json"])
        self.assertIn({"id": REF["referent_ids"][0], "label": "Mara", "origin": "annotation entry"}, reg["referents"])
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

    def test_base_class_errors_carry_custody_headers(self):
        cookie, _ = self.login()
        status, headers, _ = self.req("PUT", "/api/index", {}, cookie=cookie)
        self.assertEqual(status, 501)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertIn("default-src 'self'", headers["Content-Security-Policy"])

    def test_media_supports_ranges(self):
        cookie, _ = self.login()
        status, headers, body = self.req(
            "GET", "/media/1", cookie=cookie, headers={"Range": "bytes=10-19"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(headers["Content-Range"], f"bytes 10-19/{256 * 40}")
        self.assertEqual(body, bytes(range(10, 20)))


class CalibrationCourt(unittest.TestCase):
    """Calibration is decided by words: windows 1 and 8 of the synthetic recall are masked
    until a decision on them commits blind and reveals (v2 function review, B3 and B4)."""

    SRC = {"source_scene_ids": [1], "source_anchor_ids": ["row-1"], "source_resolvable": "yes",
           "reference_outcome": "supported", "ambiguity_reason": ""}

    def test_calibration_windows_are_fixed_and_masked(self):
        s = done_store()
        self.assertEqual(s.calibration_windows(), [1, 8])
        self.assertIn(8, s.masked_rows())
        self.assertNotIn(30, s.masked_rows())

    def test_a_start_on_masked_words_is_calibration_whatever_the_count(self):
        s = done_store()
        ordinary = s.start_decision([30, 30])
        self.assertFalse(ordinary["calibration"])
        s.commit_reference(ordinary["id"], {**REF, "first_word": 30, "last_word": 30})
        for span in ([8, 8], [180, 181], [25, 26]):
            self.assertTrue(s.start_decision(span)["calibration"], span)

    def test_suggestions_never_reach_masked_words(self):
        s = done_store()
        self.assertTrue(s.suggestions_blind(None, (8, 8)))
        self.assertTrue(s.suggestions_blind(None, (25, 26)))  # crosses into window 2
        self.assertFalse(s.suggestions_blind(None, (60, 62)))
        open_ordinary = s.start_decision([60, 62])["id"]
        self.assertTrue(s.suggestions_blind(open_ordinary, (8, 8)))  # B2/B3: no preview

    def test_an_open_unrevealed_item_blinds_everything(self):
        s = done_store()
        cal = s.start_decision([8, 8])["id"]
        self.assertTrue(s.suggestions_blind(cal, (60, 62)))
        self.assertTrue(s.suggestions_blind(cal, None))
        s.commit_reference(cal, {**REF, "first_word": 8, "last_word": 8})
        s.reveal_window(1)
        self.assertFalse(s.suggestions_blind(cal, (8, 8)))
        self.assertFalse(s.suggestions_blind(None, (20, 20)))  # the whole window is revealed
        self.assertIn(20, s.exposed_rows())

    def test_context_never_reads_masked_words(self):
        s = done_store()
        c = candidates.Candidates(s.p)
        masked = s.masked_rows()
        out = c.for_span(26, 27, masked=masked, limit=50)
        reasons = " ".join(sig["reason"] for m in out["moments"] for sig in m["signals"])
        words = s.p.recall_text()
        for r in range(14, 26):  # the context words that window 1 would have contributed
            w = words[r - 1].lower()
            if len(w) > 3 and w not in " ".join(words[25:39]).lower():
                self.assertNotIn(w, reasons, w)

    def test_revision_after_reveal_is_counted(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8, **TOBIAS})
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})  # corrected, still blind
        s.reveal_window(1)
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8, **TOBIAS})
        cal = s.calibration()
        self.assertEqual((cal["items"], cal["compared"], cal["revised"]), (1, 1, 1))
        self.assertEqual(s.decisions()[did]["blind"]["referent_ids"], REF["referent_ids"])

    def test_undo_cannot_reach_behind_a_reveal(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})
        s.reveal_window(1)
        with self.assertRaisesRegex(store.StoreError, "after its reveal"):
            s.undo()
        self.assertTrue(s.decisions()[did]["revealed"])

    def test_a_reveal_is_monotonic_and_whole_window(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})
        s.reveal_window(1)
        s.delete(did)
        self.assertNotIn(14, s.masked_rows())  # deleting the item does not re-mask (B7b)
        self.assertFalse(s.start_decision([14, 14])["calibration"])
        with self.assertRaisesRegex(store.StoreError, "already revealed"):
            s.reveal_window(1)

    def test_an_uncommitted_item_never_locks_its_window(self):
        s = done_store()
        abandoned = s.start_decision([8, 8])["id"]  # closed without committing (B10)
        s.reveal_window(1)
        self.assertIn(1, s.revealed_windows())
        s.commit_reference(abandoned, {**REF, "first_word": 8, "last_word": 8})
        d = s.decisions()[abandoned]
        self.assertFalse(d["calibration"])  # committed after the reveal: not a blind item
        self.assertTrue(d["late"])
        self.assertEqual(s.calibration()["items"], 0)
        with self.assertRaisesRegex(store.StoreError, "calibration window"):
            s.reveal_window(3)

    def test_only_compared_items_enter_the_revision_rate(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})
        cal = s.calibration()
        self.assertEqual((cal["items"], cal["compared"], cal["revised"]), (1, 0, 0))
        s.reveal_window(1)
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8, **TOBIAS})
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})  # changed back
        cal = s.calibration()
        self.assertEqual((cal["compared"], cal["revised"]), (1, 0))  # final agreement

    def test_deleted_calibration_items_are_counted(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})
        s.delete(did)
        self.assertEqual(s.calibration()["byWindow"][1]["deleted"], 1)

    def test_a_partial_reveal_stays_visible_in_the_report(self):
        s = done_store()
        did = s.start_decision([8, 8])["id"]
        s.commit_reference(did, {**REF, "first_word": 8, "last_word": 8})
        s.reveal_window(1)
        after = s.start_decision([14, 14])["id"]  # first started after the reveal
        s.commit_reference(after, {**REF, "first_word": 14, "last_word": 14})
        w1 = s.calibration()["byWindow"][1]
        self.assertEqual((w1["blind"], w1["late"], w1["revealed"]), (1, 1, True))
        self.assertGreater(w1["likely"], 1)

    def test_a_new_referent_clears_old_evidence(self):
        s = done_store()
        did = s.start_decision([60, 60])["id"]
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60})
        s.commit_source(did, self.SRC)
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60, "claim_role": "patient"})
        self.assertIsNotNone(s.decisions()[did]["source"])  # same person: evidence stands
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60, **TOBIAS})
        self.assertIsNone(s.decisions()[did]["source"])
        self.assertTrue(s.decisions()[did]["staleSource"])

    def test_an_ordinary_decision_cannot_take_masked_words(self):
        s = done_store()
        did = s.start_decision([30, 30])["id"]
        with self.assertRaisesRegex(store.StoreError, "calibration window"):
            s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30,
                                     "other_coref_spans": [[24, 24]]})

    def test_search_is_blind_inside_a_masked_window(self):
        s = done_store()
        self.assertTrue(s.suggestions_blind(None, None, 1))
        self.assertFalse(s.suggestions_blind(None, None, 3))

    def test_undo_cannot_remove_a_start(self):
        s = done_store()
        did = s.start_decision([60, 62])["id"]
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60})
        self.assertEqual(s.undo()["kind"], "decision.reference")
        self.assertNotEqual(s.undo()["kind"], "decision.start")

    def test_step_two_evidence_must_be_consistent(self):
        s = done_store()
        did = s.start_decision([60, 60])["id"]
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60})
        with self.assertRaisesRegex(store.StoreError, "supported or contradicted"):
            s.commit_source(did, {**self.SRC, "source_anchor_ids": [], "source_scene_ids": [],
                                  "source_resolvable": "unknown", "reference_outcome": "supported"})
        with self.assertRaisesRegex(store.StoreError, "resolvable"):
            s.commit_source(did, {**self.SRC, "source_anchor_ids": [], "reference_outcome": "unresolved"})
        with self.assertRaisesRegex(store.StoreError, "unknown source anchor"):
            s.commit_source(did, {**self.SRC, "source_anchor_ids": ["row-9999"]})
        with self.assertRaisesRegex(store.StoreError, "anchored row"):
            s.commit_source(did, {**self.SRC, "source_scene_ids": [7]})
        with self.assertRaisesRegex(store.StoreError, "anchored row"):  # scenes with no anchor
            s.commit_source(did, {**self.SRC, "source_anchor_ids": [], "source_scene_ids": [1],
                                  "source_resolvable": "unknown", "reference_outcome": "unresolved"})
        s.commit_source(did, self.SRC)

    def test_a_mention_resolves_only_to_someone_present(self):
        s = fresh_store()
        row = next(r for r in s.p.rows if s.mentions[r["row"]] and len(r["names"]) > 1)
        m = s.mentions[row["row"]][0]["id"]
        absent_name = row["names"][0]
        with self.assertRaisesRegex(store.StoreError, "present person"):
            s.source_row(row["row"], row["names"][1:], [], {m: absent_name}, True)


class ReferentContractCourt(unittest.TestCase):
    """Decision schema v2 and the referents registry (Codex, Fray #140 @1058), on synthetic data."""

    def commit(self, s, row, **ref):
        did = s.start_decision([row, row])["id"]
        s.commit_reference(did, {**REF, "first_word": row, "last_word": row, **ref})
        return did

    def rows(self, s):
        return {r["decision_id"]: r for r in csv.DictReader(io.StringIO(s.export()["decisions.csv"]))}

    def test_each_status_round_trips_through_the_export(self):
        s = done_store()
        mara, tobias = REF["referent_ids"][0], TOBIAS["referent_ids"][0]
        cases = {
            "identified": dict(referent_status="identified", referent_ids=[mara]),
            "ambiguous": dict(referent_status="ambiguous", referent_ids=[mara, tobias]),
            "unknown": dict(referent_status="unknown", referent_ids=[], person_reference="unknown"),
            "external": dict(referent_status="external", referent_ids=[]),
            "not_person": dict(referent_status="not_person", referent_ids=[], person_reference="no"),
        }
        ids = {name: self.commit(s, 30 + k, **ref) for k, (name, ref) in enumerate(cases.items())}
        rows = self.rows(s)
        for name, did in ids.items():
            self.assertEqual(rows[did]["referent_status"], name)
            self.assertEqual(json.loads(rows[did]["referent_ids"]), cases[name]["referent_ids"])

    def test_ambiguous_needs_two_and_unknown_none(self):
        s = done_store()
        did = s.start_decision([30, 30])["id"]
        with self.assertRaisesRegex(store.StoreError, "at least two"):
            s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30, "referent_status": "ambiguous"})
        with self.assertRaisesRegex(store.StoreError, "carries no referent ids"):
            s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30, "referent_status": "unknown"})
        with self.assertRaisesRegex(store.StoreError, "requires person"):
            s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30, "referent_status": "external",
                                     "referent_ids": [], "person_reference": "unknown"})

    def test_an_off_screen_known_person_is_identified_with_their_own_id(self):
        # There is no generic "absent" referent: an off-screen known person keeps their id.
        s = done_store()
        self.assertNotIn("absent", {p["id"] for p in s.people()})
        did = self.commit(s, 30, **TOBIAS)
        self.assertEqual(json.loads(self.rows(s)[did]["referent_ids"]), TOBIAS["referent_ids"])

    def test_ids_are_opaque_and_survive_a_rename(self):
        s = done_store()
        pid = s.create_person("woman on the quay")["pid"]
        did = self.commit(s, 30, referent_status="identified", referent_ids=[pid])
        s.rename_person(pid, "harbour clerk")
        reg = {r["id"]: r for r in s.registry()["referents"]}
        self.assertEqual(reg[pid]["label"], "harbour clerk")
        self.assertEqual(json.loads(self.rows(s)[did]["referent_ids"]), [pid])
        for r in reg.values():  # never a display name, an index or a row number
            self.assertNotEqual(r["id"], r["label"])
            self.assertFalse(r["id"].isdigit())
        with self.assertRaisesRegex(store.StoreError, "reviewer-created"):
            s.rename_person(REF["referent_ids"][0], "Someone")

    def test_an_invalid_id_is_refused(self):
        s = done_store()
        did = s.start_decision([30, 30])["id"]
        with self.assertRaisesRegex(store.StoreError, "unknown referent id"):
            s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30, "referent_ids": ["Mara"]})

    def test_a_legacy_decision_exports_as_not_captured(self):
        s = done_store()
        did = s.start_decision([30, 30])["id"]
        legacy = {k: v for k, v in REF.items() if not k.startswith("referent")}
        s._append({"kind": "decision.reference", "id": did,
                   "fields": {**legacy, "first_word": 30, "last_word": 30, "other_coref_spans": [], "referent": "Mara"}})
        row = self.rows(s)[did]
        self.assertEqual((row["referent_status"], row["referent_ids"]), ("", ""))  # not "unknown"

    def test_a_changed_referent_status_clears_evidence(self):
        s = done_store()
        did = self.commit(s, 60)
        s.commit_source(did, CalibrationCourt.SRC)
        s.commit_reference(did, {**REF, "first_word": 60, "last_word": 60, "referent_status": "ambiguous",
                                 "referent_ids": REF["referent_ids"] + TOBIAS["referent_ids"]})
        self.assertIsNone(s.decisions()[did]["source"])

    def test_export_is_versioned_and_bound_by_a_manifest(self):
        s = done_store()
        self.commit(s, 30)
        first, second = s.write_export(), s.write_export()
        self.assertEqual((first.name, second.name), ("v001", "v002"))
        man = json.loads((first / "export-manifest.json").read_text())
        self.assertEqual(man["decisionSchema"], "decisions/v2")
        for name in ("decisions.csv", "review-windows.csv", "referents.json", "log.jsonl"):
            body = (first / name).read_text()
            self.assertEqual(man["files"][name], __import__("hashlib").sha256(body.encode()).hexdigest())
        self.assertEqual(man["parentInputs"]["recall"], json.loads(MANIFEST.read_text())["inputs"]["recall"]["sha256"])
        self.assertEqual((first / "decisions.csv").stat().st_mode & 0o777, 0o600)


class ReferenceFirstGateCourt(unittest.TestCase):
    """The reference route opens recall directly; the export records exposure order."""

    def manifest(self):
        m = json.loads(MANIFEST.read_text())
        m["gate"] = {"mode": "reference-first", "decisionOp": "synthetic"}
        path = SPEC / f"reference-first-{next(_n)}.json"
        path.write_text(json.dumps(m))
        return path

    def test_recall_opens_without_the_source_pass_and_exposure_is_recorded(self):
        s = store.Store(packet.Packet(self.manifest()), private())
        self.assertTrue(s.recall_open())
        r = s.p.rows[0]
        s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
        s.note_recall_opened()
        r = s.p.rows[1]
        s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
        e = s.exposure()
        self.assertEqual((e["sourceRowsDoneBeforeRecall"], e["sourceRowsDoneOnlyAfterRecall"]), (1, 1))
        self.assertEqual(e["recallExposure"], "recorded")
        self.assertIsNotNone(e["recallFirstOpened"])

    def test_undo_cannot_rewrite_exposure(self):
        s = store.Store(packet.Packet(self.manifest()), private())
        r = s.p.rows[0]
        s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
        s.note_recall_opened()
        s.undo()  # undoes the source row (recall.opened is not an edit)
        self.assertEqual(s.events[-1]["kind"], "undo")
        self.assertEqual(s.events[s.events[-1]["target"]]["kind"], "source.row")
        s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
        e = s.exposure()
        self.assertEqual((e["sourceRowsDoneBeforeRecall"], e["sourceRowsDoneOnlyAfterRecall"]), (1, 0))

    def test_a_source_only_search_is_not_recall_exposure(self):
        # Codex's witness: source row, a search with no recall context, source row, recall.
        s = store.Store(packet.Packet(self.manifest()), private())
        for k in (0, 1):
            r = s.p.rows[k]
            s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
            if k == 0:
                s.searched(None, "lantern", None, None, [20])
        s.note_recall_opened()
        e = s.exposure()
        self.assertEqual(e["recallExposure"], "recorded")
        self.assertEqual((e["sourceRowsDoneBeforeRecall"], e["sourceRowsDoneOnlyAfterRecall"]), (2, 0))
        s.searched(None, "lantern", None, 3, [20])  # a recall-pass search does count
        self.assertTrue(s._shows_recall(s.events[-1]))

    def test_a_legacy_log_reports_known_prior_exposure(self):
        # Recall work recorded before the recall.opened instrumentation existed.
        s = store.Store(packet.Packet(self.manifest()), private())
        did = s.start_decision([30, 30])["id"]
        s.commit_reference(did, {**REF, "first_word": 30, "last_word": 30})
        s.note_recall_opened()  # the first instrumented opening comes later
        e = s.exposure()
        self.assertEqual(e["recallExposure"], "known prior; timing incomplete")
        self.assertIsNone(e["recallFirstOpened"])
        self.assertIsNotNone(e["earliestRecallEvidence"])


class BlindnessServerCourt(unittest.TestCase):
    """The server decides blindness, whatever the client asks for."""

    def setUp(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.srv, self.app = server.serve(MANIFEST, private(), announce=False)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        s = self.app.store
        for r in s.p.rows:
            s.source_row(r["row"], r["names"], [], {m["id"]: "unknown" for m in s.mentions[r["row"]]}, True)
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", f"/?token={self.app.token}", headers={"Host": f"127.0.0.1:{self.port}"})
        self.cookie = c.getresponse().getheader("Set-Cookie").split(";")[0]
        c.close()

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def post(self, path, body):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("POST", path, body=json.dumps(body), headers={"Host": f"127.0.0.1:{self.port}", "Cookie": self.cookie, "Content-Type": "application/json"})
        r = c.getresponse()
        out = (r.status, json.loads(r.read()))
        c.close()
        return out

    def test_candidates_for_masked_words_are_withheld(self):
        status, out = self.post("/api/candidates", {"first": 8, "last": 8, "id": None, "hidden": False})
        self.assertEqual(status, 200)
        self.assertTrue(out["hidden"])
        self.assertEqual((out["moments"], out["nominations"]["one"]), ([], []))
        status, out = self.post("/api/candidates", {"first": 60, "last": 62, "id": None})
        self.assertFalse(out["hidden"])
        self.assertTrue(out["moments"])

    def test_search_is_refused_on_masked_words_or_an_open_item(self):
        status, out = self.post("/api/search", {"q": "lantern", "span": [8, 8]})
        self.assertEqual(status, 409)
        did = self.app.store.start_decision([8, 8])["id"]
        status, out = self.post("/api/search", {"q": "lantern", "id": did, "span": [60, 60]})
        self.assertEqual(status, 409)
        status, out = self.post("/api/search", {"q": "lantern", "span": [60, 60], "window": 3})
        self.assertEqual(status, 200)
        status, out = self.post("/api/search", {"q": "lantern", "window": 1})
        self.assertEqual(status, 409)

    def test_index_carries_no_timing_for_calibration_words(self):
        status, out = self.post("/api/index", {})
        w8 = out["words"][7]
        self.assertEqual((w8["t"], w8["tr"], w8["nom"]["one"]), (None, None, []))
        self.assertTrue(out["words"][29]["nom"]["one"])

    def test_state_carries_masked_rows_without_text(self):
        status, out = self.post("/api/state", {})
        self.assertEqual(out["calibrationWindows"], [1, 8])
        self.assertIn(8, out["maskedRows"])


if __name__ == "__main__":
    unittest.main()
