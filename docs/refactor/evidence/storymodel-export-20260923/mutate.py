import re, subprocess, sys, pathlib
repo = pathlib.Path(sys.argv[1]); log = pathlib.Path(sys.argv[2])
src = repo / "codec/src/main/scala/storymodel4s/codec/storymodelexport.scala"
orig = src.read_text()
names = re.findall(r'Loss\(\s*"([a-z-]+)"', orig)
assert len(names) == 17, names
def delete(text, name):
    start = re.search(r'\n\s*Loss\(\s*"' + re.escape(name) + '"', text).start()
    i = text.index("Loss(", start) + 4; depth = 0
    while True:
        c = text[i]
        depth += c == "("; depth -= c == ")"
        i += 1
        if depth == 0: break
    if text[i] == ",": i += 1
    return text[:start] + text[i:]
sbt = ["sbt", "--client", "-Dstorymodel4s.grakern.build=" + str(pathlib.Path.home() / "code/scala/grakern")]
out = []
try:
    for name in names:
        src.write_text(delete(orig, name))
        r = subprocess.run(sbt + ["pipeline/testOnly storymodel4s.pipeline.StoryModelExportSuite"], cwd=repo, capture_output=True, text=True)
        text = r.stdout + r.stderr
        failed = sorted(set(l.split("StoryModelExportSuite.",1)[-1].strip() for l in text.splitlines() if "==> X" in l))
        totals = re.findall(r"Passed: Total \d+.*|Failed: Total \d+.*", text)
        compile_err = "Compilation failed" in text
        out.append(f"MUTANT delete {name}: exit={r.returncode} compile_error={compile_err} totals={totals} failed={failed}")
        log.write_text("\n".join(out) + "\n")
finally:
    src.write_text(orig)
    subprocess.run(sbt + ["pipeline/testOnly storymodel4s.pipeline.StoryModelExportSuite"], cwd=repo, capture_output=True, text=True)
    out.append("RESTORED")
    log.write_text("\n".join(out) + "\n")
