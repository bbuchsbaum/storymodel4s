"""Compare word overlap to word overlap with the credited speaker in the same film row."""
import csv
import hashlib
import json
import math
import re
import sys
from collections import Counter, defaultdict

import retrieval as r

sys.path.insert(0, str(r.assay.HERE.parents[2] / "tools/filmfestival"))
from ff_baselines import tfidf, tokens  # noqa: E402

ARMS = ("base", "words", "speaker", "swapped", "lookup_words", "lookup_speaker")
PEOPLE = r.assay.PEOPLE
PARTS = {"a": "Sherlock_part1_imovie", "b": "Sherlock_part2_imovie"}
FROZEN = "caf9a590a558696a53b6af089f1322547cd77e56d2669ff978c165486c6d872b"


def tsv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def fingerprint():
    inputs = [bytes.fromhex(r.input_digest())]
    for part in PARTS.values():
        for filename in ("segments.tsv", "words.tsv"):
            inputs.append(hashlib.sha256((r.c.ASR / part / filename).read_bytes()).digest())
    return hashlib.sha256(b"".join(inputs)).hexdigest()


def vocabulary(text):
    return [w for w in tokens(text) if w not in r.ALIASES]


def index():
    rows = r.c.film_rows()
    docs = defaultdict(Counter)
    report = Counter()
    for part, folder in PARTS.items():
        segments = {int(x["index"]): x["suspect"] for x in tsv(r.c.ASR / folder / "segments.tsv")}
        source = [row for row in rows if row.part == part]
        for word in tsv(r.c.ASR / folder / "words.tsv"):
            report["asr_words"] += 1
            if segments[int(word["segment_index"])] != "False":
                continue
            start, end = float(word["start_seconds"]), float(word["end_seconds"])
            assert math.isfinite(start) and math.isfinite(end) and 0 <= start <= end
            mid = (start + end) / 2
            hits = [row for row in source if row.start <= mid < row.end]
            if len(hits) != 1:
                continue
            report["uniquely_timed_words"] += 1
            row = hits[0]
            if row.speaking.strip().title() not in PEOPLE:
                continue
            report["single_candidate_credit_words"] += 1
            docs[row.id].update(vocabulary(word["text"]))
    docs = {k: v for k, v in docs.items() if v}
    idf, vectors = tfidf(docs)
    entries = [
        (row.scene - 1, row.speaking.strip().title(), vectors[row.id])
        for row in rows
        if row.id in vectors
    ]
    report["searchable_rows"] = len(entries)
    report["represented_scenes"] = len({s for s, _, _ in entries})
    return idf, entries, dict(report)


def query(text, idf):
    actor = r.speaker(text)
    if not actor:
        return None, {}
    cue = r.CUE.search(text)
    suffix = r.CUE.split(text[cue.end() :], maxsplit=1)[0]
    clause = re.split(r"[.!?;]", suffix, maxsplit=1)[0]
    counts = Counter(w for w in vocabulary(" ".join(clause.split()[:32])) if w in idf)
    v = {w: (1 + math.log(n)) * idf[w] for w, n in counts.items()}
    norm = math.sqrt(sum(x * x for x in v.values()))
    return actor, {w: x / norm for w, x in v.items()}


def strengths(q, actor, entries):
    best = {name: [0.0] * r.S for name in PEOPLE}
    for i, name, vector in entries:
        score = sum(x * vector.get(w, 0.0) for w, x in q.items())
        best[name][i] = max(best[name][i], score)
    other = next(n for n in PEOPLE if n != actor)
    both = [max(a, b) for a, b in zip(best[actor], best[other], strict=True)]
    return both, best[actor], best[other]


def weighted(base, scores):
    if not any(scores):
        return base
    return r.normalized([p * (1 + s) for p, s in zip(base, scores, strict=True)])


def predict():
    idf, entries, source = index()
    uniform = [1 / r.S] * r.S
    sessions, flags = {}, {}
    for name in r.c.development():
        r.c.guard(name)
        arms_by_id = {}
        for u, base in r.baseline_units(name):
            actor, q = query(u.text, idf)
            words, same, swapped = strengths(q, actor, entries) if q else ([0.0] * r.S,) * 3
            arms_by_id[u.id] = (
                actor,
                dict(
                    base=base,
                    words=weighted(base, words),
                    speaker=weighted(base, same),
                    swapped=weighted(base, swapped),
                    lookup_words=weighted(uniform, words),
                    lookup_speaker=weighted(uniform, same),
                ),
            )
            flags[name, u.id] = (bool(q), any(same), same != words)
        sessions[name] = arms_by_id
    return sessions, flags, source


def top(p):
    m = max(p)
    return {i for i, v in enumerate(p) if v == m}


def diagnostics(sessions, flags):
    counts = Counter()
    for row in json.loads((r.c.RUN / "support.json").read_text())["rows"]:
        if row["exclusion"] is not None or row["goldScene"] is None:
            continue
        name, uid = row["participant"], row["id"]
        actor, arms = sessions[name][uid]
        if actor is None:
            continue
        q, matched, changed = flags[name, uid]
        counts["in_vocabulary_query"] += q
        counts["positive_same_speaker_overlap"] += matched
        counts["speaker_changes_scene_evidence"] += changed
        for prefix, a, b in (
            ("reranked", "words", "speaker"),
            ("lookup", "lookup_words", "lookup_speaker"),
        ):
            counts[prefix + "_changed_top_set"] += top(arms[a]) != top(arms[b])
    return dict(counts)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fingerprint", "run"))
    args = parser.parse_args()
    digest = fingerprint()
    if args.action == "fingerprint":
        print(digest)
    else:
        if digest != FROZEN:
            raise ValueError("input fingerprint differs from the frozen edition")
        sessions, flags, source = predict()
        result = r.evaluate(sessions, arm_names=ARMS, baseline="words")
        print(
            json.dumps(
                dict(
                    input_sha256=digest,
                    source=source,
                    coverage=diagnostics(sessions, flags),
                    **result,
                ),
                indent=2,
            )
        )
