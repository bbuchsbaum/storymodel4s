# Executed sampled extraction qualification

Producer: `8014f86486680572346bc5a51acd8beb476a8089`, over local main
`06a67eefa36be59e447e7c784fee05a2879d3f63`.
Mote: `bd-01M37ANBB3VPY81RGMA1Q1CHA2`.

The JVM runner verifies raw input bytes, executes ordinal selection, and checks the observed
selected-frame PTS, clock, dimensions, byte count and input stability before publishing output.
An asymmetric moving-image fixture checks pixels with a display rotation and nonzero origin.
The format allowlist refuses concat references; MOV external data references retain ffmpeg's
installed default refusal. This is not a general audit of every supported demuxer.

## Executed evidence

- Focused restored suites: 16 passed, 0 failed/errors/skipped.
- Strict reference scope (`media/test; scalafmtCheckAll; scalafmtSbtCheck`) at the exact producer:
  exit 0, 118 passed, 0 failed/errors, 3 skipped (121 total). Formatting ran last.
- Eight compiled mutants killed: PTS agreement, rational clock agreement, observed geometry,
  disabled autorotation, preserved origin, demuxer allowlist, raw input pin and constructor access.
  Each behavior mutant failed its named test with 15 siblings passing. Opening the constructor
  failed its two related access doors, with three other boundary probes passing. Boundary tests
  were cleaned and recompiled and include same-shape accepting controls.
- The qualification script verifies a clean exact source tree before mutations and after the
  restored gate. `manifest.json` pins the raw compressed logs, exit metadata, patches and receipt.

These are locally observed checks against project-authored F0 and generated synthetic media.
They establish selected-frame correspondence, not caption accuracy or all-packet decoding.
Raw execution transcripts can contain process addresses and paths: extraction identity is
execution-specific; `frames.identity` identifies the replayable frame derivation.
Independent source review passed `f76b6ef09b6a84bebb8de0e63886c4040a45e504`; the final producer adds
only the foreign-clock regression witness. Final evidence review and landing are recorded on Mote.
No hosted CI or push is claimed.
