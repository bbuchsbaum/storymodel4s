# A local video source for the mapping workflow

`VideoSourceCli` accepts an explicit job file and produces a multipart caption package plus
`source.json`, a prepared timed source accepted by study preparation. Descriptions remain Draft
model proposals. Their native film clocks, receipts and separate sampled/examined coverage survive
workspace import. This workflow makes no claim about caption accuracy, dialogue or fleeting action.

## Configure existing tools and admitted inputs

The command uses installed local ffmpeg, ffprobe, PySceneDetect and the pinned MLX caption worker.
It never installs a tool, downloads weights or falls back to a hosted service. Configure actual paths
for the two Python environments, both worker scripts and the verified model directory. Each video
part needs an admitted fixture manifest declaring its checksum, stream inventory and permitted
stream dispositions; this is input admission metadata, not a hand-written narrative annotation.

This creates an F0 job from existing project-authored fixture recipes. Run it from the repository
root, and replace runtime paths with installations available on your machine:

```python
import json
from pathlib import Path

fixture = Path("media/src/test/resources/f0")
caption = json.loads((fixture / "f0-v1.caption-request.json").read_text())
detector = json.loads((fixture / "f0-v1.detector-request.json").read_text())
job = {
    "schemaVersion": "video-source-job/v1",
    "edition": "f0-video-demo",
    "runtime": {
        "ffmpeg": "/opt/homebrew/bin/ffmpeg",
        "ffprobe": "/opt/homebrew/bin/ffprobe",
        "detectorPython": "media/worker/.venv/bin/python",
        "detectorScript": "media/worker/scenedetect_worker.py",
        "captionPython": "media/worker-vlm/.venv/bin/python",
        "captionScript": "media/worker-vlm/caption_worker.py",
        "modelPath": "tmp/models/Qwen3-VL-4B-Instruct",
    },
    "parts": [{
        "occurrence": "part-1",
        "manifest": str(fixture / "f0-v1.manifest.json"),
        "input": str(fixture / "f0-v1.mov"),
        "streamIndex": 0,
    }],
    "model": {k: caption["model"][k] for k in ("repo", "revision", "filesSha256")},
    "captionRecipe": caption["recipe"],
    "detectorRecipe": detector["detector"],
    "samplesPerShot": 4,
    "maxDecodedBytes": 1000000,
    "timeoutSeconds": 1200,
}
Path("video-job.json").write_text(json.dumps(job, indent=2))
```

Input paths resolve inside the job directory. Runtime paths may explicitly name installed tools
outside it. `maxDecodedBytes` caps each part's temporary full-frame decode for the boundary worker;
the command deletes that decode after extracting samples. It copies admitted input videos into the
package, so allow storage for those inputs, retained samples and one temporary decode.

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.VideoSourceCli build video-job.json video-output'
```

An existing output is refused. Successful publication contains `package/manifest.json`, its pinned
artifacts, `source.json`, and `completion.json`. The reported manifest checksum is the independent
pin for subsequent full media replay:

```sh
sbt 'pipeline/runMain storymodel4s.pipeline.VideoSourceCli replay video-output/package MANIFEST_SHA256 f0-video-demo replay-output'
```

Keep the entire original package for revalidation. Portable `source.json` import checks internal
caption/clock/receipt consistency; it does not rehash video files or recreate an execution witness.
The worker and interpreter digests and observed module versions are recorded. This is not a wheel
or container audit.

## Prepare and inspect mappings

Point the ordinary study configuration at the prepared artifact:

```json
{
  "schemaVersion": "storymodel-study/v1",
  "source": {"format": "checked-video", "path": "video-output/source.json"},
  "profile": "recall-baseline-lexical-words/v1",
  "recalls": [{"participant": "p1", "session": "s1", "path": "recall.txt", "timing": null}]
}
```

Use the existing study preparation, paired mapping, exchange and workspace commands. Recall access
and publication rules are unchanged. The video source is also a receipt-bearing caption sidecar
for a source whose physical support is already on the same checked film axes; the command does not
infer an annotation-to-film clock transformation from matching part labels.

A caption's interval is a hull of its examined frames. Gaps remain available through sampled and
examined ordinal inventories; the hull is not a claim that intervening video was observed. Local
parts keep their own clocks. Repeated occurrences of an identical local bundle need an explicit
composition before timed-source projection. A crossing extent retains its per-part children and
has no invented aggregate clock.

## Qualification boundary

Live caption generation requires the configured runtime and pinned model files. A missing local
capability is an actionable refusal with no output publication. Replaying synthetic fixtures tests
contracts and refusal behavior; it does not substitute for live worker validation or the admitted
Sherlock development-participant journey.
