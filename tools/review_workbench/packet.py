"""Packet: the workbench's view of one pinned review packet. It splits what it reads into a
text-free index (ids, timings, scenes, nominations, hashes) and text stores (recall words; film
annotation and transcript), because the index can be shown and logged freely and the recall text
cannot (plan constraint 1). A packet is loaded only when every input matches its pinned SHA-256.
"""
import csv
import hashlib
import json
from pathlib import Path


class PacketError(Exception):
    """A packet input is missing, unpinned, or does not match its pin."""


DECLARED = {
    "reference_form": ["name", "pronoun", "description", "ellipsis", "other"],
    "person_reference": ["yes", "no", "unknown"],
    "source_resolvable": ["yes", "no", "unknown"],
    "reference_outcome": ["supported", "contradicted", "unresolved", "external"],
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _col(row: dict, name: str) -> str:
    """Real annotation headers carry trailing spaces; match on the stripped name."""
    for k, v in row.items():
        if k and k.strip() == name:
            return (v or "").strip()
    raise PacketError(f"annotation column missing: {name}")


def _num(text: str):
    text = (text or "").strip()
    return float(text) if text else None


class Packet:
    def __init__(self, manifest_path: Path):
        self.manifest_path = Path(manifest_path).resolve()
        self.root = self.manifest_path.parent
        m = json.loads(self.manifest_path.read_text())
        if m.get("schema") != "review-workbench-manifest/v0":
            raise PacketError("unknown manifest schema")
        self.manifest = m
        self.manifest_sha = sha256(self.manifest_path)
        self.paths = {}
        for key, pin in m["inputs"].items():
            p = (self.root / pin["path"]).resolve()
            if not p.is_file():
                raise PacketError(f"input missing: {key}")
            if sha256(p) != pin["sha256"]:
                raise PacketError(f"input does not match its pin: {key}")
            self.paths[key] = p
        self.media = {}
        for part, spec in m["media"].items():
            spec = spec if isinstance(spec, dict) else {"path": spec}
            p = (self.root / spec["path"]).resolve()
            if not p.is_file():
                raise PacketError(f"media missing: part {part}")
            if "sha256" in spec and sha256(p) != spec["sha256"]:
                raise PacketError(f"media does not match its pin: part {part}")
            self.media[part] = p
        self.tr = float(m["tr"])
        self._load()

    # -- loading ---------------------------------------------------------------------------
    def _load(self):
        m = self.manifest
        parts = {int(k): v for k, v in m["parts"].items()}
        with open(self.paths["annotation"], newline="", encoding="utf-8-sig") as f:
            raw = list(csv.DictReader(f, delimiter="\t"))
        scene_offset = {p: v["firstScene"] - 1 for p, v in parts.items()}
        # Part membership comes from the manifest's declared row ranges: the real table restarts
        # its seconds clock per part, numbers TRs continuously, and leaves some rows without TRs.
        self.rows = []
        for r in raw:
            n = int(_col(r, "Segment Number"))
            part = next(
                (p for p, v in parts.items() if v["rows"][0] <= n <= v["rows"][1]), None
            )
            if part is None:
                raise PacketError(f"annotation row outside every declared part: {n}")
            shift = parts[part]["trShift"]
            tr0, tr1 = (
                _col(r, "Start Time (TRs, 1.5s)"),
                _col(r, "End Time (TRs, 1.5s)"),
            )
            names = [x.strip() for x in _col(r, "Name - All").split(",") if x.strip()]
            self.rows.append(
                {
                    "row": n,
                    "part": part,
                    "t0": float(_col(r, "Start Time (s)")),
                    "t1": float(_col(r, "End Time (s)")),
                    "tr0": int(tr0) - shift if tr0 else None,
                    "tr1": int(tr1) - shift if tr1 else None,
                    "names": names,
                    "focus": [
                        x.strip()
                        for x in _col(r, "Name - Focus").split(",")
                        if x.strip()
                    ],
                    "speaking": [
                        x.strip()
                        for x in _col(r, "Name - Speaking").split(",")
                        if x.strip()
                    ],
                    "details": _col(r, "Scene Details - A Level"),
                    "location": _col(r, "Location"),
                    "onscreen": _col(r, "Words on Screen"),
                }
            )
        with open(self.paths["watchOnsets"], newline="", encoding="utf-8-sig") as f:
            watch = list(csv.DictReader(f))
        self.scenes = []
        for w in watch:
            part = int(w["Scan"].replace("Part", ""))
            sid = int(w["Scene"]) + scene_offset[part]
            on, off = int(w["Onset"]), int(w["Offset"])
            rows = [
                r["row"]
                for r in self.rows
                if r["part"] == part
                and r["tr0"] is not None
                and r["tr1"] >= on
                and r["tr0"] <= off
            ]
            sr = [r for r in self.rows if r["row"] in rows]
            self.scenes.append(
                {
                    "scene": sid,
                    "part": part,
                    "trOn": on,
                    "trOff": off,
                    "rows": rows,
                    "t0": min((r["t0"] for r in sr), default=on * self.tr),
                    "t1": max((r["t1"] for r in sr), default=(off + 1) * self.tr),
                }
            )
        self.scenes.sort(key=lambda s: s["scene"])
        for r in self.rows:
            r["scenes"] = [s["scene"] for s in self.scenes if r["row"] in s["rows"]]
        # Rows with blank TR bounds (the real table has some) get no TR-based scene. Assign each
        # to the scene of its part that contains its midpoint, else the nearest one in time, so
        # the source pass can reach every row the complete-all gate requires (Codex review).
        for r in self.rows:
            if r["scenes"]:
                continue
            same = [s for s in self.scenes if s["part"] == r["part"]]
            if not same:
                raise PacketError(f"annotation row {r['row']} has no scene in its part")
            mid = (r["t0"] + r["t1"]) / 2
            inside = [s for s in same if s["t0"] <= mid < s["t1"]]
            best = inside[0] if inside else min(
                same, key=lambda s: min(abs(mid - s["t0"]), abs(mid - s["t1"]))
            )
            best["rows"].append(r["row"])
            best["rows"].sort()
            r["scenes"] = [best["scene"]]
            r["sceneByTime"] = True
        self.transcripts = {}
        for part in parts:
            t = json.loads(self.paths[f"transcript{part}"].read_text())
            self.transcripts[part] = t
        with open(self.paths["recall"], newline="", encoding="utf-8-sig") as f:
            self.recall = list(csv.DictReader(f))
        census = json.loads(self.paths["census"].read_text())
        if len(census) != len(self.recall):
            raise PacketError("census and recall row counts differ")
        self.census = census
        with open(self.paths["windows"], newline="") as f:
            self.windows = [
                {
                    "window": int(w["window"]),
                    "first": int(w["first_word"]),
                    "last": int(w["last_word"]),
                }
                for w in csv.DictReader(f)
            ]
        with open(self.paths["decisions"], newline="") as f:
            self.decision_columns = next(csv.reader(f))
        self.people = sorted(
            {n for r in self.rows for n in r["names"]}, key=str.casefold
        )

    # -- views -----------------------------------------------------------------------------
    def index(self) -> dict:
        """Everything the UI needs except text. Safe to hold in memory on the client."""
        words = []
        for i, (c, r) in enumerate(zip(self.census, self.recall)):
            words.append(
                {
                    "row": i + 1,
                    "window": c["window"],
                    "t": _num(r.get("Princeton Dataspace Word Onset Time (sec)")),
                    "tr": _num(r.get("Princeton Dataspace TR Number")),
                    "screen": bool(c["screen"]),
                    "nom": {
                        "one": c["nomination_scenes"],
                        "zero": c["zero_based_tr_scenes"],
                        "legacy": c["legacy_seconds_scenes"],
                    },
                    "boundary": bool(c["boundary_disagreement"]),
                    "missing": bool(c["timing_missing"]),
                }
            )
        return {
            "subject": self.manifest["subject"],
            "synthetic": bool(self.manifest.get("synthetic")),
            "tr": self.tr,
            "parts": {
                p: {"seconds": v["seconds"], "media": f"/media/{p}"}
                for p, v in self.manifest["parts"].items()
            },
            "windows": self.windows,
            "words": words,
            "scenes": self.scenes,
            "rows": [
                {k: r[k] for k in ("row", "part", "t0", "t1", "scenes")}
                for r in self.rows
            ],
            "declared": DECLARED,
            "columns": self.decision_columns,
            "hashes": {"manifest": self.manifest_sha},
        }

    def recall_text(self) -> list:
        return [r["Words"] for r in self.recall]

    def film_text(self) -> dict:
        lines = {}
        for p, t in self.transcripts.items():
            lines[p] = [
                {
                    "seg": s["index"],
                    "t0": s["start_seconds"],
                    "t1": s["end_seconds"],
                    "text": s["text"],
                }
                for s in t["segments"]
            ]
        return {
            "rows": {
                r["row"]: {
                    k: r[k]
                    for k in (
                        "names",
                        "focus",
                        "speaking",
                        "details",
                        "location",
                        "onscreen",
                    )
                }
                for r in self.rows
            },
            "lines": lines,
            "people": self.people,
        }

    def row_at(self, part: int, t: float):
        for r in self.rows:
            if r["part"] == part and r["t0"] <= t < r["t1"]:
                return r
        return None
