"""Candidate film moments for a stretch of recall, and search over the film side.

Four independent signals each point at annotation rows (moments), never at persons (plan
constraint 2): the census's scene nominations under three conventions, recall tokens equal to
annotation name tokens, local BM25 retrieval over annotation and transcript, and dialogue echo
(recall word runs that reappear in the transcript). Every candidate carries its reasons so the
reviewer can see why it was offered. Standard library only; no model call.
"""
import math
import re
from collections import Counter, defaultdict

STOP = frozenset(
    """a an and are as at be but by did do for from had has have he her him his i if in into is
    it its just like me my no not of oh on or our out over really so some that the their them
    then there they this to up was we were what when where which who will with you your she
    one all about after before again get got goes go going think kind sort know""".split()
)
_TOKEN = re.compile(r"[a-z0-9']+")
WEIGHTS = {
    "nomination": 1.0,
    "nomination-alt": 0.4,
    "name": 0.6,
    "lexical": 1.5,
    "echo": 2.5,
}
ECHO_MIN = 3  # shortest recall word run that counts as a dialogue echo
CONTEXT = 12  # recall words either side of a selection that feed lexical retrieval


def tokens(text: str) -> list:
    return [t.strip("'") for t in _TOKEN.findall(text.lower()) if t.strip("'")]


def content(text: str) -> list:
    return [t for t in tokens(text) if t not in STOP and len(t) > 1]


class BM25:
    def __init__(self, docs, k1=1.4, b=0.75):
        self.docs = [Counter(d) for d in docs]
        self.len = [sum(d.values()) for d in self.docs]
        self.avg = sum(self.len) / max(1, len(self.len))
        df = Counter(t for d in self.docs for t in d)
        n = len(self.docs)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}
        self.k1, self.b = k1, b

    def scores(self, query) -> list:
        q = Counter(query)
        out = []
        for d, ln in zip(self.docs, self.len):
            s = 0.0
            for t in q:
                f = d.get(t)
                if f:
                    s += (
                        self.idf[t]
                        * f
                        * (self.k1 + 1)
                        / (f + self.k1 * (1 - self.b + self.b * ln / self.avg))
                    )
            out.append(s)
        return out


