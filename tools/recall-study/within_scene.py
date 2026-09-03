#!/usr/bin/env python3
"""Within-scene precision for recall-to-video: blind packet, sealed key, scorer, diagnostics.

Implements docs/plans/2026-09-03-within-scene-precision-preregistration.md and nothing else. The
packet shows a recall unit against its gold scene's segments and never the prediction; the
adjudicator labels the unit; the prediction is joined at scoring from any arm's report.

Usage:
  within_scene.py packet   ARM_DIR OUT_DIR [--seed N] [--per-a N] [--per-b N]
  within_scene.py extract  PACKET_MD ANSWERS_TSV          # pull filled answer lines out of a packet
  within_scene.py score    KEY_DIR ANSWERS_TSV LABEL ARM_DIR [--machine MACHINE_TSV]
  within_scene.py diagnose LABEL ARM_DIR                  # gold-free within-scene diagnostics

Data root: $STORYMODEL4S_DATA or <main checkout>/data (tools/data-root.sh). The annotation, the gold
and the partition are read from there; --annotation/--gold/--partition override.
"""
import csv, glob, hashlib, json, os, random, re, statistics, subprocess, sys
from collections import defaultdict

TR = 1.5
SEED = 20260903
EXCLUDED_NUMS = {1}  # NN01: coding runs to 1417 s against a 782 s transcript
NO_GOLD_NUMS = {5}  # NN05: the source the public alias set omits
BOOT_N = 2000
LEAF = re.compile(r"sit:sherlock:row:(\d+)$")
SCENE_ANCHOR = re.compile(r"seg:sherlock:scene:(\d+)$")
ANSWER = re.compile(
    r"^\[u:([A-Z0-9]+):(\d+)\]\s+first:\s*(\S*)\s+last:\s*(\S*)\s+sure:\s*(\S*)\s*note:\s*(.*)$"
)


# ---------------------------------------------------------------- inputs


def data_root():
    env = os.environ.get("STORYMODEL4S_DATA")
    if env:
        return env
    common = subprocess.check_output(
        ["git", "rev-parse", "--git-common-dir"], text=True
    ).strip()
    return os.path.normpath(os.path.join(common, "..", "data"))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gold_subject_of(nn):
    if nn in EXCLUDED_NUMS or nn in NO_GOLD_NUMS:
        return None
    return nn if nn <= 4 else nn - 1


class Annotation:
    """The 1,000 segments, their scenes, and one continuous film clock across the run break."""

    def __init__(self, path):
        self.path = path
        self.seg = {}  # number -> dict(start, end, scene, text, location, cast)
        self.scene_segs = defaultdict(list)  # scene -> [numbers in order]
        self.scene_title = {}
        offset, prev_end, cur = 0.0, 0.0, None
        with open(path, encoding="utf-8", newline="") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                n = int(r["Segment Number"])
                s, e = float(r["Start Time (s) "]), float(r["End Time (s) "])
                if s < prev_end - 0.01:  # the annotation clock resets at the second run
                    offset += prev_end
                prev_end = e
                marker = r["Scene Segments"].strip()
                if marker:
                    cur = int(marker.split(".")[0])
                    self.scene_title[cur] = marker
                self.seg[n] = dict(
                    start=s + offset,
                    end=e + offset,
                    scene=cur,
                    text=r["Scene Details - A Level "].strip(),
                    location=r["Location"].strip(),
                    cast=r["Name - All"].strip(),
                    part_start=s,
                    part_end=e,
                )
                self.scene_segs[cur].append(n)
        self.midpoint = {}
        for sc, segs in self.scene_segs.items():
            lo, hi = self.seg[segs[0]]["start"], self.seg[segs[-1]]["end"]
            mid = (lo + hi) / 2
            self.midpoint[sc] = next(
                (n for n in segs if self.seg[n]["start"] <= mid < self.seg[n]["end"]),
                segs[len(segs) // 2],
            )

    def gap_seconds(self, p, a, b):
        """Seconds between segment p's interval and the interval spanned by segments a..b; 0 if they overlap."""
        ps, pe = self.seg[p]["start"], self.seg[p]["end"]
        rs, re_ = self.seg[a]["start"], self.seg[b]["end"]
        if pe <= rs:
            return rs - pe
        if ps >= re_:
            return ps - re_
        return 0.0


def load_gold(path):
    by = {}
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            by.setdefault(int(r["Subject"]), []).append(
                (float(r["Onset"]) * TR, float(r["Offset"]) * TR, int(r["Scene"]))
            )
    for v in by.values():
        v.sort()
    return by


def scene_at(intervals, t):
    for lo, hi, sc in intervals:
        if lo <= t <= hi:
            return sc
    return None


def load_arm(d):
    """participant name -> list of report rows (dicts), in unit order."""
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "recall-map-*.tsv"))):
        name = os.path.basename(p)[len("recall-map-") : -len(".tsv")]
        with open(p, newline="", encoding="utf-8") as fh:
            out[name] = list(csv.DictReader(fh, delimiter="\t"))
    return out


