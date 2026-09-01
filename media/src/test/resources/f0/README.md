# F0 v1: project-authored media fixture

The first acquisition court of the movie plan (`docs/plans/2026-08-29-movie-narrative-architecture.md`
§9.1; admission ledger §6) runs on project-authored media before any third-party excerpt. This
directory is that fixture and its recorded probe.

| File | Role |
|---|---|
| `f0-v1.mov` | The bytes. MOV container: stream 0 rawvideo rgb24 32x18 at timebase 1/24000, variable frame rate; stream 1 pcm_s16le mono 8000 Hz, a 440 Hz tone, 2.000 s. 111,250 bytes. |
| `f0-v1.manifest.json` | Exact-byte manifest: SHA-256, byte length, declared stream set, and the generator's ground truth (48 frames at 1000 ticks, frames 5, 6 and 20 dropped with PTS passthrough; 16,000 audio samples). |
| `f0-v1.ffprobe-envelope.json` | The recorded invocation: which `ffprobe` binary ran (path, version line, SHA-256 of the executable), the exact arguments, and the digest of its stdout. |
| `f0-v1.ffprobe-stdout.json` | That stdout, verbatim. Ordinary CI replays it instead of executing a tool. |

Regenerate with `bash media/tools/generate_f0.sh` (needs `ffmpeg` and `python3`). Two consecutive
runs on 2026-09-01 produced identical bytes. If the bytes ever change, the manifest, envelope, and
stdout must be re-recorded together and the change explained in the commit.

What this fixture may support: container and stream ingest, exact rational timebase, PTS/DTS
handling, gaps, variable frame rate, and replay. What it cannot support: anything about speech,
identity, visual semantics, narrative structure, or recall alignment. It carries no text, no faces,
no third-party material.

Authority: the generation is project-authored. Every runtime record a tool produces over these
bytes is `Draft` under ADR 0007 C1, whatever tool produced it, until an authorized E0 adapter
exists.
