# October recovery and workstation checkpoint

The numerical implementation is qualified by the full hosted matrix at
`433aa1056f6aa5e88b63b4b665079e9959a266ae`. The matching StoryAtlas consumer is
`d67dccfb7eec07e184ce3ef8fe6ccc16c3745523`, whose declared source pin is that producer.
Source and exact-consumer workstation acceptance are complete. Final integration review and
GitHub main synchronization remain pending.

## Qualified hosted code

| Gate | Exact code | Result | Retained evidence |
| --- | --- | --- | --- |
| [Producer CI](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37526554024) | `433aa105` | 80 test cells; 12,450 passed, 0 failed/errors, 12 skipped | [Receipt](workstation/hosted-37526554024.json), full per-step ZIP alongside it |
| [Documentation](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37526553980) | `433aa105` | parser 5; examples 13; site 21 pages built, 20 verified; full browser/provenance verification | [Receipt](workstation/hosted-37526553980.json), full ZIP alongside it |
| [Portable kernel](https://github.com/bbuchsbaum/storymodel4s/actions/runs/37521362926) | `a6c27212` | JVM17/JVM21/JS17/Native ReleaseFast: 20 passed, 0 failed/errors/skipped; kernel unchanged at `433aa105` | [Numerical evidence](numerical/) |
| [StoryAtlas CI](https://github.com/bbuchsbaum/storyatlas4s/actions/runs/37526783465) | `d67dccfb` | 15 cells; 745 passed, 0 failed/errors/skipped; link/doc/format/workflow checks succeed | Consumer receipt in its `docs/delivery/evidence/workstation-checkpoint-20261006/` |

The producer matrix runs JVM on Temurin17/21 and JS/Native on Temurin17, Ubuntu22.04,
Scala3.7.4. Native modules run sequentially through the generated `testNative` route.
Assertions and 30-second test deadlines remain unchanged. Raw GitHub API ZIP logs are retained:
the stitched CLI log omitted some tails in an earlier failed run. The corrected complete counts
and original partial capture remain in [the bootstrap receipt](bootstrap/native-aggregation-timeouts.json).

The historical Mac alignment goldens are unchanged. The shared fdlibm arithmetic revision,
independent original-C oracle, high-precision comparisons, compiled mutation witness and
propagation/record captures are described in [ADR0023](../../../adr/0023-portable-alignment-numerics.md)
and the numerical receipts. This establishes fixed-input engineering determinism on qualified
targets. It does not establish corpus-level scientific validity.

## Workstation acceptance still in progress

`buc-gw01.tail5f873d.ts.net` authenticates as `BUC-GW01`: macOS15.1.1/ARM64, 10 CPU cores,
32GiB RAM, Temurin21.0.12.1+1, Node24.21.0, Apple Clang16 and Python3.9.6. Repo-local Git identity,
separate Git transport and account-routed GitHub API credentials, Mote identity/ignore behavior
and AC sleep configuration were checked. See [tool identity](workstation/tool-identity.json)
and [API/power observations](workstation/api-power.json). Optional ffprobe is absent; no
private-video capability is claimed.

The a6 precursor passed 59 test cells (9,007 passed, 0 failed/errors, 6 skipped) and documentation,
then exited1 on two test-file format wraps. [Its receipt](workstation/producer-a6-precursor.json)
preserves that red exit. The wraps are fixed at the current code; this is historical evidence.

The first exact433 workstation attempt exited1 in fixturesNative: 7,947 passed and two
30-second timeouts across45 observed cells. [The failed receipt](workstation/producer-03-timeouts.json)
and compressed full log preserve both names. At the subsequent resource observation, host load
was22.26/27.00/28.00 on10cores with other-session CPU use. Resource contention is a plausible
cause, not a proved explanation of either timeout.

The unchanged433 continuation completed successfully in owned tmux job
`storymodel4s-resume-20261006`, after the recorded load preflight. The
[completed status](workstation/producer-04-status.json) and compressed full log bind all commands,
exits and raw hashes. The [producer runner](workstation/host-resume-gate-04.py) retains44 green
prefix cells (7,789 passed), then reruns the entire fixturesNative module, all three laws targets
and eight JVM-only modules. Documentation, three Native ReleaseFast courts and final
format/workflow checks pass. Both previously timing-out tests pass with unchanged assertions
and deadlines. This is assembled coverage across two invocations:56 normal cells plus3
ReleaseFast courts, **9,007 passed, 0 failed/errors, 6 skipped**. The failed cell's158 passes are
excluded from these deduplicated totals. The six skips are four optional media checks, one
remote-provider check and one embedding-benchmark check.
The [consumer runner](workstation/host-consumer-gate-04.py) then completed exactd67 from its
default public source pins:10 JVM/JS cells, **480 passed, 0 failed/errors/skipped**, with
compilation/linking and final formatting/workflow checks. The [summary](workstation/consumer-04-summary.json)
binds actual loaded clean source checkouts to all five declared dependency commits. Public
WOG edition (32 files,29 receipt-bound) and synthetic Bell Voyage (4 files,3 receipt-bound)
export successfully; every bound hash reads back, all SVGs parse and bundled JavaScript syntax
checks. Complete status/log and both export receipt bodies are retained in the consumer's
`docs/delivery/evidence/workstation-checkpoint-20261006/`. This establishes engineering/CLI
qualification, without Atlas browser interaction or private-corpus inference claims.
Owned job state and full logs live under
`~/.local/share/storymodel4s/checkpoints/20261006/` on BUC-GW01, outside temporary directories.
The next session reads `integrated-producer-04-status.json` and
`integrated-consumer-04-status.json` before starting anything. A failed or incomplete status
is not permission to duplicate the gate. Other sessions' processes are left untouched.

## Recovery and private data

The [stocktake](../../STATE-20261006.md) is the frozen initial audit; the
[delivery plan](../../PLAN.md) records the continuing queue. Original refs, nine stashes and
205 dangling objects were preserved before salvage. The verified private recovery bundle and
selected consumer preservation archive have destination readback; GitHub does not replace them.
The rescued reference-scope guard, FrameSet identity and S2a4 courts have their own bounded
receipts in this directory. The broader S2a4 channel-distinction ticket stays open.

[Private data handoff](data-handoff/) is complete on the approved workstation:18 assets,
276,496,625 bytes, with interrupted-stream resumption, independent SHA verification and a
permitted reader smoke. Held-out/video data were excluded. Private manifests, filenames,
participant rows and data-bearing logs remain on approved storage. The handoff tool refuses
unapproved hosts and conflicting bytes. The marker binds manifest identity, destination,
root and complete byte counts; it does not widen corpus admission.

Before final landing: independently review the completed suffix/consumer receipts and public
output hashes at the exact evidence commits, fast-forward main without force in both repositories,
verify live remote parity and update both workstation checkouts. Retain distinct engineering,
private-reader, browser, scientific and release evidence boundaries. The next product work is
the typed availability/abstention distinction and ordinary-file lexical/reference walkthrough
from the existing queue, followed by pinned ONNX qualification.
