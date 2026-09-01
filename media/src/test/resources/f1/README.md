# F1 v1: a checksummed *Big Buck Bunny* excerpt

The second rung of the fixture ladder (movie plan §9): a pinned open film excerpt that tests real
container and codec behaviour. It supports source-side construction only; no annotation, gold, or
narrative claim is admitted with it.

| File | Role |
|---|---|
| `f1-v1.manifest.json` | Exact-byte manifest of the excerpt (SHA-256 `efcdc104…`, 3,640,647 bytes), the source archive and inner-file hashes, the CC BY 3.0 attribution, and the recorded derivation. |
| `f1-v1.ffprobe-envelope.json`, `f1-v1.ffprobe-stdout.json` | The recorded `ffprobe` invocation and its verbatim stdout (720 H.264 packets on 1/12288, 1407 AAC packets on 1/48000). |
| `f1-v1.frames-envelope.json` | The recorded decode to BGR24 (720 frames of 320x180, 124 MB, SHA-256 `e1fcc243…`); the bytes are not committed. |
| `f1-v1.detector-request.json`, `f1-v1.detector-outcome.json`, `f1-v1.detector-envelope.json` | The recorded `ContentDetector` run (threshold 27.0, minimum scene length 15): eight cut proposals. |

**The excerpt bytes are not in this repository.** Reproduce them with

```
bash media/tools/derive_f1.sh /path/to/BigBuckBunny_320x180.mp4 tmp/f1/f1-v1.mp4
```

after downloading and verifying the official archive named in the manifest. The derivation is a
packet copy (no re-encode) and produced identical bytes on two consecutive runs. The live courts
find the excerpt at `tmp/f1/f1-v1.mp4` or `STORYMODEL4S_F1_EXCERPT` and never download it.

Attribution, as the licence requires: *Big Buck Bunny*, (c) copyright 2008, Blender Foundation /
www.bigbuckbunny.org, licensed CC BY 3.0.

Authority: every runtime record over these bytes is `Draft` under ADR 0007 C1. The detector's cut
proposals are proposals about visual discontinuity between consecutive frames; they carry no
morphology and say nothing about shots, scenes, or story.
