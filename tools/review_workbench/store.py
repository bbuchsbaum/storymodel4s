"""Store: the reviewer's work as an append-only JSONL event log, folded into current state.

The log is the record (plan "Data artifacts"): every edit with its time, the suggestions shown
and whether they were hidden. Undo appends an event rather than deleting one. `decisions.csv`
and `review-windows.csv` are deterministic exports of the fold, in Codex's template columns.
The source-first gate (plan constraint 3) lives here, so the server cannot open a recall window
that the log has not earned.
"""
from datetime import datetime, timezone
from decimal import Decimal
import csv
import io
import json
import os
from pathlib import Path
import sys

from packet import DECLARED

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "source_identity"))
import producer  # noqa: E402  (the recognizer's own mention rule; ADR 0027 guards)

LOG_SCHEMA = "review-workbench-log/v0"
REFERENCE_FIELDS = (
    "first_word",
    "last_word",
    "other_coref_spans",
    "reference_form",
    "claim_role",
    "occurrence_group",
    "person_reference",
    "referent",
)
SOURCE_FIELDS = (
    "source_scene_ids",
    "source_anchor_ids",
    "source_resolvable",
    "reference_outcome",
    "ambiguity_reason",
)
FIXED_REFERENTS = ("absent", "none", "unknown")
MENTION_RESOLUTIONS = ("present", "absent", "unknown")


class StoreError(Exception):
    """A write the log refuses: invalid field, unknown id, or a closed gate."""


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


