"""Read-only: plan every real Sherlock scene with v2 and compare offsets to the frozen v1 manifest.

Reads the annotation TSV and the frozen scene-manifest.json under the data root; extracts no frames
and writes nothing there. Run from the repository root.
"""
import hashlib, importlib.util, json, os, sys

spec = importlib.util.spec_from_file_location("esf", "tools/recall-study/extract_scene_frames.py")
esf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(esf)
manifest_path = os.path.join(esf.DATA, "study/recall-to-video/scene-frames/scene-manifest.json")
raw = open(manifest_path, "rb").read()
v1 = json.loads(raw)
order, sc = esf.scenes()
per = v1["framesPerScene"]
rows, identical, multi, short = [], 0, 0, []
assert len(order) == len(v1["scenes"])
for s, label in zip(v1["scenes"], order):
    assert s["label"] == label, (s["label"], label)
    plan = esf.plan_scene(sc[label], per)
    multi += len(plan) > 1
    a, b = s["startSeconds"], s["endSeconds"]
    old = [a + max(b - a, 0.5) * (k + 0.5) / per for k in range(per)]
    same = len(plan) == 1 and plan[0]["part"] == s["part"] and plan[0]["offsets"] == old
    identical += same
    if b - a < 0.5:
        short.append(label)
print(json.dumps({
    "frozenManifestSha256": hashlib.sha256(raw).hexdigest(),
    "frozenStraddlingScenes": v1["straddlingScenes"],
    "scenes": len(order), "refused": 0, "multiPartPlans": multi,
    "offsetsIdenticalToV1": identical, "extentsUnderHalfSecond": short,
}, indent=2))
