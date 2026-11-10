#!/usr/bin/env python3
"""Export one FilmFestival run's inputs for the generic recorded pipeline.

Design: docs/plans/2026-11-10-common-recorded-pipeline-design.md ("Prompts", "Recall").

Usage: ff_export_run.py DATA_ROOT MANIFEST OUTPUT_DIR

MANIFEST is the run's tracked, frozen manifest. The export is what the generic replay needs and
nothing corpus-specific beyond it:

- `index.json`: the system prompt, the model, condition, chunk size, attempts and retry suffix,
  the manifest's hash, and per participant-session the hash of each prompt;
- `sessions/<participant>_<session>.json`: the session's recall units (unit number, the study's
  own row key, text, onset and end in seconds) and its chunks (first and last unit, the exact
  prompt text).

Everything is rendered by the run's own code (`ff_map.plan`), and the export refuses unless that
plan equals MANIFEST, so the prompts exported are the prompts that were sent. The output holds
recall text: write it under the ignored data root or a scratch directory, never into the
repository.
"""

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402
import ff_map  # noqa: E402
import ff_packet  # noqa: E402

SCHEMA = "storymodel4s.agentmapping.study-export/v1"
# The rules by which each condition's replies were read (see StudyReplay.ReplyProtocol). The
# time conditions read other fields and get a protocol of their own; until then they refuse.
REPLY_PROTOCOL = {ff_map.ANNOTATION: "strict-chunked/v1"}


class ExportError(ValueError):
    pass


def build(data_root, manifest_path):
    """(index, {file name: session document}) for the run MANIFEST describes."""
    with open(manifest_path, encoding="utf-8") as handle:
        tracked = json.load(handle)
    condition_name = tracked.get("condition", ff_map.ANNOTATION)
    if condition_name not in REPLY_PROTOCOL:
        raise ExportError(
            f"no reply protocol is defined yet for the {condition_name} condition"
        )
    made = ff_map.plan(data_root, tracked.get("model"), condition_name)
    if made["manifest"] != tracked:
        raise ExportError("the rendered run differs from the manifest")
    condition, packet = made["condition"], made["packet"]
    gold_path = ff_map.paths(data_root)[0]
    times = {}
    for r in ff_gold.read_tsv(gold_path):
        key = (r["participant"], r["run"], int(r["gold_row"]))
        if key in times:
            raise ExportError(f"the reference repeats {key}")
        times[key] = (r["start_s"], r["end_s"])
    sessions, entries = {}, []
    for entry in tracked["sessions"]:
        participant, session = entry["participant"], entry["session"]
        units = ff_packet.units(gold_path, participant, session)
        chunks = ff_packet.chunks(packet, units, tracked["chunk"], condition.heading)
        if [ff_packet.sha(p) for _, _, p in chunks] != entry["promptSha256"]:
            raise ExportError(
                f"prompts of {participant} {session} are not the manifest's"
            )
        name = f"{participant}_{session}.json"
        sessions[name] = {
            "participant": participant,
            "session": session,
            "units": [
                {
                    "u": u,
                    "row": row,
                    "text": text,
                    "onset": times[(participant, session, row)][0],
                    "end": times[(participant, session, row)][1],
                }
                for u, row, text in units
            ],
            "chunks": [{"lo": lo, "hi": hi, "prompt": p} for lo, hi, p in chunks],
        }
        entries.append(
            {
                "participant": participant,
                "session": session,
                "file": f"sessions/{name}",
                "units": len(units),
                "promptSha256": entry["promptSha256"],
            }
        )
    index = {
        "schema": SCHEMA,
        "study": "filmfestival",
        "condition": condition_name,
        "model": tracked["model"],
        "system": condition.system,
        "systemSha256": ff_packet.sha(condition.system),
        "chunk": tracked["chunk"],
        "replyProtocol": REPLY_PROTOCOL[condition_name],
        "attempts": tracked["attempts"],
        "retrySuffix": tracked["retrySuffix"],
        "extras": list(condition.extras),
        "unitLine": "[{u}] {text}",
        "chunkLine": "TARGET UNITS: map every unit numbered {lo} through {hi} inclusive.",
        "manifestSha256": ff_map.file_sha(manifest_path),
        "sessions": entries,
    }
    return index, sessions


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    data_root, manifest_path, out = argv
    if os.path.exists(out):
        print("refused: output exists", file=sys.stderr)
        return 2
    try:
        index, sessions = build(data_root, manifest_path)
    except (ExportError, ff_gold.GoldError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    os.makedirs(os.path.join(out, "sessions"))
    for name, doc in sessions.items():
        ff_map.write_text(
            os.path.join(out, "sessions", name), json.dumps(doc, indent=1) + "\n"
        )
    ff_map.write_text(
        os.path.join(out, "index.json"), json.dumps(index, indent=1) + "\n"
    )
    units = sum(e["units"] for e in index["sessions"])
    calls = sum(len(e["promptSha256"]) for e in index["sessions"])
    print(
        f"sessions={len(sessions)} units={units} prompts={calls} model={index['model']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
