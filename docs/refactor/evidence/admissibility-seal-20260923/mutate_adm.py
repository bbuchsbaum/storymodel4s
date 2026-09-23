import re, subprocess, sys, pathlib
repo = pathlib.Path(sys.argv[1]); log = pathlib.Path(sys.argv[2])
cost = repo / "align/src/main/scala/storymodel4s/align/cost.scala"
src = repo / "align/src/main/scala/storymodel4s/align/source.scala"
M = [
  ("M1 Admissibility back to a case class", cost, "final class Admissibility private (val contradictions", "final case class Admissibility private (contradictions", True),
  ("M2 StructuralCoverage back to a case class", src, "final class StructuralCoverage private (", "final case class StructuralCoverage private (", True),
  ("M3 factory guard removed", src, "if membersWithEvidence >= 0 && membersWithEvidence <= members then", "if true then", False),
  ("M4 counted miscounts evidence", src, "new StructuralCoverage(level, evidenced.count(identity), evidenced.size)", "new StructuralCoverage(level, evidenced.size, evidenced.size)", False),
  ("M5 faithful hard-wired", cost, "def faithful: Boolean = contradictions.isEmpty", "def faithful: Boolean = true", False),
  ("M6 distortion dropped", cost, "NonEmptySet.fromSet(scala.collection.immutable.SortedSet.from(contradictions.map(_.facet)))", "None", False),
  ("M7 dedup removed", cost, "new Admissibility(contradictions.distinct)", "new Admissibility(contradictions)", False),
]
sbt = ["sbt", "-batch", "-Dstorymodel4s.grakern.build=" + str(pathlib.Path.home() / "code/scala/grakern")]
tests = "alignJVM/testOnly storymodel4s.probes.AdmissibilityUnforgeableSuite storymodel4s.align.GateProofSuite storymodel4s.align.EvidenceSuite storymodel4s.align.WireSuite storymodel4s.align.StructuralReductionSuite; codecJVM/testOnly storymodel4s.codec.AlignCodecSuite storymodel4s.codec.MappingCodecDigestSuite"
out = []
def run(clean):
    cmds = (["alignJVM/clean", "codecJVM/clean"] if clean else []) + [tests]
    r = subprocess.run(sbt + cmds, cwd=repo, capture_output=True, text=True)
    t = r.stdout + r.stderr
    failed = sorted(set(re.sub(r"\x1b\[[0-9;]*m", "", l).split("==> X",1)[-1].strip()[:120] for l in t.splitlines() if "==> X" in l))
    totals = re.findall(r"(?:Passed|Failed): Total \d+[^\n]*", t)
    return r.returncode, "Compilation failed" in t or "[error] -- " in t and not totals, totals, failed
for name, f, old, new, clean in M:
    orig = f.read_text()
    assert orig.count(old) == 1, (name, orig.count(old))
    f.write_text(orig.replace(old, new))
    try:
        rc, cerr, totals, failed = run(clean)
    finally:
        f.write_text(orig)
    out.append(f"{name}: exit={rc} compile_error={cerr} clean_recompile={clean} totals={totals} failed={failed}")
    log.write_text("\n".join(out) + "\n")
rc, cerr, totals, failed = run(True)
out.append(f"BASELINE restored (clean): exit={rc} totals={totals} failed={failed}")
log.write_text("\n".join(out) + "\n")
