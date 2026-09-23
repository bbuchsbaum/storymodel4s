# AC4 slice S1: sampled frame sets and an edition-bound caption join

`bd-01M37AMS9NYY9J0FWJESVPJ56E` (child of `bd-01M35JG7DXD7NSEE4WVEKT3CSF`). Scope approved by the
lead on Fray #86, seq 749. Commits on `claude-p1/ac4-s1-sampled-frames`, rebased onto `main` `b737b14f`. The pre-rebase SHAs
(parent `43e5a613`) are shown too: the review and first gate ran on those, and the rebase left
`frames.scala` and `caption.scala` byte-identical.

| commit | pre-rebase | what |
|---|---|---|
| `0c37dd2d` | `1e88c9ea` | ADR 0007 amendment, written before the vocabulary (SD5) |
| `fac95927` | `d62e4e0c` | consumer test written first, from outside `storymodel4s.media` (T6) |
| `509a7c25` | `960015a1` | implementation |
| `91d1ba3f` | `9f10e10b` | cold-review repair (the BLOCK and three follow-ups), algorithm tag v3 |

## What it establishes

On declared inputs, a sampled frame's **sample index** (position in the bytes) and its
**presentation ordinal** (presented packet) are kept apart, and time comes only from the latter.
The join:

- refuses bad selections, non-member ordinals and wrong byte counts;
- refuses another edition, for full and sampled sets alike;
- refuses a request naming an unheld frame, and a forged extent shape;
- closes each extent on its own samples' presented packets.

`FrameSet`'s own admission is unchanged. On the full-decode path, the caption refusal for an
ordinal beyond the decode is now `caption/unsampled`, where it was `caption/request`.

It does **not** establish that a tool run produced these samples. That is S3's executed
correspondence witness. The caption request does not yet bind the selection: follow-up
`bd-01M37D2JPGXRTVF37PN417JW1Y`.

## Runs (author's own, `LocallyObserved`)

| artifact | command | result |
|---|---|---|
| `gate-91d1ba3f.log.gz` | `git archive 91d1ba3f` export (rebased; `main` moved `build.sbt` and `core`, which `media` compiles against); same commands | 62 total, 59 passed, 3 skipped, 0 failed; `TEST_EXIT=0`, `FMT_EXIT=0` |
| `gate-9f10e10b.log.gz` | `git archive 9f10e10b` export (pre-rebase); `sbt -batch 'set ThisBuild / tlFatalWarnings := true' media/test`, then `scalafmtCheckAll scalafmtSbtCheck` separately | 62 total, 59 passed, 3 skipped (the live worker tests, which need local weights), 0 failed; `TEST_EXIT=0`, `FMT_EXIT=0` |
| `mutations/` | `python3 docs/refactor/evidence/ac4-s1-sampled-frames-20260923/mutate_s1.py OUTDIR`, from a clone at `9f10e10b` | 9/9 named mutants compile and fail exactly their named test (`Failed: Total 1`); restored controls 10/10 before and after. `receipt.json` binds the sha256 of `frames.scala` and `caption.scala` at `9f10e10b`, which are identical at `91d1ba3f` |

`tools/reference-scope.sh` names `media` both before and after the rebase (`43e5a613..9f10e10b`, `b737b14f..91d1ba3f`); `media` as the only module to gate. It is
JVM-only, so the run needs no gate lock (AGENTS.md §1). `qualification.json` records totals,
exits and the raw and compressed digests of every log.

## Review

A fresh-context reviewer read `960015a1` cold and reported one BLOCK. Because `CaptionExtent` is a
case class, a forged, out-of-order extent `[0,11,1,2]` was admitted with a hull that omitted a frame
the model was shown, and an empty extent threw. The same door existed on the full-decode path.

`9f10e10b` fixes it. The named test is red on `960015a1` and green on `9f10e10b`.

The reviewer also reported four follow-ups. Three are fixed here: the selection binding had no
test, the key change was unstated, and the algorithm tag was stale. The fourth, the request not
binding the selection, is filed as its own bead.

Two mutants first failed to compile under fatal warnings (unused bindings). They were rewritten
until they compiled and were killed by their named test. A mutant that does not compile proves
nothing.