def short(name):
    return name[:4]


def predicted_scene(row):
    g = (row.get("group") or "").split(".")[0].strip()
    return int(g) if g.isdigit() else None


def leaf_of(anchor):
    m = LEAF.match(anchor or "")
    return int(m.group(1)) if m else None


# ---------------------------------------------------------------- packet

RUBRIC = """\
# Within-scene adjudication packet

You are placing recall units against the film's annotated segments. For each unit below, read the
unit (bold) with its neighbours (quoted, for context only), then look at the listed scene's segments
and fill the answer line in place:

    [u:NN03:017] first: 45 last: 47 sure: y note: optional words

- `first` and `last`: the inclusive range of segment numbers, from the listed scene, that the unit
  describes. A unit that describes the scene as a whole, or a gist you cannot attribute to a
  narrower span, takes the scene's first and last segment number.
- Write `none` for both when the unit describes nothing in the listed scene.
- `sure`: `y` or `n`.
- Leave a unit's line untouched to skip it; skipped units are simply not counted.

Label the unit, not a guess about any system. Nothing in this packet reflects any system's output.
Units are grouped by scene, and the order of scenes and of units within a scene is random.
"""


def cmd_packet(args):
    arm_dir, out_dir = args[0], args[1]
    opts = parse_opts(args[2:])
    seed, per_a, per_b = (
        int(opts.get("--seed", SEED)),
        int(opts.get("--per-a", 15)),
        int(opts.get("--per-b", 5)),
    )
    root = data_root()
    ann_path = opts.get(
        "--annotation",
        os.path.join(root, "sherlock", "Sherlock_Segments_1000_NN_2017.tsv"),
    )
    gold_path = opts.get(
        "--gold", os.path.join(root, "sherlock", "Sherlock_Recall_Scene_n50_Onsets.csv")
    )
    part_path = opts.get(
        "--partition", os.path.join(root, "study", "recall-to-video", "partition.json")
    )
    ann, gold = Annotation(ann_path), load_gold(gold_path)
    development = set(json.load(open(part_path))["development"])
    arm = load_arm(arm_dir)

    frame = {"A": defaultdict(list), "B": defaultdict(list)}
    unit_rows = {}
    for name in sorted(arm):
        if name not in development:
            continue
        gs = gold_subject_of(int(name[2:4]))
        if gs is None or gs not in gold:
            continue
        rows = arm[name]
        for i, r in enumerate(rows):
            if not r.get("recallOnsetSeconds"):
                continue
            g = scene_at(gold[gs], float(r["recallOnsetSeconds"]))
            if g is None:
                continue
            stratum = "A" if predicted_scene(r) == g else "B"
            frame[stratum][name].append((name, int(r["unit"]), g))
            unit_rows[(name, int(r["unit"]))] = (rows, i)

    rng = random.Random(seed)
    sample = []
    for stratum, quota in (("A", per_a), ("B", per_b)):
        for name in sorted(frame[stratum]):
            units = sorted(frame[stratum][name], key=lambda u: u[1])
            pick = rng.sample(units, min(quota, len(units)))
            sample.extend((n, u, g, stratum) for n, u, g in pick)

    by_scene = defaultdict(list)
    for n, u, g, s in sample:
        by_scene[g].append((n, u, g, s))
    scenes = sorted(by_scene)
    rng.shuffle(scenes)
    for sc in scenes:
        rng.shuffle(by_scene[sc])

    os.makedirs(out_dir, exist_ok=True)
    packet_path, key_path, manifest_path = (
        os.path.join(out_dir, f) for f in ("packet.md", "key.tsv", "manifest.json")
    )
    with open(packet_path, "w", encoding="utf-8") as fh:
        fh.write(RUBRIC)
        fh.write(f"\n{len(sample)} units in {len(scenes)} scene groups.\n")
        for sc in scenes:
            segs = ann.scene_segs[sc]
            a, b = ann.seg[segs[0]], ann.seg[segs[-1]]
            fh.write(
                f"\n---\n\n## Scene {ann.scene_title[sc]}  (segments {segs[0]}–{segs[-1]})\n\n"
            )
            for n in segs:
                s = ann.seg[n]
                extra = "; ".join(x for x in (s["location"], s["cast"]) if x)
                fh.write(
                    f"- **{n}** [{s['part_start']:.0f}–{s['part_end']:.0f} s] {s['text']}"
                    + (f"  _({extra})_" if extra else "")
                    + "\n"
                )
            fh.write("\n")
            for name, u, g, s in by_scene[sc]:
                rows, i = unit_rows[(name, u)]
                prev_t = rows[i - 1]["recallText"] if i > 0 else "(start of recall)"
                next_t = (
                    rows[i + 1]["recallText"]
                    if i + 1 < len(rows)
                    else "(end of recall)"
                )
                fh.write(f"### Unit u:{short(name)}:{u:03d}\n\n")
                fh.write(f"> {prev_t}\n\n**{rows[i]['recallText']}**\n\n> {next_t}\n\n")
                fh.write(
                    f"[u:{short(name)}:{u:03d}] first: __ last: __ sure: _ note:\n\n"
                )
    with open(key_path, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t")
        w.writerow(["participant", "nn", "unit", "goldScene", "stratum"])
        for name, u, g, s in sorted(sample):
            w.writerow([name, short(name), u, g, s])
    manifest = {
        "schema": "storymodel4s.study.within-scene-packet",
        "schemaVersion": 1,
        "seed": seed,
        "preregistration": "docs/plans/2026-09-03-within-scene-precision-preregistration.md",
        "arm": os.path.basename(os.path.normpath(arm_dir)),
        "quota": {"A": per_a, "B": per_b},
        "frame": {
            s: {short(n): len(v) for n, v in sorted(frame[s].items())}
            for s in ("A", "B")
        },
        "frameTotal": {s: sum(len(v) for v in frame[s].values()) for s in ("A", "B")},
        "sample": {s: sum(1 for x in sample if x[3] == s) for s in ("A", "B")},
        "sceneGroups": len(scenes),
        "inputs": {
            os.path.basename(p): sha256(p) for p in (ann_path, gold_path, part_path)
        },
        "armReports": {
            short(n): sha256(os.path.join(arm_dir, f"recall-map-{n}.tsv"))
            for n in sorted(arm)
            if n in development
        },
        "outputs": {"packet.md": sha256(packet_path), "key.tsv": sha256(key_path)},
    }
    json.dump(manifest, open(manifest_path, "w"), indent=2, sort_keys=True)
    print(
        f"packet: {packet_path}\nkey:    {key_path}\nframe A {manifest['frameTotal']['A']} B {manifest['frameTotal']['B']}; "
        f"sample A {manifest['sample']['A']} B {manifest['sample']['B']} in {len(scenes)} scene groups"
    )
    for k, v in manifest["outputs"].items():
        print(f"sha256 {k}: {v}")


# ---------------------------------------------------------------- answers


def cmd_extract(args):
    """extract PACKET_MD... ANSWERS_TSV [--strip-notes]: several filled packets (chunks) concatenate.

    --strip-notes writes the content-free form (no free text) that pre-registration rule 6 allows
    under docs/data/sherlock/.
    """
    strip = "--strip-notes" in args
    args = [a for a in args if a != "--strip-notes"]
    packets, out = args[:-1], args[-1]
    rows = [r for p in packets for r in read_answers(p)]
    seen = set()
    for r in rows:
        k = (r["nn"], r["unit"])
        if k in seen:
            sys.exit(f"duplicate answer for {k}")
        seen.add(k)
    cols = ["nn", "unit", "first", "last", "sure"] + ([] if strip else ["note"])
    with open(out, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t")
        w.writerow(cols)
        for r in sorted(rows, key=lambda r: (r["nn"], r["unit"])):
            w.writerow([r[c] for c in cols])
    print(f"{len(rows)} answers from {len(packets)} packet(s) -> {out}")


def read_answers(path):
    """Answer rows from a filled packet (.md) or an answers TSV. Unfilled lines are skipped."""
    rows = []
    if path.endswith(".md"):
        for line in open(path, encoding="utf-8"):
            m = ANSWER.match(line.rstrip("\n"))
            if not m:
                continue
            nn, unit, first, last, sure, note = m.groups()
            if first in ("", "__") or last in ("", "__"):
                continue
            rows.append(
                dict(
                    nn=nn,
                    unit=int(unit),
                    first=first,
                    last=last,
                    sure=sure,
                    note=note.strip(),
                )
            )
    else:
        with open(path, newline="", encoding="utf-8") as fh:
            for r in csv.DictReader(fh, delimiter="\t"):
                if not r["first"] or r["first"] == "__":
                    continue
                rows.append(
                    dict(
                        nn=r["nn"],
                        unit=int(r["unit"]),
                        first=r["first"],
                        last=r["last"],
                        sure=r.get("sure", ""),
                        note=r.get("note", ""),
                    )
                )
    return rows


def normalise(rows, ann, key):
    """Validate answers against the key and the annotation; return {(nn, unit): (a, b) or None}."""
    out, problems = {}, []
    for r in rows:
        k = (r["nn"], r["unit"])
        if k not in key:
            problems.append(f"{k}: not in key")
            continue
        if r["first"].lower() == "none":
            out[k] = None
            continue
        try:
            a, b = int(r["first"]), int(r["last"])
        except ValueError:
            problems.append(f"{k}: unreadable range {r['first']}..{r['last']}")
            continue
        if a > b:
            a, b = b, a
        segs = ann.scene_segs[key[k]["goldScene"]]
        if a not in segs or b not in segs:
            problems.append(
                f"{k}: range {a}..{b} outside gold scene {key[k]['goldScene']} ({segs[0]}..{segs[-1]})"
            )
            continue
        out[k] = (a, b)
    for p in problems:
        print("   problem:", p)
    return out


def grain_of(rng, segs):
    if rng is None:
        return "none"
    a, b = rng
    if a == segs[0] and b == segs[-1]:
        return "whole"
    return "point" if b - a + 1 <= 2 else "span"


# ---------------------------------------------------------------- scoring


def cluster_boot(per_part, fn, seed=SEED, n=BOOT_N):
    names = sorted(per_part)
    if len(names) < 2:
        return (None, None)
    rng = random.Random(seed)
    vals = []
    for _ in range(n):
        pooled = [
            x
            for k in (names[rng.randrange(len(names))] for _ in names)
            for x in per_part[k]
        ]
        vals.append(fn(pooled))
    vals.sort()
    return vals[int(0.025 * n)], vals[int(0.975 * n) - 1]


def mean(v):
    return sum(v) / len(v) if v else float("nan")


def pct(v):
    return 100.0 * mean(v)


def wquantile(pairs, q):
    """Weighted quantile of (value, weight) pairs."""
    pairs = sorted(pairs)
    total = sum(w for _, w in pairs)
    acc = 0.0
    for v, w in pairs:
        acc += w
        if acc >= q * total:
            return v
    return pairs[-1][0] if pairs else float("nan")


def ci_str(lo, hi, fmt="{:+.1f}"):
    if lo is None:
        return "CI n/a"
    excl = "excludes zero" if (lo > 0 or hi < 0) else "includes zero"
    return f"95% CI [{fmt.format(lo)}, {fmt.format(hi)}] {excl}"


def load_key(key_dir):
    key = {}
    with open(os.path.join(key_dir, "key.tsv"), newline="", encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            key[(r["nn"], int(r["unit"]))] = dict(
                participant=r["participant"],
                goldScene=int(r["goldScene"]),
                stratum=r["stratum"],
            )
    return key


def join(key, answers, ann, arm):
    """Per adjudicated unit: everything the outcomes need, from the key, the answer, and the arm."""
    units = []
    for (nn, u), rng in answers.items():
        k = key[(nn, u)]
        rows = arm.get(k["participant"])
        if rows is None:
            continue
        r = next((x for x in rows if int(x["unit"]) == u), None)
        if r is None:
            continue
        g = k["goldScene"]
        segs = ann.scene_segs[g]
        pred_scene = predicted_scene(r)
        units.append(
            dict(
                nn=nn,
                unit=u,
                gold=g,
                stratum=k["stratum"],
                range=rng,
                grain=grain_of(rng, segs),
                leaf=leaf_of(r["mapAnchor"]),
                runner=leaf_of(r.get("runnerUpAnchor")),
                mass=float(r["mapAnchorMass"])
                if r.get("mapAnchorMass")
                else float("nan"),
                pred_scene=pred_scene,
                scene_correct=(pred_scene == g),
                mid=ann.midpoint[g],
                scene_len=len(segs),
                pred_mid=ann.midpoint[pred_scene]
                if pred_scene in ann.midpoint
                else None,
            )
        )
    return units


def hit(p, rng, tol=0):
    if p is None or rng is None:
        return False
    a, b = rng
    return a - tol <= p <= b + tol


def score_lane(label, units, ann, arm_label, frame_total=None):
    per = lambda sel: {
        k: [x for x in sel if x["nn"] == k] for k in sorted({x["nn"] for x in sel})
    }
    print(f"\n=== {label} lane: {arm_label} ===")
    print(
        f"   adjudicated units {len(units)}  participants {len({x['nn'] for x in units})}  "
        f"stratum A {sum(1 for x in units if x['stratum']=='A')}  B {sum(1 for x in units if x['stratum']=='B')}"
    )

    # 5. grain distribution
    for s in ("A", "B"):
        sel = [x for x in units if x["stratum"] == s]
        if sel:
            c = {
                gname: sum(1 for x in sel if x["grain"] == gname)
                for gname in ("point", "span", "whole", "none")
            }
            print(
                f"   grain, stratum {s}: "
                + "  ".join(f"{k} {v} ({100*v/len(sel):.0f}%)" for k, v in c.items())
            )

    # coverage: none
    covered = [x for x in units if x["range"] is not None]

    # 1–4, 7, 9: primary set
    prim = [
        x
        for x in covered
        if x["stratum"] == "A"
        and x["scene_correct"]
        and x["leaf"] is not None
        and x["grain"] in ("point", "span")
    ]
    print(
        f"\n   primary set (A, scene-correct under this arm, leaf-anchored, point/span): {len(prim)} units"
    )
    if prim:
        pp = per(prim)
        model = {
            k: [1.0 if hit(x["leaf"], x["range"]) else 0.0 for x in v]
            for k, v in pp.items()
        }
        null = {
            k: [1.0 if hit(x["mid"], x["range"]) else 0.0 for x in v]
            for k, v in pp.items()
        }
        unif = {
            k: [(x["range"][1] - x["range"][0] + 1) / x["scene_len"] for x in v]
            for k, v in pp.items()
        }
        diff = {k: [a - b for a, b in zip(model[k], null[k])] for k in pp}
        lo, hi = cluster_boot(model, pct)
        print(
            f"   hit            model {pct(sum(model.values(), [])):5.1f}%  {ci_str(lo, hi, '{:.1f}')}"
        )
        print(
            f"   hit            scene-midpoint null {pct(sum(null.values(), [])):5.1f}%   uniform null {pct(sum(unif.values(), [])):5.1f}%"
        )
        lo, hi = cluster_boot(diff, pct)
        better = sum(1 for k in pp if mean(model[k]) > mean(null[k]))
        print(
            f"   PRIMARY  model minus midpoint null {pct(sum(diff.values(), [])):+5.1f} points  {ci_str(lo, hi)}  "
            f"participants improved {better}/{len(pp)}"
        )
        for tol in (1, 2):
            m = pct([1.0 if hit(x["leaf"], x["range"], tol) else 0.0 for x in prim])
            n = pct([1.0 if hit(x["mid"], x["range"], tol) else 0.0 for x in prim])
            print(f"   hit ±{tol}          model {m:5.1f}%   midpoint null {n:5.1f}%")
        gm = [ann.gap_seconds(x["leaf"], *x["range"]) for x in prim]
        gn = [ann.gap_seconds(x["mid"], *x["range"]) for x in prim]
        gu = [
            mean([ann.gap_seconds(q, *x["range"]) for q in ann.scene_segs[x["gold"]]])
            for x in prim
        ]
        print(
            f"   time gap s     model median {statistics.median(gm):5.1f}  p75 {sorted(gm)[int(0.75*len(gm))]:5.1f}   "
            f"midpoint null median {statistics.median(gn):5.1f}  p75 {sorted(gn)[int(0.75*len(gn))]:5.1f}   uniform mean {mean(gu):5.1f}"
        )
        misses = [x for x in prim if not hit(x["leaf"], x["range"])]
        resc = sum(1 for x in misses if hit(x["runner"], x["range"]))
        print(
            f"   runner-up rescue: {resc}/{len(misses)} misses"
            if misses
            else "   runner-up rescue: no misses"
        )
        qs = sorted(x["mass"] for x in prim)
        cuts = [qs[int(len(qs) * f)] for f in (0.25, 0.5, 0.75)]
        for qi in range(4):
            lo_c = cuts[qi - 1] if qi > 0 else -1
            hi_c = cuts[qi] if qi < 3 else 2
            sel = (
                [x for x in prim if lo_c <= x["mass"] < hi_c]
                if qi < 3
                else [x for x in prim if x["mass"] >= lo_c]
            )
            if sel:
                print(
                    f"   confidence Q{qi+1}: hit {pct([1.0 if hit(x['leaf'], x['range']) else 0.0 for x in sel]):5.1f}%  n {len(sel)}"
                )

    # 6. abstention concordance
    a_units = [x for x in covered if x["stratum"] == "A" and x["scene_correct"]]
    leaf_u = [x for x in a_units if x["leaf"] is not None]
    abst = [x for x in a_units if x["leaf"] is None]
    if a_units:
        w_leaf = (
            pct([1.0 if x["grain"] == "whole" else 0.0 for x in leaf_u])
            if leaf_u
            else float("nan")
        )
        w_abst = (
            pct([1.0 if x["grain"] == "whole" else 0.0 for x in abst])
            if abst
            else float("nan")
        )
        print(
            f"\n   abstention concordance: whole-scene share among scene-anchored {w_abst:5.1f}% (n {len(abst)})  "
            f"among leaf-anchored {w_leaf:5.1f}% (n {len(leaf_u)})"
        )

    # 8. temporal error over all units, stratum-weighted
    if frame_total:
        n_s = {s: sum(1 for x in covered if x["stratum"] == s) for s in ("A", "B")}
        wt = {s: (frame_total[s] / n_s[s]) if n_s[s] else 0.0 for s in ("A", "B")}
        pairs_m, pairs_n = [], []
        for x in covered:
            p = (
                x["leaf"]
                if x["leaf"] is not None
                else (x["pred_mid"] if x["pred_mid"] else None)
            )
            if p is None or x["pred_mid"] is None:
                continue
            pairs_m.append((ann.gap_seconds(p, *x["range"]), wt[x["stratum"]]))
            pairs_n.append(
                (ann.gap_seconds(x["pred_mid"], *x["range"]), wt[x["stratum"]])
            )
        if pairs_m:
            print(
                f"   temporal error, all units (weights A {wt['A']:.2f} B {wt['B']:.2f}): model median {wquantile(pairs_m, .5):5.1f} s  "
                f"p75 {wquantile(pairs_m, .75):5.1f} s   predicted-scene midpoint median {wquantile(pairs_n, .5):5.1f} s  p75 {wquantile(pairs_n, .75):5.1f} s"
            )
    none_n = sum(1 for x in units if x["range"] is None)
    print(f"   coverage: {none_n} unit(s) adjudicated as none, excluded above")
    return units


def reliability(human, machine, ann):
    hk = {(x["nn"], x["unit"]): x for x in human}
    mk = {(x["nn"], x["unit"]): x for x in machine}
    shared = sorted(set(hk) & set(mk))
    if not shared:
        print("\n=== reliability: no shared units ===")
        return
    jac, grain_agree, hit_agree = [], 0, 0
    for k in shared:
        h, m = hk[k], mk[k]
        if h["range"] is None or m["range"] is None:
            jac.append(1.0 if h["range"] is None and m["range"] is None else 0.0)
        else:
            a = set(range(h["range"][0], h["range"][1] + 1))
            b = set(range(m["range"][0], m["range"][1] + 1))
            jac.append(len(a & b) / len(a | b))
        grain_agree += h["grain"] == m["grain"]
        hit_agree += hit(h["leaf"], h["range"]) == hit(m["leaf"], m["range"])
    med = statistics.median(jac)
    print(f"\n=== reliability, human vs machine, {len(shared)} shared units ===")
    print(
        f"   range Jaccard median {med:.2f}  mean {mean(jac):.2f}   grain agreement {100*grain_agree/len(shared):.1f}%   "
        f"hit-decision agreement {100*hit_agree/len(shared):.1f}%"
    )
    print(
        "   machine lane "
        + (
            "PASSES the 0.5 median-Jaccard bar"
            if med >= 0.5
            else "FAILS the 0.5 median-Jaccard bar: its estimates are not to be quoted"
        )
    )


def cmd_score(args):
    key_dir, answers_path, arm_label, arm_dir = args[0], args[1], args[2], args[3]
    opts = parse_opts(args[4:])
    root = data_root()
    ann = Annotation(
        opts.get(
            "--annotation",
            os.path.join(root, "sherlock", "Sherlock_Segments_1000_NN_2017.tsv"),
        )
    )
    key = load_key(key_dir)
    manifest = json.load(open(os.path.join(key_dir, "manifest.json")))
    arm = load_arm(arm_dir)
    print(
        f"key {key_dir}  packet sha256 {manifest['outputs']['packet.md'][:16]}…  arm {arm_label}"
    )
    human = join(key, normalise(read_answers(answers_path), ann, key), ann, arm)
    score_lane("human", human, ann, arm_label, manifest["frameTotal"])
    if "--machine" in opts:
        machine = join(
            key, normalise(read_answers(opts["--machine"]), ann, key), ann, arm
        )
        score_lane(
            "machine (diagnostic, never gold)",
            machine,
            ann,
            arm_label,
            manifest["frameTotal"],
        )
        if human:
            reliability(human, machine, ann)


# ---------------------------------------------------------------- diagnostics (gold-free)


def cmd_diagnose(args):
    label, arm_dir = args[0], args[1]
    opts = parse_opts(args[2:])
    root = data_root()
    ann = Annotation(
        opts.get(
            "--annotation",
            os.path.join(root, "sherlock", "Sherlock_Segments_1000_NN_2017.tsv"),
        )
    )
    arm = load_arm(arm_dir)
    tot = leaf = first = last = pairs = fwd = tie = 0
    rel = []
    for name, rows in arm.items():
        prev = None
        for r in rows:
            if not r.get("recallOnsetSeconds"):
                continue
            tot += 1
            p, sc = leaf_of(r["mapAnchor"]), predicted_scene(r)
            if p is None or sc is None:
                prev = None
                continue
            leaf += 1
            segs = ann.scene_segs[sc]
            i = segs.index(p)
            rel.append(i / max(1, len(segs) - 1))
            first += i == 0
            last += i == len(segs) - 1
            if prev and prev[0] == sc:
                pairs += 1
                fwd += p > prev[1]
                tie += p == prev[1]
            prev = (sc, p)
    print(
        f"=== within-scene diagnostics (gold-free): {label}, {len(arm)} participants ==="
    )
    print(
        f"   units {tot}; leaf-anchored {leaf} ({100*leaf/tot:.1f}%); at scene's first segment {100*first/leaf:.1f}%, last {100*last/leaf:.1f}%"
    )
    hist = [0] * 10
    for x in rel:
        hist[min(9, int(x * 10))] += 1
    print(f"   relative position within predicted scene, deciles: {hist}")
    back = pairs - fwd - tie
    print(
        f"   consecutive same-scene leaf pairs {pairs}: forward {100*fwd/pairs:.1f}%  tie {100*tie/pairs:.1f}%  backward {100*back/pairs:.1f}%"
        f"   (a within-scene order signal; chance is symmetric)"
        if pairs
        else "   no consecutive same-scene leaf pairs"
    )


# ---------------------------------------------------------------- main


def parse_opts(argv):
    opts, i = {}, 0
    while i < len(argv):
        if argv[i].startswith("--") and i + 1 < len(argv):
            opts[argv[i]] = argv[i + 1]
            i += 2
        else:
            i += 1
    return opts


def main(argv):
    if len(argv) < 2 or argv[1] not in ("packet", "extract", "score", "diagnose"):
        print(__doc__)
        sys.exit(2)
    {
        "packet": cmd_packet,
        "extract": cmd_extract,
        "score": cmd_score,
        "diagnose": cmd_diagnose,
    }[argv[1]](argv[2:])


if __name__ == "__main__":
    main(sys.argv)
