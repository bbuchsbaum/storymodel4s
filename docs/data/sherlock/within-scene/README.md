# Within-scene adjudication: admitted answer files

Content-free records of the within-scene adjudication lanes under
`docs/plans/2026-09-03-within-scene-precision-preregistration.md`. Each file carries participant
alias, unit index, the adjudicated segment range (or `none`) and the adjudicator's `sure` flag.
Free-text notes and every word of recall or annotation stay outside Git, in the study record
(`data/study/recall-to-video/within-scene/`), with the packet and the sealed key.

| File | Lane | Status | Produced | Digest |
|---|---|---|---|---|
| `answers-machine-lane.content-free.tsv` | machine | **diagnostic, never gold** (M1 Law I1) | 2026-09-03, eight fresh-context language-model adjudicators (claude-fable-5-1), one packet chunk each, rubric verbatim, no key; chunks, filled copies and `lane-manifest.json` retained in the study record | sha256 `59cf49dced91deee72d141ff1b20fababb4a47a12f41390eefdbbc1c8aa320bb` |
| `answers-human-lane.content-free.tsv` | human | adjudicated; the measurement of record | not yet run | |

The packet these files answer is `packet.md` sha256
`b7dd63389bf0a2f3bd1f4e13dd9808f9015e1fb42d026c72bb9c32075b045d52`; the key is `key.tsv` sha256
`e0d06c04e785bb9a6394aa65f578589e6461698a74f1288f01b7654d5d0a90ab`; both are regenerable from the
inputs named in `manifest.json` with seed 20260903. A file whose name and `# lane:` header do not
agree is rejected by the scorer. The machine lane may not select an arm and its estimates may be
quoted only if its median range Jaccard against the human lane reaches 0.5.