class Store:
    def __init__(self, packet, private_dir: Path):
        self.p = packet
        self.dir = Path(private_dir)
        self.dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)
        self.path = self.dir / "log.jsonl"
        self.events = []
        if self.path.exists():
            for line in self.path.read_text().splitlines():
                if line.strip():
                    self.events.append(json.loads(line))
            head = self.events[0] if self.events else {}
            if (
                head.get("kind") != "header"
                or head.get("manifest") != packet.manifest_sha
            ):
                raise StoreError("log belongs to a different packet")
        else:
            self._append(
                {
                    "kind": "header",
                    "schema": LOG_SCHEMA,
                    "manifest": packet.manifest_sha,
                    "subject": packet.manifest["subject"],
                    "gate": packet.manifest["gate"],
                    "reviewer": packet.manifest.get("reviewer"),
                }
            )
        self.mentions = {r["row"]: self._row_mentions(r) for r in packet.rows}

    # -- log -------------------------------------------------------------------------------
    def _append(self, event: dict) -> dict:
        event = {"seq": len(self.events), "t": now(), **event}
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as f:
            f.write(json.dumps(event, sort_keys=True) + "\n")
            f.flush()
            os.fsync(f.fileno())
        self.events.append(event)
        return event

    def live(self) -> list:
        undone = {e["target"] for e in self.events if e["kind"] == "undo"}
        return [
            e for e in self.events if e["seq"] not in undone and e["kind"] != "undo"
        ]

    def undo(self) -> dict:
        """Undo the latest live edit. Starts, resumes and reveals are not edits: undoing a start
        would renumber the calibration schedule, and a reveal cannot be unseen."""
        fixed = ("header", "suggestions.shown", "decision.start", "calibration.reveal")
        live = [e for e in self.live() if e["kind"] not in fixed]
        if not live:
            raise StoreError("nothing to undo")
        target = live[-1]
        if target["kind"] == "decision.reference" and self._revealed(target["id"]):
            raise StoreError(
                "a calibration answer cannot be undone after its reveal; use Edit, which is logged"
            )
        self._append({"kind": "undo", "target": target["seq"]})
        return target

    # -- source pass -----------------------------------------------------------------------
    def _row_mentions(self, row) -> list:
        tr = self.p.transcripts[row["part"]]
        scope = (Decimal(str(row["t0"])), Decimal(str(row["t1"])))
        mentions, meta, _ = producer.dialogue_mentions(tr, row["names"], scope)
        kinds = {m["id"]: m["class"] for m in meta}
        out = []
        for m in mentions:
            words = {w["index"]: w for w in tr["words"]}
            out.append(
                {
                    "id": m["id"],
                    "class": kinds[m["id"]],
                    "name": m.get("name"),
                    "words": m["words"],
                    "text": " ".join(words[i]["text"] for i in m["words"]),
                    "t0": words[m["words"][0]]["start_seconds"],
                    "t1": words[m["words"][-1]]["end_seconds"],
                }
            )
        return out

    def source_row(self, row: int, present, unnamed, mentions, done: bool) -> dict:
        r = next((x for x in self.p.rows if x["row"] == row), None)
        if r is None:
            raise StoreError("unknown row")
        known = set(self.p.people)
        if any(n not in known for n in present):
            raise StoreError(
                "present names must come from the annotation's Name - All entries"
            )
        uids = set(self.unnamed())
        if any(u not in uids for u in unnamed):
            raise StoreError("unknown unnamed person")
        ids = {m["id"] for m in self.mentions[row]}
        for mid, res in mentions.items():
            if mid not in ids:
                raise StoreError("unknown mention")
            ok = res in ("absent", "unknown") or res in present or res in unnamed
            if not ok:
                raise StoreError(
                    "a mention resolves to a present person, absent, or unknown"
                )
        if done and set(mentions) != ids:
            raise StoreError("a row is done only when every mention is resolved")
        return self._append(
            {
                "kind": "source.row",
                "row": row,
                "present": sorted(present),
                "unnamed": sorted(unnamed),
                "mentions": dict(sorted(mentions.items())),
                "done": bool(done),
            }
        )

    def create_unnamed(self, label: str, scene: int) -> dict:
        label = (label or "").strip()
        if not label:
            raise StoreError("an unnamed person needs a short description")
        uid = f"u{1 + sum(1 for e in self.events if e['kind'] == 'source.unnamed')}"
        return self._append(
            {"kind": "source.unnamed", "uid": uid, "label": label, "scene": scene}
        )

    def unnamed(self) -> dict:
        return {e["uid"]: e for e in self.live() if e["kind"] == "source.unnamed"}

    def source_state(self) -> dict:
        rows = {}
        for e in self.live():
            if e["kind"] == "source.row":
                rows[e["row"]] = e
        scenes = {}
        for s in self.p.scenes:
            done = sum(1 for r in s["rows"] if rows.get(r, {}).get("done"))
            scenes[s["scene"]] = {"rows": len(s["rows"]), "done": done}
        return {"rows": rows, "scenes": scenes, "unnamed": self.unnamed()}

    def recall_open(self) -> bool:
        """The source-first gate. complete-all: every row of every scene is done."""
        mode = self.p.manifest["gate"]["mode"]
        if mode != "complete-all":
            raise StoreError(f"gate mode not implemented: {mode}")
        rows = self.source_state()["rows"]
        return all(rows.get(r["row"], {}).get("done") for r in self.p.rows)

    def _require_recall(self):
        if not self.recall_open():
            raise StoreError("the source pass must be complete before recall opens")

    # -- recall pass -----------------------------------------------------------------------
    def read_window(self, window: int, value: bool = True) -> dict:
        self._require_recall()
        if window not in {w["window"] for w in self.p.windows}:
            raise StoreError("unknown window")
        return self._append(
            {"kind": "window.read", "window": window, "value": bool(value)}
        )

    def create_person(self, label: str) -> dict:
        self._require_recall()
        label = (label or "").strip()
        if not label:
            raise StoreError("a new person needs a short label")
        pid = f"p{1 + sum(1 for e in self.events if e['kind'] == 'person.create')}"
        return self._append({"kind": "person.create", "pid": pid, "label": label})

    def people(self) -> list:
        made = [
            {"id": e["pid"], "label": e["label"], "kind": "created"}
            for e in self.live()
            if e["kind"] == "person.create"
        ]
        named = [{"id": n, "label": n, "kind": "annotation"} for n in self.p.people]
        return named + made

    # -- calibration: decided by words, not by count ----------------------------------------
    # Calibration windows are fixed before review (manifest `calibrationWindows`, else window 1
    # and every eighth window). Their words are masked: no timing chip, candidate, context word,
    # echo or search result reaches them until a decision on them commits blind and reveals.
    # A count-based schedule cannot do this, because anything shown before an item starts
    # (v2 function review, B3) is then untracked.
    def calibration_windows(self) -> list:
        ws = [w["window"] for w in self.p.windows]
        chosen = self.p.manifest.get("calibrationWindows")
        if chosen is None:
            chosen = [w for w in ws if w == 1 or w % 8 == 0]
        return sorted(set(chosen) & set(ws))

    def calibration_rows(self) -> set:
        cw = set(self.calibration_windows())
        return {w for win in self.p.windows if win["window"] in cw
                for w in range(win["first"], win["last"] + 1)}

    def revealed_windows(self) -> set:
        """Calibration windows whose suggestions have been revealed. A reveal covers a whole
        window and is monotonic: no undo, edit or delete reverses it (v3 reviews: per-decision
        exposure primed the rest of the window, and was reversible)."""
        return {e["window"] for e in self.events if e["kind"] == "calibration.reveal"}

    def exposed_rows(self) -> set:
        rw = self.revealed_windows()
        return {w for win in self.p.windows if win["window"] in rw
                for w in range(win["first"], win["last"] + 1)}

    def masked_rows(self) -> set:
        return self.calibration_rows() - self.exposed_rows()

    def masked_windows(self) -> list:
        return [w for w in self.calibration_windows() if w not in self.revealed_windows()]

    def reveal_window(self, window: int) -> dict:
        """Reveal one calibration window: every decision started there keeps its blind step-1
        answer, and revisions from now on are logged as revisions."""
        self._require_recall()
        if window not in self.calibration_windows():
            raise StoreError("only a calibration window can be revealed")
        if window in self.revealed_windows():
            raise StoreError("this window is already revealed")
        win = next(w for w in self.p.windows if w["window"] == window)
        rows = set(range(win["first"], win["last"] + 1))
        committed = self.decisions()
        deleted = {e["id"] for e in self.live() if e["kind"] == "decision.delete"}
        open_items = [
            e["id"] for e in self.live()
            if e["kind"] == "decision.start" and e["calibration"] and e.get("span")
            and e["id"] not in committed and e["id"] not in deleted
            and set(range(e["span"][0], e["span"][1] + 1)) & rows
        ]
        if open_items:
            raise StoreError(f"commit step 1 of {', '.join(open_items)} before revealing this window")
        return self._append({"kind": "calibration.reveal", "window": window})

    def _span_rows(self, did):
        out = []
        for a, b in self.spans_of(did):
            out.extend(range(a, b + 1))
        return out

    def start_decision(self, span=None) -> dict:
        """Number a new decision as it starts. It is a calibration item when its span touches a
        masked calibration word; such items open with suggestions hidden."""
        self._require_recall()
        n = 1 + sum(1 for e in self.events if e["kind"] == "decision.start")
        rows = set(range(span[0], span[1] + 1)) if span else set()
        calibration = bool(rows & self.masked_rows())
        return self._append(
            {
                "kind": "decision.start",
                "id": f"d{n:03d}",
                "n": n,
                "calibration": calibration,
                "span": span,
            }
        )

    def spans_of(self, did) -> list:
        """Every recall span a decision has been tied to: its start span and its committed main
        and co-referring spans."""
        out = []
        for e in self.live():
            if e.get("id") != did:
                continue
            if e["kind"] == "decision.start" and e.get("span"):
                out.append(tuple(e["span"]))
            if e["kind"] in ("decision.reference", "decision.revise"):
                f = e["fields"]
                out.append((f["first_word"], f["last_word"]))
                out.extend(tuple(x) for x in f["other_coref_spans"])
        return out

    def suggestions_blind(self, did=None, span=None, window=None) -> bool:
        """Whether candidates and search must be withheld. Blind while a calibration item is
        open and unrevealed, whenever the selected span touches a masked word, and, for search,
        while the reader is inside a masked window.

        Threat model: this prevents inadvertent priming of an honest reviewer through the UI.
        It does not stop a reviewer who forges requests or reads the page's data in devtools."""
        if did and self._started(did)["calibration"] and not self._revealed(did):
            return True
        if window is not None and window in self.masked_windows():
            return True
        if span is None:
            return False
        a, b = span
        return bool(set(range(a, b + 1)) & self.masked_rows())

    def _started(self, did):
        e = next(
            (
                e
                for e in self.live()
                if e["kind"] == "decision.start" and e["id"] == did
            ),
            None,
        )
        if e is None:
            raise StoreError("unknown decision")
        return e

    def commit_reference(self, did: str, fields: dict) -> dict:
        self._require_recall()
        start = self._started(did)
        f = {k: fields.get(k) for k in REFERENCE_FIELDS}
        n = len(self.p.recall)
        try:
            a, b = int(f["first_word"]), int(f["last_word"])
        except (TypeError, ValueError):
            raise StoreError("span bounds must be word row ids") from None
        if not 1 <= a <= b <= n:
            raise StoreError("span outside the recall")
        f["first_word"], f["last_word"] = a, b
        spans = f["other_coref_spans"] or []
        if not all(
            isinstance(s, list)
            and len(s) == 2
            and all(isinstance(x, int) for x in s)
            and 1 <= s[0] <= s[1] <= n
            for s in spans
        ):
            raise StoreError("other co-referring spans are inclusive word-row pairs")
        f["other_coref_spans"] = spans
        for key in ("reference_form", "person_reference"):
            if f[key] not in DECLARED[key]:
                raise StoreError(f"{key} must be one of {', '.join(DECLARED[key])}")
        ref = f["referent"]
        if f["person_reference"] == "yes" and ref is None:
            raise StoreError("choose who this refers to, or mark it Unknown")
        ids = {p["id"] for p in self.people()}
        if ref is not None and ref not in ids and ref not in FIXED_REFERENTS:
            raise StoreError(
                "the referent must be a listed person, a created person, or fixed"
            )
        f["claim_role"] = (f["claim_role"] or "").strip()
        f["occurrence_group"] = (f["occurrence_group"] or "").strip()
        masked = self.masked_rows()
        touched = set(range(a, b + 1)).union(*(range(x, y + 1) for x, y in spans))
        if not start["calibration"] and touched & masked:
            raise StoreError(
                "these words are in a calibration window; start a separate decision on them"
            )
        revealed = self._revealed(did)
        kind = "decision.revise" if revealed else "decision.reference"
        return self._append({"kind": kind, "id": did, "fields": f})

    def _revealed(self, did) -> bool:
        """A calibration item is revealed once every calibration window its start touches is."""
        start = self._started(did)
        if not start["calibration"]:
            return False
        a, b = start["span"]
        cw = {w["window"]: w for w in self.p.windows}
        touched = {self.p.census[r - 1]["window"] for r in range(a, b + 1)}
        cal = touched & set(self.calibration_windows())
        return bool(cal) and cal <= self.revealed_windows()


    def commit_source(self, did: str, fields: dict) -> dict:
        self._require_recall()
        d = self.decisions().get(did)
        if not d:
            raise StoreError("commit the reference step first")
        f = {k: fields.get(k) for k in SOURCE_FIELDS}
        scenes = {s["scene"] for s in self.p.scenes}
        f["source_scene_ids"] = list(f["source_scene_ids"] or [])
        if any(s not in scenes for s in f["source_scene_ids"]):
            raise StoreError("unknown source scene")
        f["source_anchor_ids"] = [str(x) for x in (f["source_anchor_ids"] or [])]
        for key in ("source_resolvable", "reference_outcome"):
            if f[key] not in DECLARED[key]:
                raise StoreError(f"{key} must be one of {', '.join(DECLARED[key])}")
        f["ambiguity_reason"] = (f["ambiguity_reason"] or "").strip()
        if f["reference_outcome"] in ("supported", "contradicted") and not f["source_anchor_ids"]:
            raise StoreError("a supported or contradicted outcome needs at least one source anchor")
        if f["source_resolvable"] == "yes" and not f["source_anchor_ids"]:
            raise StoreError("resolvable needs at least one source anchor")
        rows = {r["row"]: r for r in self.p.rows}
        mention_rows = {m["id"]: row for row, ms in self.mentions.items() for m in ms}
        anchored_scenes = set()
        for a in f["source_anchor_ids"]:
            if a.startswith("row-") and a[4:].isdigit() and int(a[4:]) in rows:
                anchored_scenes.update(rows[int(a[4:])]["scenes"])
            elif a in mention_rows:
                anchored_scenes.update(rows[mention_rows[a]]["scenes"])
            elif a in self.unnamed():
                continue
            else:
                raise StoreError(f"unknown source anchor {a}")
        if not set(f["source_scene_ids"]) <= anchored_scenes:
            raise StoreError("each source scene must contain an anchored row or mention")
        return self._append({"kind": "decision.source", "id": did, "fields": f})

    def delete(self, did: str) -> dict:
        self._require_recall()
        if did not in self.decisions():
            raise StoreError("unknown decision")
        return self._append({"kind": "decision.delete", "id": did})

    def shown(self, did, span, rows, hidden: bool) -> dict:
        return self._append(
            {
                "kind": "suggestions.shown",
                "id": did,
                "span": span,
                "rows": rows,
                "hidden": hidden,
            }
        )

    def decisions(self) -> dict:
        out = {}
        starts = {}
        for e in self.live():
            k = e["kind"]
            if k == "decision.start":
                starts[e["id"]] = e
            elif k in ("decision.reference", "decision.revise"):
                d = out.setdefault(
                    e["id"],
                    {
                        "id": e["id"],
                        "calibration": starts[e["id"]]["calibration"],
                        "source": None,
                        "revealed": False,
                        "blind": None,
                        "revised": False,
                    },
                )
                # Before its window is revealed a calibration item may be corrected and is
                # still blind; the blind answer is the last one committed before the reveal.
                if k == "decision.reference" and d["calibration"]:
                    d["blind"] = e["fields"]
                if k == "decision.revise":
                    d["revised"] = e["fields"] != d["blind"] if d["blind"] else True
                d["reference"] = e["fields"]
                d["t"] = e["t"]
            elif k == "decision.source" and e["id"] in out:
                out[e["id"]]["source"] = e["fields"]
                out[e["id"]]["t"] = e["t"]
            elif k == "decision.delete":
                out.pop(e["id"], None)
        for did, d in out.items():
            d["revealed"] = self._revealed(did)
        return out

    def duplicates(self, did, first, last, referent) -> list:
        """Committed decisions whose span overlaps this one with the same referent (step 3)."""
        out = []
        for d in self.decisions().values():
            r = d["reference"]
            if d["id"] != did and r["referent"] == referent and referent is not None:
                if r["first_word"] <= last and first <= r["last_word"]:
                    out.append(d["id"])
        return out

    def windows_read(self) -> dict:
        state = {}
        for e in self.live():
            if e["kind"] == "window.read":
                state[e["window"]] = (e["value"], e["t"])
        return state

    def calibration(self) -> dict:
        items = [d for d in self.decisions().values() if d["calibration"]]
        by_form = {}
        for d in items:
            f = (d["blind"] or d["reference"])["reference_form"]
            g = by_form.setdefault(f, {"items": 0, "revised": 0})
            g["items"] += 1
            g["revised"] += int(d["revised"])
        return {
            "items": len(items),
            "revised": sum(int(d["revised"]) for d in items),
            "byForm": by_form,
        }

    # -- export ----------------------------------------------------------------------------
    def export(self) -> dict:
        """Codex's two templates, column for column. The referent is written only under an
        encoding agreed on Fray #136 (manifest `referentEncoding`); until then it stays in the
        log and the export says so."""
        enc = self.p.manifest.get("referentEncoding")
        cols = list(self.p.decision_columns)
        if enc == "referent_id-column" and "referent_id" not in cols:
            cols.append("referent_id")
        reviewer = self.p.manifest.get("reviewer") or ""
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(cols)
        for did, d in sorted(self.decisions().items()):
            r, s = d["reference"], d["source"] or {}
            row = {
                "decision_id": did,
                "first_word": r["first_word"],
                "last_word": r["last_word"],
                "other_coref_spans": json.dumps(r["other_coref_spans"]),
                "reference_form": r["reference_form"],
                "claim_role": r["claim_role"],
                "occurrence_group": r["occurrence_group"],
                "source_scene_ids": json.dumps(s.get("source_scene_ids", [])),
                "source_anchor_ids": json.dumps(s.get("source_anchor_ids", [])),
                "person_reference": r["person_reference"],
                "source_resolvable": s.get("source_resolvable", ""),
                "reference_outcome": s.get("reference_outcome", ""),
                "ambiguity_reason": s.get("ambiguity_reason", ""),
                "reviewer": reviewer,
                "reviewed_at": d["t"],
                "referent_id": r["referent"] or "",
            }
            w.writerow([row[c] for c in cols])
        wbuf = io.StringIO()
        ww = csv.writer(wbuf, lineterminator="\n")
        ww.writerow(["window", "first_word", "last_word", "reviewed", "reviewer"])
        read = self.windows_read()
        for win in self.p.windows:
            done = read.get(win["window"], (False, None))[0]
            ww.writerow(
                [
                    win["window"],
                    win["first"],
                    win["last"],
                    "yes" if done else "",
                    reviewer if done else "",
                ]
            )
        return {
            "decisions.csv": buf.getvalue(),
            "review-windows.csv": wbuf.getvalue(),
            "referentExported": enc is not None,
        }

    def write_export(self) -> Path:
        out = self.dir / "export"
        out.mkdir(mode=0o700, exist_ok=True)
        files = self.export()
        for name in ("decisions.csv", "review-windows.csv"):
            path = out / name
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(files[name])
        return out
