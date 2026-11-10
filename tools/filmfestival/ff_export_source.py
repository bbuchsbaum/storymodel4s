"""Write the FilmFestival annotation as a timed-annotation source for the common pipeline.

Design: docs/plans/2026-11-10-common-recorded-pipeline-design.md (slice 1).
Input format: docs/guide/study-inputs.md, "Timed annotations".

    python3 tools/filmfestival/ff_export_source.py check ANNOTATION.tsv
    python3 tools/filmfestival/ff_export_source.py export ANNOTATION.tsv OUTPUT_DIR

`export` writes `annotations.csv`, `parts.json` and `export-receipt.json` into OUTPUT_DIR, which
must not exist. The CSV holds annotation text, so keep OUTPUT_DIR outside the repository. The
receipt and `check` print counts and identities only.

What the file declares:

- rows       the annotation's segments, numbered by its `segment` column
- parts      the scanning runs. Times are whole seconds on the run's own clock
- groups     the release's global scene numbers, labelled `scene N` (the scenes have no names)
- collections  the films and cartoon intros in presentation order, labelled with the annotation's
             film name. The name's leading number is the collection ordinal
- target     a row is a target exactly when its scene is a valid scene answer in
             `ff_gold.load_dictionary`. That rule is per scene, so every row of a valid scene is
             a target, including a title-card row inside a scene that also has content. The
             reader's rule (a group is a target if any row is; a collection if any group is)
             then gives exactly the valid scenes and the ten films. The cartoon and the
             title-only scenes are source content that is not a target.
"""

import csv
import hashlib
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import ff_gold  # noqa: E402

HEADER = [
    "ordinal",
    "text",
    "part",
    "onset",
    "end",
    "group",
    "group_label",
    "locations",
    "characters",
    "collection",
    "collection_label",
    "target",
]
NOT_TARGET = "no"
USAGE = (
    "usage: ff_export_source.py check ANNOTATION.tsv\n"
    "       ff_export_source.py export ANNOTATION.tsv OUTPUT_DIR"
)
FILM_NAME = re.compile(r"([1-9][0-9]*)\. (\S.*)")
WHOLE = re.compile(r"0|[1-9][0-9]*")


class ExportError(ValueError):
    pass


def whole(value, what, segment):
    if not WHOLE.fullmatch(value):
        raise ExportError(f"segment {segment}: {what} {value!r} is not a whole second")
    return int(value)


def build(annotation_path):
    """The source as (csv rows without the header, parts, facts). Nothing is written.

    `facts` holds counts and the film-code table; it has no annotation text.
    """
    film_of, valid = ff_gold.load_dictionary(annotation_path)
    rows = ff_gold.read_tsv(annotation_path)
    collections, labels = {}, {}
    extent, part_order = {}, []
    out, seen, scene_collection = [], set(), {}
    for r in rows:
        segment = r["segment"]
        ordinal = whole(segment, "segment", segment)
        if ordinal == 0 or ordinal in seen:
            raise ExportError(f"segment {segment} is zero or repeated")
        seen.add(ordinal)
        if not r["description"].strip():
            raise ExportError(f"segment {segment} has no description")
        key = (r["run"], r["film"])
        if key not in collections:
            match = FILM_NAME.fullmatch(r["film"])
            number = len(collections) + 1
            if not match or int(match.group(1)) != number:
                raise ExportError(
                    f"film name {r['film']!r} does not start with its position {number}"
                )
            # A film's collection carries the film number the prompts show and the replies use
            # (1-10), so no reader needs a translation table. The two cartoon intros, which are
            # not films of the study, take the numbers after them.
            code = film_of[whole(r["scene_number"], "scene", segment)]
            if code == ff_gold.CARTOON:
                code = (
                    max(ff_gold.FILMS)
                    + 1
                    + sum(1 for c in collections.values() if c > max(ff_gold.FILMS))
                )
            if code in labels:
                raise ExportError(f"two films would take collection {code}")
            collections[key] = code
            labels[code] = match.group(2)
        collection = collections[key]
        scene = whole(r["scene_number"], "scene", segment)
        if scene_collection.setdefault(scene, collection) != collection:
            raise ExportError(f"scene {scene} is in two films")
        part = r["part_id"]
        if not part:
            raise ExportError(f"segment {segment} has no part")
        if part not in extent:
            part_order.append(part)
            extent[part] = 0
        onset = whole(r["start_s"], "start", segment)
        if r["end_s"] == "":
            end = ""
            # A point event occupies its own tick, so the declared extent must pass it.
            extent[part] = max(extent[part], onset + 1)
        else:
            end = whole(r["end_s"], "end", segment)
            if end <= onset:
                raise ExportError(f"segment {segment} ends at or before its start")
            extent[part] = max(extent[part], end)
        out.append(
            [
                str(ordinal),
                r["description"],
                part,
                str(onset),
                str(end),
                str(scene),
                f"scene {scene}",
                "",
                "",
                str(collection),
                labels[collection],
                # A row is a target unless its scene is not a valid target or the row itself is
                # a title card. A scene that mixes a title row with content (one exists) keeps
                # the content row, so the scene stays a target and its first target row is the
                # first content row, which is where the film's clip starts.
                ""
                if scene in valid and ff_gold.TITLE_MARK not in r["description"]
                else NOT_TARGET,
            ]
        )
    parts = [
        {
            "part": part,
            "start": "0",
            "end": str(extent[part]),
            "numerator": "1",
            "denominator": "1",
        }
        for part in part_order
    ]
    target_scenes = sorted({int(r[5]) for r in out if r[11] == ""})
    target_collections = sorted({int(r[9]) for r in out if r[11] == ""})
    # The film code of the reference labels (1-10; 11 is the cartoon) for each collection.
    codes = {}
    for scene, collection in scene_collection.items():
        if codes.setdefault(collection, film_of[scene]) != film_of[scene]:
            raise ExportError(f"collection {collection} has two film codes")
    film_collections = {code: c for c, code in codes.items() if code != ff_gold.CARTOON}
    if target_scenes != sorted(valid):
        raise ExportError("target scenes differ from the dictionary's valid scenes")
    if sorted(film_collections) != list(ff_gold.FILMS) or target_collections != sorted(
        film_collections.values()
    ):
        raise ExportError("target collections differ from the ten films")
    facts = {
        "rows": len(out),
        "targetRows": sum(1 for r in out if r[11] == ""),
        "groups": len(scene_collection),
        "collections": len(collections),
        "targetGroups": target_scenes,
        "targetCollections": target_collections,
        "collectionOfFilmCode": {
            str(code): film_collections[code] for code in sorted(film_collections)
        },
        "parts": parts,
    }
    return out, parts, facts


