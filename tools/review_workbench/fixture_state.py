#!/usr/bin/env python3
"""Write a review log for the synthetic specimen in a named state, through the Store's own
checked API, so UI checks and hill-climb specimens start from reproducible states.

  fresh        nothing reviewed
  source-done  every source row done (recall pass open), nothing in the recall pass
  midway       source done, three windows read, four decisions (one awaiting step 2)

Usage: python3 -I fixture_state.py MANIFEST PRIVATE_DIR STATE   (PRIVATE_DIR must not exist)
"""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from packet import Packet  # noqa: E402
from store import Store  # noqa: E402


def complete_source(store):
    p = store.p
    unnamed_boat = None
    for r in p.rows:
        film = p.film_text()["rows"][r["row"]]
        unnamed = []
        if r["row"] == 15:  # the boat at sea: an unnamed figure, reused later
            unnamed_boat = store.create_unnamed(
                "figure in the small boat", r["scenes"][0]
            )["uid"]
        if r["row"] == 19 and unnamed_boat:
            unnamed = [unnamed_boat]
        res = {}
        for m in store.mentions[r["row"]]:
            if m["name"]:
                res[m["id"]] = m["name"] if m["name"] in film["names"] else "absent"
            elif film["focus"] and film["focus"][0] in film["names"]:
                res[m["id"]] = film["focus"][0]
            else:
                res[m["id"]] = "unknown"
        store.source_row(r["row"], film["names"], unnamed, res, True)


def find(words, phrase, start=1):
    toks = phrase.split()
    for i in range(start - 1, len(words) - len(toks) + 1):
        if [w.lower() for w in words[i : i + len(toks)]] == toks:
            return i + 1, i + len(toks)
    raise ValueError(phrase)


def midway(store):
    words = store.p.recall_text()
    for w in (1, 2, 3):
        store.read_window(w)
    plan = [
        (
            "this woman mara",
            "description",
            "Mara",
            "experiencer",
            [2],
            ["row-1"],
            "supported",
        ),
        ("she", "pronoun", "Mara", "agent", [1], ["row-1"], "supported"),
        ("this guy", "description", "Tobias", "agent", [2], ["row-4"], "supported"),
        (
            "the old man the harbour guy",
            "description",
            "Harbourmaster",
            "speaker",
            None,
            None,
            None,
        ),
    ]
    for phrase, form, ref, role, scenes, anchors, outcome in plan:
        a, b = find(words, phrase)
        did = store.start_decision()["id"]
        store.commit_reference(
            did,
            {
                "first_word": a,
                "last_word": b,
                "other_coref_spans": [],
                "reference_form": form,
                "claim_role": role,
                "occurrence_group": f"g{did[1:].lstrip('0')}",
                "person_reference": "yes",
                "referent": ref,
            },
        )
        if store.decisions()[did]["calibration"]:
            store.reveal(did)
        if scenes:
            store.commit_source(
                did,
                {
                    "source_scene_ids": scenes,
                    "source_anchor_ids": anchors,
                    "source_resolvable": "yes",
                    "reference_outcome": outcome,
                    "ambiguity_reason": "",
                },
            )


def main(argv):
    manifest, private, state = argv
    if Path(private).exists():
        raise SystemExit("private dir must be new")
    store = Store(Packet(Path(manifest)), Path(private))
    if state in ("source-done", "midway"):
        complete_source(store)
    if state == "midway":
        midway(store)
    if state not in ("fresh", "source-done", "midway"):
        raise SystemExit(f"unknown state {state}")
    print(f"{state}: {len(store.events)} events")


if __name__ == "__main__":
    main(sys.argv[1:])
