# Untouched-Sherlock read of the LLM mapper: result (2026-11-08)

This is the read registered in
[the untouched-read protocol](../../../plans/2026-11-08-untouched-read-protocol.md). The protocol
was committed and pushed at `60bd824e1` (origin/main) before the read. The arms ran from that
commit unchanged.

- **Gold support.** Frozen first: 833 untouched units, of which 635 are eligible. Gold SHA
  `68cc307c…`, support SHA `809e9d16…`.
- **Population.** Five participants were scored: NN02, NN09, NN10, NN12 and NN14. NN01 was mapped
  but has no usable gold.
- **Completeness.** Every arm returned all 833 units with none missing. v2 left 2 units null and
  v3 left 3; both count as misses.

| Arm | Scene-exact (participant avg) | Within one | Pooled | Paired gain over old mapper [95% CI] |
|---|---:|---:|---:|---:|
| Old mapper (`all17-monofill`) | 55.15 | 78.91 | 60.47 | — |
| **v2 Opus, primary** | **75.36** | 88.22 | 77.32 | **+20.21 [+11.15, +33.69]** |
| v3 Opus, structured claims | 75.90 | 89.55 | 77.64 | +20.75 [+11.44, +34.61] |
| Automatic (Whisper + Claude-vision frames, Sonnet) | 70.02 | 84.53 | 73.39 | +14.87 [+7.94, +23.80] |

The CI is a participant bootstrap with seed 20260902 and 2,000 draws. With n = 5 it is coarse.

Per participant, scene-exact for old / v2 / automatic:

| Participant | Old | v2 | Automatic |
|---|---:|---:|---:|
| NN02 | 57.0 | 68.6 | 64.0 |
| NN09 | 22.2 | 68.3 | 54.0 |
| NN10 | 63.9 | 82.6 | 80.6 |
| NN12 | 71.4 | 79.9 | 78.3 |
| NN14 | 61.3 | 77.5 | 73.2 |

## Reading

- **The gain holds out of sample.** v2 drops from 79.2 on development to 75.4 here. The old
  mapper drops from 61.0 to 55.2, so these participants are harder for both.
- **Every participant improves.**
- **The automatic film record also generalizes,** at +14.9 points.
- **Limits.** This is still one film that the LLM may know. No claim is made about unfamiliar
  films, calibration or row-level accuracy.

## Execution note

Each mapper exited non-zero after writing all participant files. Its built-in development
scorer had no development units to average in an untouched-only run. Scoring is done by
`untouched_read.py`, so this did not affect the read. The harness guard was fixed afterwards.

## Disclosure

As stated in the protocol, coarse untouched-gold statistics had been printed during exploration.
No per-unit labels were seen.
