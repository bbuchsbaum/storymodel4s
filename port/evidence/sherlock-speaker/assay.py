"""Prepare blinded, source-only Sherlock speaker packets and score recorded decisions."""
import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "tools/sherlock_mapper"))
from common import SEGMENTS, film_rows  # noqa: E402

SOURCE_SHA = "8c205826dcea8c58db24d7a17c71a3f99a9054e9379435e60b1dd0b987c2b296"
PEOPLE = ("Sherlock", "John")
ARMS = ("credited", "description", "masked")
NEUTRAL = "Someone speaks during this segment."
TASK = (
    "Judge each packet independently using only its text. Which single candidate is identified "
    "as speaking during this segment, Sherlock or John? A speaker credit is direct evidence. "
    "In a description, being present, acting, or being addressed does not by itself identify "
    "a speaker. If neither or both are supported, return null. Never guess. Return a JSON "
    "array of objects with id, choice (Sherlock, John, or null), and quote (a unique verbatim "
    "substring supporting the attribution, or null for abstention)."
)


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


def names(row):
    return [n.title() for n in re.findall(r"\b(?:sherlock|john)\b", row.desc, re.I)]


def trials():
    if hashlib.sha256(SEGMENTS.read_bytes()).hexdigest() != SOURCE_SHA:
        raise ValueError("source edition changed")
    scenes = defaultdict(dict)
    for row in sorted(film_rows(), key=lambda r: r.id):
        speaker = row.speaking.strip().title()
        if speaker in PEOPLE:
            scenes[row.scene].setdefault(speaker, row)
    selected = [r for pair in scenes.values() if len(pair) == 2 for r in pair.values()]
    assert len(selected) == 38 and len({r.scene for r in selected}) == 19
    assert Counter(len(set(names(r))) for r in selected) == {1: 24, 2: 14}
    cases = {}
    for arm in ARMS:
        for row in selected:
            text = row.desc
            if arm == "credited":
                text += "\nSpeaker credit: " + row.speaking.strip().title() + "."
            if arm == "masked":
                text = NEUTRAL
            identity = "null" if arm == "masked" else str(row.id)
            key = sha(f"speaker-control/v1/{arm}/{identity}")[:16]
            if key in cases:
                assert arm == "masked" and cases[key][1] == text
            else:
                cases[key] = (arm, text, [])
            cases[key][2].append(row)
    return cases


def prepare(directory):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    cases = trials()
    for arm in ARMS:
        packets = [dict(id=k, text=t) for k, (a, t, _) in sorted(cases.items()) if a == arm]
        with (directory / (arm + ".json")).open("x") as out:
            json.dump(dict(instruction=TASK, packets=packets), out, ensure_ascii=False, indent=2)


def utf16(text):
    return len(text.encode("utf-16-le")) // 2


def normalize(directory):
    from storymodel.model import Span

    cases = trials()
    readings = {}
    for arm in ARMS:
        answers = json.loads((directory / (arm + "-answers.json")).read_text())
        for answer in answers:
            key, choice, quote = (answer[k] for k in ("id", "choice", "quote"))
            if key in readings or key not in cases or cases[key][0] != arm:
                raise ValueError("duplicate, unknown or misplaced answer")
            text = cases[key][1]
            rec = dict(choice=choice, evidence=None, text_sha256=sha(text))
            if choice is None and quote is None:
                pass
            elif choice in PEOPLE and isinstance(quote, str) and quote and text.count(quote) == 1:
                start = utf16(text[: text.index(quote)])
                span = Span(start=start, end=start + utf16(quote))
                assert span.slice(text) == quote
                rec["evidence"] = span.model_dump()
            else:
                raise ValueError(f"invalid choice or citation for {key}")
            readings[key] = rec
    if readings.keys() != cases.keys():
        raise ValueError("missing answers")
    return readings


def score(readings):
    from storymodel.model import Span

    cases = trials()
    if readings.keys() != cases.keys():
        raise ValueError("packet set differs")
    counts = defaultdict(lambda: defaultdict(Counter))
    for key, (arm, text, rows) in cases.items():
        reading = readings[key]
        choice = reading["choice"]
        assert reading["text_sha256"] == sha(text)
        assert choice in (*PEOPLE, None)
        assert (choice is None) == (reading["evidence"] is None)
        if choice is not None:
            assert Span.model_validate(reading["evidence"]).slice(text)
        for row in rows:
            labels = names(row)
            group = "both" if len(set(labels)) == 2 else "one"
            decisions = {arm: choice}
            if arm == "description":
                decisions.update(sole=labels[0] if group == "one" else None, first=labels[0])
            for method, predicted in decisions.items():
                gold = row.speaking.strip().title()
                outcome = (
                    "abstain" if predicted is None else "correct" if predicted == gold else "wrong"
                )
                for stratum in ("all", group):
                    counts[method][stratum][outcome] += 1
    return counts


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "normalize", "score"])
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.path)
    elif args.action == "normalize":
        print(json.dumps(normalize(args.path), indent=2, sort_keys=True))
    else:
        print(json.dumps(score(json.loads(args.path.read_text())), indent=2, sort_keys=True))
