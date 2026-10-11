# Multipart caption package qualification

Producer: `62f8eef340b5a41325dfa3ed2afe9e0b55412e17`, based on local main
`a2a75021a4570a3c23e65a41d9476f456eacaabd`.
Mote: `bd-01M37AN2FX81EKWVJJ85PD9520`.

The loader verifies an externally pinned multipart manifest, its metadata and streamed raw inputs,
then replays probe/frame/caption joins with explicit local editions and global-to-local extents.
Sampled and actually examined entries remain separate; gaps are presentation-ordinal ranges and
density is an explicit count ratio. Package replay remains Draft and does not certify extraction
execution, temporal coverage between samples, or caption accuracy.

## Executed evidence

- Focused restored suite: 34 passed, 0 failed/errors.
- Strict `checkAll` in the clean standalone clone at the exact producer: exit 0, 56 test totals,
  10,440 passed, 0 failed/errors, 5 skipped (10,445 total). Formatting ran last.
- Eleven compiled mutants were killed: manifest pin, raw frame pin, metadata pin, global/local
  membership, examined deduplication, strict UTF-8, frame input identity, probe input identity,
  package constructor, part constructor and location constructor. Constructor tests were cleaned
  and recompiled; compile refusals have accepting same-shape controls.
- Mutation failures are named in `package-qualification.json`. UTF-8 removal fails three relevant
  tests. Opening a constructor also opens Scala's universal constructor application, so those
  mutations fail both relevant doors rather than an unrelated assertion.

The first focused development run used an incorrect resource filename and failed. Its log is
retained. The first raw-frame-pin mutant left an unused variable, so fatal warnings prevented
compilation; this is inconclusive and is not counted. A subsequent sandbox-denied launcher run is
also uncounted. Corrected runs use `-03` log suffixes; the manifest mutant's original compiled run
is retained. The qualification script skips already-receipted mutations when resumed.

`manifest.json` binds the compressed raw logs, wrapper exit metadata, patches and qualification
receipt. The logs describe project-authored F0 and synthetic replay clocks, including a deliberately
counterfactual second-part clock. Those replays test membership and refusal, not real-media timing.

## Review and delivery boundary

The independent read-only reviewer found lossy UTF-8 decoding in the initial candidate. The exact
producer fixes it and adds malformed-byte witnesses with matching pins plus a valid U+FFFD control.
The source review passed; `review.txt` records its scope. Final evidence review is recorded with the
landing on Mote. No hosted CI or push is claimed.
