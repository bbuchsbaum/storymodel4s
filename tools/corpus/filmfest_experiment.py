#!/usr/bin/env python3
"""Prepare fixed development arms without reading recall gold or scoring any outcome.

The source-independence comparison measures FILM IDENTITY ONLY. Every candidate in a film carries
that film's complete annotation interval, not an invented crowd-window timestamp. Both arms have
no scene groups. The crowd arm selects a token-Jaccard medoid per released description window,
using all test-phase descriptions and a SHA-256 tie-break; no outcome selects text. The coder arm
retains all JL rows for the same six films. Density and wording are jointly changed, not isolated.
The longer cmiyc cut is included only as a declared diagnostic source mismatch; a fixed five-film
gold sensitivity excludes it. These are placeholder annotation intervals, not admitted video axes.
"""
import argparse
import collections
import csv
import hashlib
import json
from pathlib import Path
import re

FILMS = {"cmiyc_long": 2, "theboyfriend": 4, "theshoe": 5,
         "keithreynolds": 6, "therock": 8, "busstop": 12}
PARTS = {"run-01": 0.0, "run-02": 1490.0}
COLUMNS = ["segment", "part_id", "run", "film", "scene_number", "coarse_start_s", "start_s", "end_s", "description"]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path, delimiter=","):
    with Path(path).open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f, delimiter=delimiter, quoting=csv.QUOTE_NONE if delimiter == "\t" else csv.QUOTE_MINIMAL))


def medoid(texts):
    """Maximize mean token-set Jaccard to all responses, including repeats; SHA breaks ties."""
    if not texts or any(not t.strip() for t in texts):
        raise ValueError("medoid requires nonempty descriptions")
    sets = [set(re.findall(r"\w+", t.lower())) for t in texts]
    if any(not s for s in sets):
        raise ValueError("description contains no word tokens")
    values = [sum(len(a & b) / len(a | b) for b in sets) for a in sets]
    return min(zip(texts, values), key=lambda p: (-p[1], hashlib.sha256(p[0].encode()).hexdigest()))[0]


def write_table(path, rows):
    if not rows:
        raise ValueError("empty source index")
    with Path(path).open("w", newline="", encoding="utf-8") as f:
        # The Scala adapter reads a literal tab-separated replay, not quoted CSV.
        f.write("\t".join(COLUMNS) + "\n")
        for n, row in enumerate(rows, 1):
            row = dict(row, segment=n)
            row["description"] = row["description"].replace("\t", " ").replace("\n", " ").replace("\r", " ").strip()
            f.write("\t".join(str(row[c]) for c in COLUMNS) + "\n")


def prepare(data, output):
    output.mkdir(parents=True, exist_ok=False)
    annotation = data / "filmfestival/derived/annotation-JL.tsv"
    rows = read(annotation, "\t")
    by = collections.defaultdict(list)
    for r in rows:
        by[int(r["film"].split(".")[0])].append(r)
    if set(by) != set(range(1, 13)):
        raise ValueError("expected twelve film blocks including two cartoons")
    inputs = {str(annotation.relative_to(data)): sha(annotation)}
    names = ["jl-all", "jl-no-cartoons", "jl-six-film-identity", "crowd-six-film-identity"]
    arms = {"jl-all": rows,
            "jl-no-cartoons": [r for r in rows if int(r["film"].split(".")[0]) not in (1, 7)],
            "jl-six-film-identity": [], "crowd-six-film-identity": []}
    details = {}
    for slug, ordinal in FILMS.items():
        film_rows = by[ordinal]
        # A film-level locus for classification, shared exactly by coder and crowd candidates.
        lo = min(int(r["start_s"]) for r in film_rows)
        hi = max(int(r["end_s"] or r["start_s"]) for r in film_rows)
        def at_film(text):
            return dict(film_rows[0], scene_number="", coarse_start_s="", start_s=lo, end_s=hi, description=text)
        arms["jl-six-film-identity"].extend(at_film(r["description"]) for r in film_rows)
        clean = data / f"filmfestival/textdata/Cleaned Data/Description_Cleaned_Data/{slug}_description_cleaned.csv"
        mapping = data / f"filmfestival/textdata/Analysis Data/Description/{slug}_consensus_mapped_to_neuro.csv"
        for p in (clean,mapping):
            inputs[str(p.relative_to(data))] = sha(p)
        raw, maps = read(clean), read(mapping)
        groups = collections.defaultdict(list)
        def key(r):
            return float(r["counterbalance"]), float(r["description_stop"])
        for r in raw:
            if r["phase_type"] == "test":
                groups[key(r)].append(r)
        if len({key(r) for r in maps}) != len(maps) or {key(r) for r in maps} != set(groups):
            raise ValueError(f"{slug}: description windows and mapping disagree")
        rejected_bounds = 0
        for m in sorted(maps,key=lambda r:(float(r["offset"]),key(r))):
            rs = groups[key(m)]
            matched = [r for r in rs if (float(r["onset"]),float(r["offset"])) == (float(m["onset"]),float(m["offset"]))]
            rejected_bounds += len(rs) - len(matched)
            rs = matched
            arms["crowd-six-film-identity"].append(at_film(medoid([r["description_content"] for r in rs])))
        details[slug] = {"rawRows":len(raw),"testDescriptions":sum(map(len,groups.values())),
                         "rejectedWindowBounds":rejected_bounds,"crowdWindows":len(maps),"coderCandidates":len(film_rows),
                         "annotationExtent":[lo,hi], "publishedMappedEnd":max(float(m["filmfest_offset"]) for m in maps),
                         "cutMismatch":slug == "cmiyc_long"}
    outputs = {}
    for name in names:
        path = output / f"{name}.tsv"
        write_table(path,arms[name])
        outputs[name] = {"path":str(path),"sha256":sha(path),"rows":len(arms[name])}
    receipt = {"schema":"storymodel4s.filmfestival.development-inputs/v1", "inputs":inputs,"outputs":outputs,
               "films":details,"parts":PARTS,"goldRead":False,
               "independenceEstimand":"film identity; candidate counts and text recipe differ; no window-time claim",
               "sixFilmGoldCodes":[1,3,4,5,6,10],"fiveFilmSensitivityGoldCodes":[3,4,5,6,10]}
    (output / "receipt.json").write_text(json.dumps(receipt,indent=2,sort_keys=True)+"\n")
    print(json.dumps({"outputs":outputs,"films":details},indent=2))


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("data",type=Path)
    p.add_argument("output",type=Path)
    a=p.parse_args()
    prepare(a.data,a.output)
