# Sherlock Qwen ASR excerpt readback — 20 October 2026

Mote `bd-01M5E7ZXABPHBG8P4Y2KN8SZ0V`.

The owner's requested small Sherlock check already existed in the completed Qwen song-test
trial. This follow-up verifies and isolates those results; it executes no new inference.
The model is **Qwen3-ASR-1.7B-8bit**, not the historical Qwen3-VL frame captioner.

Three excerpts, totaling 325 seconds, completed under recorded method
`a13ba56c34be0a17f6376bda23e9249c160ed2ae267559694dc88c08488a041c`:

| Excerpt | Native part start | Duration | Saved completion | New readback |
|---|---:|---:|---|---|
| Part 1 boundary | 20 s | 145 s | Exit 0 | Pass |
| Part 2 boundary | 20 s | 120 s | Exit 0 | Pass |
| Part 1 dialogue | 860 s | 60 s | Exit 0 | Pass |

[Local audio-review page](../../../../data/transcription/sherlock-qwen-review-20261020/comparison.html)
and [full fifteen-excerpt comparison](../../../../data/transcription/asr-comparison-qwen-20261020/run-2/comparison.html)
retain the playback links and recognizer alternatives. No video or audio bytes are copied or
committed by this follow-up. Existing production transcripts and reports are preserved.

The original model revision is `a8379a2e2f9e313c9292cdf1af4055ab56d50d55`; inference used
raw mono 16 kHz audio without VAD, automatic language, temperature 0, seed 0 and a 2048-token
budget. These are historical execution settings, not a new runtime or cold-start benchmark.

## Verification

Run from the repository with the admitted local inputs present:

```sh
python3 docs/refactor/evidence/sherlock-qwen-review-20261020/verify.py
```

The independent standard-library readback pins the previously reviewed comparison, method,
manifest and HTML by SHA-256. It checks all three completion/output hashes, raw versus normalized
text and token counts, source-part offsets and missing word timing. Each excerpt's PCM bytes
match exactly its declared sample range in the pinned full-part WAV. Both source-video identities
and full-part WAV/completion identities match. All three generation counts are below budget.
The new verification command exited 0; its raw log and numeric exit metadata remain in the local
report directory. [verification.json](verification.json) and [manifest.json](manifest.json)
preserve the content-free result and local artifact hashes. The pinned model bytes are not
reloaded or rehashed by this readback; it verifies the retained model/method receipt.

## Disposition

Qwen retains the song text in the part-1 sample but omits opening song text returned by Whisper
and Parakeet in part 2. In the dialogue sample its move-in statement differs in polarity from
those recognizers. These are inspectable text disagreements, not human audio adjudications.

Keep Qwen as a separate alternative and listen to those cases before selecting transcripts or
scaling up. The conditional mapping comparison is not run on these unadjudicated results.
Qwen supplies coarse input-chunk ranges and no word timestamps; timed downstream use needs a
separately checked alignment method. No aligner, participant/gold read, mapping/scoring experiment,
full-film Qwen run, empirical accuracy ranking or production replacement is claimed.