def render(rows):
    """The CSV text: comma-separated, LF line ends, quotes doubled inside quoted cells."""
    sink = io.StringIO(newline="")
    writer = csv.writer(sink, lineterminator="\n")
    writer.writerow(HEADER)
    writer.writerows(rows)
    return sink.getvalue()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def summary(facts):
    """Lines for `check`: counts and target sets, no annotation text."""
    return [
        f"rows={facts['rows']}",
        f"targetRows={facts['targetRows']}",
        f"groups={facts['groups']}",
        f"collections={facts['collections']}",
        f"targetGroupCount={len(facts['targetGroups'])}",
        f"targetCollectionCount={len(facts['targetCollections'])}",
        "targetGroups=" + ",".join(str(n) for n in facts["targetGroups"]),
        "targetCollections=" + ",".join(str(n) for n in facts["targetCollections"]),
        "collectionOfFilmCode="
        + ",".join(f"{k}:{v}" for k, v in facts["collectionOfFilmCode"].items()),
        "parts="
        + ",".join(f"{p['part']}:[{p['start']},{p['end']})" for p in facts["parts"]),
    ]


def export(annotation_path, output_dir):
    """Write the three files into a new directory and return the receipt."""
    rows, parts, facts = build(annotation_path)
    csv_bytes = render(rows).encode("utf-8")
    parts_bytes = json.dumps(parts).encode("utf-8")
    with open(annotation_path, "rb") as handle:
        annotation_sha = sha(handle.read())
    receipt = dict(facts)
    receipt.update(
        {
            "recipe": "filmfestival-timed-source/v1",
            "annotationSha256": annotation_sha,
            "csvSha256": sha(csv_bytes),
            "partsSha256": sha(parts_bytes),
        }
    )
    try:
        os.makedirs(output_dir, exist_ok=False)
    except FileExistsError:
        raise ExportError(f"{output_dir} exists; choose a new directory") from None
    for name, data in (
        ("annotations.csv", csv_bytes),
        ("parts.json", parts_bytes),
        (
            "export-receipt.json",
            (json.dumps(receipt, indent=2) + "\n").encode("utf-8"),
        ),
    ):
        with open(os.path.join(output_dir, name), "xb") as handle:
            handle.write(data)
    return receipt


def main(argv):
    try:
        if len(argv) == 3 and argv[1] == "check":
            _, _, facts = build(argv[2])
        elif len(argv) == 4 and argv[1] == "export":
            facts = export(argv[2], argv[3])
        else:
            print(USAGE, file=sys.stderr)
            return 2
    except (ExportError, ff_gold.GoldError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    print("\n".join(summary(facts)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
