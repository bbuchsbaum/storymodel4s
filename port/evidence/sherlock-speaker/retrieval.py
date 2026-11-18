"""Paired, local replay of Sherlock scene retrieval with explicit speaker evidence."""
import argparse
import hashlib
import json
import math
import re
import statistics
from collections import Counter
from pathlib import Path

import assay
import common as c
from decode import DEFAULT, S, emissions

RUN = "a1v2-opus"
ARMS = ("off", "speaker", "presence", "swapped")
ALIASES = {"sherlock": "Sherlock", "holmes": "Sherlock", "john": "John", "watson": "John"}
CUE = re.compile(
    r"\b(sherlock(?:\s+holmes)?|holmes|john(?:\s+watson)?|watson)\b"
    r"[\s,]+(?:(?:he|then|also|just|basically|finally|actually)[\s,]+){0,2}"
    r"(?:(?:is|was)\s+(?:saying|asking|telling|talking|speaking|explaining)|"
    r"says?|said|asks?|asked|tells?|told|replies|replied|answers?|answered|"
    r"explains?|explained|speaks?|spoke|talks?|talked)\b",
    re.I,
)


def speaker(text):
    found = {ALIASES[m.group(1).lower().split()[0]] for m in CUE.finditer(text)}
    return found.pop() if len(found) == 1 else None


def source_rates(rows, field):
    sizes = Counter(r.scene for r in rows)
    assert set(sizes) == set(range(1, S + 1))
    rates = {}
    for name in assay.PEOPLE:
        hits = Counter(
            r.scene
            for r in rows
            if name.lower() in {piece.strip().lower() for piece in getattr(r, field).split(",")}
        )
        rates[name] = [hits[i] / sizes[i] for i in range(1, S + 1)]
    return rates


def normalized(weights):
    z = math.fsum(weights)
    return [v / z for v in weights]


def forecasts():
    # No gold or evaluation-support loader is called during prediction.
    rows = c.film_rows()
    rates = source_rates(rows, "speaking")
    present = source_rates(rows, "names")
    sessions = {}
    for name in c.development():
        c.guard(name)
        units = c.units(name)
        saved = json.loads((c.OUT / RUN / (name + ".json")).read_text())
        assert saved["participant"] == name and not saved["missing"]
        predictions = {}
        for u, record in zip(units, saved["units"], strict=True):
            assert (u.id, u.index) == (record["id"], record["index"])
            assert u.id not in predictions
            base = normalized([math.exp(x) for x in emissions(record, DEFAULT[4])])
            actor = speaker(u.text)
            arms = dict.fromkeys(ARMS, base)
            if actor is not None:
                other = next(p for p in assay.PEOPLE if p != actor)
                for arm, weights in (
                    ("speaker", rates[actor]),
                    ("presence", present[actor]),
                    ("swapped", rates[other]),
                ):
                    arms[arm] = normalized(
                        [p * (1 + w) for p, w in zip(base, weights, strict=True)]
                    )
            predictions[u.id] = (actor, arms)
        sessions[name] = predictions
    return sessions


def evaluate(sessions):
    support = json.loads((c.RUN / "support.json").read_text())["rows"]
    expected = {(r["participant"], r["id"]) for r in support}
    assert len(expected) == len(support)
    assert expected == {(n, k) for n, records in sessions.items() for k in records}
    scores = {}
    coverage = Counter()
    for row in support:
        if row["exclusion"] is not None or row["goldScene"] is None:
            continue
        name, uid = row["participant"], row["id"]
        idx = row["goldScene"] - 1
        assert 0 <= idx < S
        actor, arms = sessions[name][uid]
        buckets = ["all"]
        if actor:
            coverage[actor] += 1
            buckets.append("cued")
        for arm, probs in arms.items():
            assert len(probs) == S and min(probs) > 0 and math.isclose(math.fsum(probs), 1)
            prob = probs[idx]
            higher = sum(p > prob for p in probs)
            tied = probs.count(prob)
            metrics = (-math.log2(prob), 0 if higher else 1 / tied, 1 + higher + (tied - 1) / 2)
            for bucket in buckets:
                scores.setdefault((name, bucket, arm), []).append(metrics)
    result = {}
    for bucket in ("all", "cued"):
        per = []
        for serial, name in enumerate(sorted(sessions), 1):
            raw = scores.get((name, bucket, "off"))
            if not raw:
                continue
            rec = {"session": serial, "units": len(raw)}
            for arm in ARMS:
                measurements = scores[name, bucket, arm]
                rec[arm] = dict(
                    zip(
                        ("loss_bits", "top1", "gold_rank"),
                        map(statistics.mean, zip(*measurements)),
                        strict=True,
                    )
                )
            per.append(rec)
        macro = {a: {m: statistics.mean(r[a][m] for r in per) for m in per[0][a]} for a in ARMS}
        gains = [r["off"]["loss_bits"] - r["speaker"]["loss_bits"] for r in per]
        result[bucket] = dict(
            per_session=per,
            participants=len(per),
            units=sum(r["units"] for r in per),
            macro=macro,
            speaker_gain_bits=statistics.mean(gains),
            participants_improved=sum(g > 0 for g in gains),
        )
    return dict(
        result=result,
        cue_counts=dict(coverage),
        development_sessions=len(sessions),
        mapped_units=sum(map(len, sessions.values())),
    )


def input_digest():
    names = c.development()
    paths = [c.SEGMENTS, c.DATA / "study/recall-to-video/partition.json", c.RUN / "support.json"]
    for n in names:
        base = c.RUN / "A" / ("recall-map-" + n + ".tsv")
        paths.extend([base, Path(str(base) + ".stages.json"), c.OUT / RUN / (n + ".json")])
    return hashlib.sha256(
        b"".join(hashlib.sha256(p.read_bytes()).digest() for p in paths)
    ).hexdigest()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("fingerprint", "run"))
    args = parser.parse_args()
    digest = input_digest()
    if args.action == "fingerprint":
        print(digest)
    else:
        if digest != "0e5560aa1a4b0d79ec32b1b2e561ab57a29742a82439f18178ff8efeb0ecba2f":
            raise ValueError("frozen input bytes differ")
        print(json.dumps(dict(input_sha256=digest, **evaluate(forecasts())), indent=2))
