"""Select, blind and replay the fixed 58-segment name-order challenge."""
import argparse
import json
from collections import Counter
from pathlib import Path

import assay
from replay import replay


def key(row):
    return assay.sha(f"speaker-followup/packet/v1/{row.id}")[:16]


def first(row):
    return assay.names(row)[0]


def credit(row):
    return row.speaking.strip().title()


def pairs():
    prior = [r for arm, _, rows in assay.trials().values() if arm == "description" for r in rows]
    scenes = {r.scene for r in prior}
    used = {r.id for r in prior}
    pool = sorted(
        (
            r
            for r in assay.film_rows()
            if r.scene in scenes
            and r.id not in used
            and credit(r) in assay.PEOPLE
            and len(set(assay.names(r))) == 2
        ),
        key=lambda r: assay.sha(f"speaker-followup/selection/v1/{r.id}"),
    )
    seconds = [r for r in pool if first(r) != credit(r)]
    controls = [r for r in pool if first(r) == credit(r)]
    assert (len(seconds), len(controls)) == (29, 96)
    matches = {}
    for same_scene in (True, False):
        for case in seconds:
            if case.id in matches:
                continue
            control = next(
                (
                    c
                    for c in controls
                    if first(c) == first(case) and (not same_scene or c.scene == case.scene)
                ),
                None,
            )
            if control is not None:
                matches[case.id] = control
                controls.remove(control)
    selected = [(r, matches[r.id]) for r in seconds]
    rows = [r for pair in selected for r in pair]
    assert len({key(r) for r in rows}) == len({r.desc for r in rows}) == 58
    assert not ({r.desc for r in prior} & {r.desc for r in rows})
    assert Counter(credit(r) for r in rows) == {"John": 29, "Sherlock": 29}
    return selected


def cases_for(rows):
    return {key(r): ("description", r.desc, [r]) for r in rows}


def score(cases, readings):
    if readings.keys() != cases.keys():
        raise ValueError("reading does not cover the frozen packet set")
    totals = {"first": Counter(), "second": Counter()}
    grades = Counter()
    for k, (arm, text, (row,)) in cases.items():
        rec = readings[k]
        choice = rec["choice"]
        assert rec["text_sha256"] == assay.sha(text)
        assert choice in (*assay.PEOPLE, None)
        assert (choice is None) == (rec["evidence"] is None)
        for candidate in assay.PEOPLE:
            grades[replay(k, arm, text, rec, candidate)] += 1
        group = "first" if first(row) == credit(row) else "second"
        outcome = "abstained" if choice is None else "correct" if choice == credit(row) else "wrong"
        totals[group][outcome] += 1
    return dict(by_credit_order=totals, all=totals["first"] + totals["second"], replay=grades)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "normalize", "score"))
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    matched = pairs()
    cases = cases_for([r for pair in matched for r in pair])
    if args.action == "prepare":
        args.path.mkdir(mode=0o700, parents=True, exist_ok=True)
        packets = [dict(id=k, text=cases[k][1]) for k in sorted(cases)]
        with (args.path / "description.json").open("x") as out:
            json.dump(
                dict(instruction=assay.TASK, packets=packets), out, ensure_ascii=False, indent=2
            )
    elif args.action == "normalize":
        print(
            json.dumps(
                assay.normalize(args.path, cases, ("description",)), indent=2, sort_keys=True
            )
        )
    else:
        report = score(cases, json.loads(args.path.read_text()))
        report["same_scene_pairs"] = sum(a.scene == b.scene for a, b in matched)
        report["distinct_scenes"] = len({r.scene for pair in matched for r in pair})
        print(json.dumps(report, indent=2, sort_keys=True))