class Candidates:
    def __init__(self, packet):
        self.p = packet
        film = packet.film_text()
        self.rows = {r["row"]: r for r in packet.rows}
        self.row_text = film["rows"]
        self.lines = []  # (part, t0, t1, text, row)
        for part, segs in film["lines"].items():
            for s in segs:
                mid = (s["t0"] + s["t1"]) / 2
                r = packet.row_at(int(part), mid)
                self.lines.append(
                    {
                        "part": int(part),
                        "t0": s["t0"],
                        "t1": s["t1"],
                        "text": s["text"],
                        "row": r["row"] if r else None,
                    }
                )
        self.docs = []  # one per row, then one per transcript line
        for r in packet.rows:
            t = self.row_text[r["row"]]
            self.docs.append(
                (
                    "row",
                    r["row"],
                    " ".join([t["details"], t["location"], t["onscreen"]]),
                )
            )
        for i, ln in enumerate(self.lines):
            self.docs.append(("line", i, ln["text"]))
        self.bm25 = BM25([content(d[2]) for d in self.docs])
        # Name tokens: every token of every Name-All entry, minus counts and connectives.
        self.name_rows = defaultdict(set)
        for r in packet.rows:
            for n in r["names"]:
                for t in tokens(n):
                    if t not in STOP and not t.isdigit() and t not in {"with"}:
                        self.name_rows[t].add(r["row"])
        # Transcript word stream per part, for echo.
        self.speech = {}
        for part, tr in packet.transcripts.items():
            ws = sorted(tr["words"], key=lambda w: w["index"])
            self.speech[part] = [(tokens(w["text"]) or [""])[0] for w in ws], ws
        self.recall = [(tokens(w) or [""])[0] for w in packet.recall_text()]
        self.scene_by_id = {s["scene"]: s for s in packet.scenes}

    # -- helpers ---------------------------------------------------------------------------
    def _moment(self, row, store):
        if row not in store:
            r = self.rows[row]
            store[row] = {
                "row": row,
                "part": r["part"],
                "t0": r["t0"],
                "t1": r["t1"],
                "scenes": r["scenes"],
                "score": 0.0,
                "signals": [],
                "cue": r["t0"],
            }
        return store[row]

    def _add(self, store, row, kind, reason, weight=None, cue=None):
        m = self._moment(row, store)
        w = WEIGHTS[kind] if weight is None else weight
        m["score"] += w
        m["signals"].append({"kind": kind, "reason": reason})
        if cue is not None and kind == "echo":
            m["cue"] = cue

    # -- signals ---------------------------------------------------------------------------
    def for_span(self, first: int, last: int, limit: int = 8) -> dict:
        """Candidates for recall word rows first..last (one-based, inclusive)."""
        n = len(self.recall)
        if not (1 <= first <= last <= n):
            raise ValueError("span outside the recall")
        store = {}
        words = self.p.census[first - 1 : last]
        # 1. Nomination: rows of the scenes the census nominates for these words.
        nom = {"one": set(), "zero": set(), "legacy": set()}
        for c in words:
            nom["one"].update(c["nomination_scenes"])
            nom["zero"].update(c["zero_based_tr_scenes"])
            nom["legacy"].update(c["legacy_seconds_scenes"])
        for sid in sorted(nom["one"]):
            for row in self.scene_by_id[sid]["rows"]:
                self._add(
                    store, row, "nomination", f"scene {sid} nominated (one-based TR)"
                )
        for sid in sorted((nom["zero"] | nom["legacy"]) - nom["one"]):
            conv = "zero-based TR" if sid in nom["zero"] else "closed-seconds"
            for row in self.scene_by_id[sid]["rows"]:
                self._add(
                    store, row, "nomination-alt", f"scene {sid} nominated ({conv} only)"
                )
        # 2. Name tokens inside the selection.
        for i in range(first - 1, last):
            t = self.recall[i]
            if t in self.name_rows:
                rows = sorted(self.name_rows[t])
                w = WEIGHTS["name"] / math.sqrt(
                    len(rows)
                )  # a ubiquitous name says little
                for row in rows:
                    self._add(
                        store,
                        row,
                        "name",
                        f"recall word {i + 1} matches a name token",
                        w,
                    )
        # 3. Lexical retrieval over the selection plus context.
        lo, hi = max(1, first - CONTEXT), min(n, last + CONTEXT)
        query = [
            t for t in self.recall[lo - 1 : hi] if t and t not in STOP and len(t) > 1
        ]
        scores = self.bm25.scores(query)
        top = max(scores) if scores else 0
        if top > 0:
            ranked = sorted(range(len(scores)), key=lambda k: -scores[k])[:10]
            for k in ranked:
                if scores[k] <= 0.25 * top:
                    break
                kind, ref, _ = self.docs[k]
                row = ref if kind == "row" else self.lines[ref]["row"]
                if row is None:
                    continue
                shared = sorted(set(query) & set(content(self.docs[k][2])))[:4]
                what = "annotation" if kind == "row" else "dialogue"
                self._add(
                    store,
                    row,
                    "lexical",
                    f"{what} shares: {', '.join(shared)}",
                    WEIGHTS["lexical"] * scores[k] / top,
                )
        # 4. Dialogue echo: runs of at least ECHO_MIN recall words that recur in speech.
        for part, (toks, ws) in self.speech.items():
            for a, b, j in self._echoes(lo, hi, toks):
                w0 = ws[j]
                r = self.p.row_at(part, w0["start_seconds"])
                if r:
                    self._add(
                        store,
                        r["row"],
                        "echo",
                        f"recall words {a}-{b} recur in dialogue",
                        cue=max(r["t0"], w0["start_seconds"] - 1.0),
                    )
        ranked = sorted(store.values(), key=lambda m: (-m["score"], m["part"], m["t0"]))
        for m in ranked:
            m["score"] = round(m["score"], 3)
        return {
            "span": [first, last],
            "nominations": {k: sorted(v) for k, v in nom.items()},
            "moments": ranked[:limit],
            "considered": len(ranked),
        }

    def _echoes(self, lo, hi, speech):
        """(first, last, speech index) for maximal recall runs of >= ECHO_MIN content-bearing
        words found verbatim in the speech token stream."""
        rec = self.recall[lo - 1 : hi]
        index = defaultdict(list)
        for j in range(len(speech) - ECHO_MIN + 1):
            index[tuple(speech[j : j + ECHO_MIN])].append(j)
        out, i = [], 0
        while i <= len(rec) - ECHO_MIN:
            key = tuple(rec[i : i + ECHO_MIN])
            hits = index.get(key, [])
            if not hits:
                i += 1
                continue
            j, k = hits[0], ECHO_MIN
            while (
                i + k < len(rec) and j + k < len(speech) and rec[i + k] == speech[j + k]
            ):
                k += 1
            # A run counts when it is long (4+ words) or carries two content words; three-word
            # runs of function words ("on the rocks") recur everywhere and point nowhere.
            if k >= 4 or sum(t not in STOP for t in rec[i : i + k]) >= 2:
                out.append((lo + i, lo + i + k - 1, j))
                i += k
            else:
                i += 1
        return out

    # -- search ----------------------------------------------------------------------------
    def search(self, q: str, limit: int = 12) -> list:
        query = content(q) or tokens(q)
        if not query:
            return []
        hits = []
        scores = self.bm25.scores(query)
        names = {t for t in tokens(q) if t in self.name_rows}
        for k, s in enumerate(scores):
            kind, ref, text = self.docs[k]
            row = ref if kind == "row" else self.lines[ref]["row"]
            if row is None:
                continue
            bonus = 0.0
            if kind == "row" and names:
                rn = {t for n in self.rows[row]["names"] for t in tokens(n)}
                bonus = 1.0 * len(names & rn)
            if s + bonus <= 0:
                continue
            r = self.rows[row]
            hits.append(
                {
                    "kind": kind,
                    "row": row,
                    "part": r["part"],
                    "t0": r["t0"] if kind == "row" else self.lines[ref]["t0"],
                    "t1": r["t1"] if kind == "row" else self.lines[ref]["t1"],
                    "scenes": r["scenes"],
                    "score": round(s + bonus, 3),
                }
            )
        hits.sort(key=lambda h: (-h["score"], h["part"], h["t0"]))
        return hits[:limit]
