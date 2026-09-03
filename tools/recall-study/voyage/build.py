#!/usr/bin/env python3
"""Build the Sherlock Recall Voyage page from the study record.

The page is a single self-contained HTML file: `template.html` with one JSON document embedded,
assembled here from three arms' reports, the scene gold, the annotation and the within-scene
machine lane. It carries recall prose, so it is written into the study record, never into Git.

Usage: build.py [OUT_HTML]   (default: <data>/study/recall-to-video/voyage/sherlock-recall-voyage.html)
"""
import json, os, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, ".."))
import within_scene as ws  # noqa: E402

ARMS = {"all17-monofill": None, "all17-monotone": "mono"}
POSTERIOR_ARM = (
    "all17-monofill-posterior"  # same reports, plus the per-unit posterior sidecar
)
TOP_ANCHORS = 8


def anchor_of(a):
    m = ws.LEAF.match(a or "")
    if m:
        return dict(kind="segment", n=int(m.group(1)))
    m = ws.SCENE_ANCHOR.match(a or "")
    if m:
        return dict(kind="scene", n=int(m.group(1)))
    return dict(kind="none")


def fnum(s):
    return float(s) if s not in (None, "") else None


def assemble(data_root):
    study = os.path.join(data_root, "study", "recall-to-video")
    ann = ws.Annotation(
        os.path.join(data_root, "sherlock", "Sherlock_Segments_1000_NN_2017.tsv")
    )
    gold = ws.load_gold(
        os.path.join(data_root, "sherlock", "Sherlock_Recall_Scene_n50_Onsets.csv")
    )
    part = json.load(open(os.path.join(study, "partition.json")))
    dev = set(part["development"])
    segments = [
        dict(
            n=n,
            start=s["start"],
            end=s["end"],
            scene=s["scene"],
            text=s["text"],
            loc=s["location"],
            cast=s["cast"],
        )
        for n, s in sorted(ann.seg.items())
    ]
    scenes = [
        dict(
            n=sc,
            title=ann.scene_title[sc],
            first=segs[0],
            last=segs[-1],
            start=ann.seg[segs[0]]["start"],
            end=ann.seg[segs[-1]]["end"],
            mid=ann.midpoint[sc],
        )
        for sc, segs in sorted(ann.scene_segs.items())
    ]
    ws_dir = os.path.join(study, "within-scene")
    machine, key, packet_sha = {}, {}, None
    machine_path = os.path.join(ws_dir, "answers-machine-lane.tsv")
    if os.path.exists(machine_path):
        for r in ws.read_answers(machine_path):
            machine[(r["nn"], r["unit"])] = dict(
                first=r["first"], last=r["last"], sure=r["sure"]
            )
        key = ws.load_key(ws_dir)
        packet_sha = json.load(open(os.path.join(ws_dir, "manifest.json")))["outputs"][
            "packet.md"
        ]
    arms = {arm: ws.load_arm(os.path.join(study, arm)) for arm in ARMS}
    participants = []
    for name in sorted(arms["all17-monofill"]):
        nn = int(name[2:4])
        gs = ws.gold_subject_of(nn)
        # The posterior sidecar: the decoded anchor's own mass, the argmax, every admitted anchor.
        side_path = os.path.join(
            study, POSTERIOR_ARM, f"recall-map-{name}.tsv.posterior.json"
        )
        sidecar = json.load(open(side_path))["units"]
        assert len(sidecar) == len(arms["all17-monofill"][name]), name
        units = []
        for i, r in enumerate(arms["all17-monofill"][name]):
            u = int(r["unit"])
            post = sidecar[i]
            assert post["unit"] == u, (name, u)
            on = fnum(r["recallOnsetSeconds"])
            g = ws.scene_at(gold[gs], on) if (gs in gold and on is not None) else None
            decoded, argmax = post["decoded"], post["argmax"]
            by_ref = {a["ref"]: a for a in post["anchors"]}
            origin = None
            if decoded:
                origin = (
                    "argmax"
                    if argmax and decoded["ref"] == argmax["ref"]
                    else ("filled" if decoded["mass"] == 0.0 else "bound")
                )
            anchors = [
                dict(
                    anchor=anchor_of(a["ref"]),
                    scene=a["scene"],
                    level=a["level"],
                    mass=a["mass"],
                )
                for a in post["anchors"][:TOP_ANCHORS]
            ]
            rec = dict(
                u=u,
                fn=r["function"],
                text=r["recallText"],
                t0=on,
                t1=fnum(r["recallLastWordOnsetSeconds"]),
                anchor=anchor_of(r["mapAnchor"]),
                mode=r["mapMode"],
                src=post["sourceMass"],
                ext=post["externalMass"],
                mass=decoded["mass"] if decoded else None,
                origin=origin,
                loc=post["localizability"],
                scene=ws.predicted_scene(r),
                gold=g,
                raw=dict(
                    anchor=anchor_of(argmax["ref"]) if argmax else dict(kind="none"),
                    scene=by_ref[argmax["ref"]]["scene"] if argmax else None,
                    mass=argmax["mass"] if argmax else None,
                ),
                anchors=anchors,
            )
            for arm, short_name in ARMS.items():
                if short_name is None:
                    continue
                rr = arms[arm][name][i]
                assert int(rr["unit"]) == u, (arm, name, u)
                # The posterior is the same channel in every arm; only the decode differs, so an
                # arm's anchor takes its own mass from the sidecar, zero when the decode looked
                # outside the posterior.
                arm_anchor = rr["mapAnchor"]
                rec[short_name] = dict(
                    anchor=anchor_of(arm_anchor),
                    scene=ws.predicted_scene(rr),
                    mass=by_ref[arm_anchor]["mass"] if arm_anchor in by_ref else 0.0,
                )
            k = (name[:4], u)
            if k in machine:
                rec["adj"] = dict(machine[k], stratum=key[k]["stratum"])
            units.append(rec)
        with_gold = [x for x in units if x["gold"] is not None]
        acc = None
        if with_gold:
            acc = dict(
                n=len(with_gold),
                exact=sum(1 for x in with_gold if x["scene"] == x["gold"]),
                within1=sum(
                    1
                    for x in with_gold
                    if x["scene"] is not None and abs(x["scene"] - x["gold"]) <= 1
                ),
            )
        participants.append(
            dict(
                id=name[:4],
                name=name,
                partition="development" if name in dev else "untouched",
                goldSubject=gs,
                excluded=(nn in ws.EXCLUDED_NUMS),
                units=units,
                goldIntervals=[dict(t0=lo, t1=hi, scene=sc) for lo, hi, sc in gold[gs]]
                if gs in gold
                else [],
                accuracy=acc,
                duration=max((x["t1"] or x["t0"] or 0) for x in units),
            )
        )
    return dict(
        generated=__import__("datetime").date.today().isoformat(),
        arm="all17-monofill (default configuration)",
        compare=[a for a, s in ARMS.items() if s],
        partAEnd=1426.0,
        filmEnd=segments[-1]["end"],
        segments=segments,
        scenes=scenes,
        participants=participants,
        machineLane=dict(
            status="diagnostic, never gold", packetSha256=packet_sha or ""
        ),
    )


def main(argv):
    root = ws.data_root()
    out = (
        argv[1]
        if len(argv) > 1
        else os.path.join(
            root, "study", "recall-to-video", "voyage", "sherlock-recall-voyage.html"
        )
    )
    data = json.dumps(assemble(root), separators=(",", ":")).replace(
        "</script", "<\\/script"
    )
    tpl = open(os.path.join(here, "template.html"), encoding="utf-8").read()
    if "/*__DATA__*/" not in tpl:
        sys.exit("template.html has no /*__DATA__*/ slot")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fh:
        fh.write(tpl.replace("/*__DATA__*/", data))
    print(f"{out}  {os.path.getsize(out)} bytes")


if __name__ == "__main__":
    main(sys.argv)
