"""Conditional fidelity of reciprocal transfers. Both candidate occurrences share the words."""
import argparse
import json
from collections import Counter
from pathlib import Path

from check import FIXTURE, packet, replay_grades, subject_lookup, write_packets
from storymodel import fidelity_prompt


def cases():
    events = json.loads(FIXTURE.read_text())["textSource"]["events"]
    roles = [event["text"].split()[1] for event in events]
    trials = {}
    for i in range(0, len(roles), 2):
        pair = roles[i : i + 2]
        obj = events[i]["text"].split()[-1].removesuffix(".")
        rows = [f"The {a} handed the {obj} to the {b}." for a, b in (pair, pair[::-1])]
        # Each source row has exactly the same word counts as its competitor.
        assert Counter(rows[0][:-1].lower().split()) == Counter(rows[1][:-1].lower().split())
        for row in (0, 1):
            for reversed_roles in (False, True):
                giver = pair[row ^ reversed_roles]
                recipient = pair[1 - (row ^ reversed_roles)]
                wordings = {
                    "active": f"The {giver} passed the {obj} to the {recipient}.",
                    "passive": f"The {recipient} was handed the {obj} by the {giver}.",
                }
                for voice, recall in wordings.items():
                    request, source = packet(rows, row, recall)
                    prompt = json.loads(fidelity_prompt(request))
                    key = prompt["packet_sha256"]
                    assert key not in trials
                    trials[key] = (prompt, request, source, (i, row), (voice, reversed_roles))
    return trials


def correct(outcome, reversed_roles):
    return (
        outcome in {"contradicted", "gist-detail-error"}
        if reversed_roles
        else outcome == "supported"
    )


def score(path):
    trials = cases()
    cells = {}
    groups = {}
    for event, (voice, flipped), outcome in replay_grades(path, trials):
        label = voice + ("_reversed" if flipped else "_preserved")
        cells.setdefault(label, Counter())[outcome] += 1
        groups.setdefault(event, []).append(correct(outcome, flipped))
    return dict(
        outcomes=cells,
        correct=sum(sum(g) for g in groups.values()),
        complete_quartets=sum(all(g) for g in groups.values()),
        total=len(trials),
        first_subject_lookup=sum(correct(subject_lookup(v[0]), v[-1][1]) for v in trials.values()),
        fixed_row_always_supported=sum(not v[-1][1] for v in trials.values()),
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "score"))
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    if args.mode == "prepare":
        write_packets(args.path, cases())
    else:
        print(json.dumps(score(args.path), indent=2))
