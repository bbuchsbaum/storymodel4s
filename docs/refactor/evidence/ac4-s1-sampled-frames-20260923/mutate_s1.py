"""Named single-defect mutants for AC4-S1; each must compile and fail exactly its named test.

Run from the clone root: python3 ../ac4-s1-mutate.py OUTDIR
"""
import hashlib, json, os, pathlib, subprocess, sys

ROOT = pathlib.Path.cwd()
FRAMES = ROOT / "media/src/main/scala/storymodel4s/media/frames.scala"
CAPTION = ROOT / "media/src/main/scala/storymodel4s/media/caption.scala"
SUITE = "storymodel4s.probe.SampledCaptionConsumerSuite"
GRAKERN = os.path.expanduser("~/code/scala/grakern")

CASES = [
    (
        "sample-index-as-presentation-ordinal",
        CAPTION,
        [
            (
                "              .map(v :+ _)\n",
                "              .map(_ => v :+ o)\n",
            ),
        ],
        "sampled extents close on their own samples",
    ),
    (
        "membership-unchecked",
        FRAMES,
        [
            (
                "selected.find(_ >= index.entries.size) match",
                "selected.find(_ => false) match",
            )
        ],
        "a selected ordinal outside the packet index refuses",
    ),
    (
        "full-decode-byte-rule",
        FRAMES,
        [
            (
                "expected = geometry.frameBytes * selected.size.toLong",
                "expected = geometry.frameBytes * index.entries.size.toLong",
            )
        ],
        "bytes must be exactly one frame per selected sample",
    ),
    (
        "selection-order-unchecked",
        FRAMES,
        [
            (
                "selected.iterator.sliding(2).forall(w => w.size < 2 || w(0) < w(1))",
                "true",
            )
        ],
        "a selection that is empty, repeated or out of order refuses",
    ),
    (
        "edition-unchecked",
        CAPTION,
        [("if edition == expected then Right(())", "if true then Right(())")],
        "the join refuses another edition",
    ),
    (
        "unsampled-request-unchecked",
        CAPTION,
        [
            (
                "request.extents.find(_.ordinals.exists(o => frames.presentationOrdinal(o).isEmpty)) match",
                "request.extents.find(_ => false) match",
            )
        ],
        "a caption request cannot name a frame that was not sampled",
    ),
    (
        "extent-shape-unchecked",
        CAPTION,
        [
            (
                "else if request.extents.exists(e => CaptionExtent.of(e.id, e.ordinals).isLeft) ||",
                "else if false && request.extents.exists(e => CaptionExtent.of(e.id, e.ordinals).isLeft) ||",
            ),
            (
                "request.extents.map(_.id).distinct.size != request.extents.size\n    then",
                "false\n    then",
            ),
        ],
        "a forged extent that is out of order, empty or repeated",
    ),
    (
        "selection-not-in-identity",
        FRAMES,
        [
            (
                'selection = Checksum.ofText(selected.mkString(","))',
                'selection = Checksum.ofText("any selection")',
            )
        ],
        "the selection is bound into the frame set",
    ),
    (
        "sampled-ordinal-is-sample-index",
        FRAMES,
        [
            (
                "def presentationOrdinal(sampleIndex: Int): Option[Int] = selected.lift(sampleIndex)",
                "def presentationOrdinal(sampleIndex: Int): Option[Int] =\n    Option.when(sampleIndex >= 0 && sampleIndex < count)(sampleIndex)",
            )
        ],
        "a sampled set keeps presentation ordinal and sample index apart",
    ),
]


def sbt(test_filter, log):
    cmd = [
        "sbt",
        "-batch",
        f"-Dstorymodel4s.grakern.build={GRAKERN}",
        "set ThisBuild / tlFatalWarnings := true",
        "media/testOnly " + SUITE + " -- *" + test_filter.replace(" ", "*").replace("'", "*") + "*"
        if test_filter
        else f"media/testOnly {SUITE}",
    ]
    r = subprocess.run(cmd, capture_output=True, text=True)
    out = r.stdout + r.stderr + f"\nEXIT={r.returncode}\n"
    log.write_text(out)
    return r.returncode, out


def main():
    out = pathlib.Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=False)
    originals = {p: p.read_text() for p in (FRAMES, CAPTION)}
    rows = []
    rc, text = sbt(None, out / "control-before.log")
    if rc != 0 or "Failed: Total" in text and "Failed 0" not in text:
        raise SystemExit("restored control failed before mutation")
    try:
        for name, path, edits, test in CASES:
            src = originals[path]
            for old, new in edits:
                if src.count(old) != 1:
                    raise SystemExit(f"anchor not unique for {name}: {old}")
                src = src.replace(old, new)
            path.write_text(src)
            rc, text = sbt(test, out / f"{name}.log")
            path.write_text(originals[path])
            compiled = "Compilation failed" not in text
            one_failed = "Failed: Total 1, Failed 1" in text
            named = ("==> X" in text) and ("Ran 1" in text or "1 total" in text)
            if not (rc != 0 and compiled and one_failed and named):
                raise SystemExit(
                    f"mutant {name} not killed by its named test (rc={rc}, compiled={compiled}, one_failed={one_failed})"
                )
            rows.append(
                {
                    "name": name,
                    "file": str(path.relative_to(ROOT)),
                    "test": test,
                    "compiled": True,
                    "exit": rc,
                    "failedTotal": 1,
                    "logSha256": hashlib.sha256(text.encode()).hexdigest(),
                }
            )
            print("killed", name, flush=True)
    finally:
        for p, t in originals.items():
            p.write_text(t)
    rc, text = sbt(None, out / "control-after.log")
    if rc != 0:
        raise SystemExit("restored control failed after mutation")
    receipt = {
        "framesSha256": hashlib.sha256(originals[FRAMES].encode()).hexdigest(),
        "captionSha256": hashlib.sha256(originals[CAPTION].encode()).hexdigest(),
        "suite": SUITE,
        "controlBefore": "pass",
        "controlAfter": "pass",
        "mutations": rows,
    }
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"killed": len(rows), "of": len(CASES)}))


if __name__ == "__main__":
    main()
