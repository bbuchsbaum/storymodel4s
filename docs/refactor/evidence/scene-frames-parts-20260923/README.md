# Part-correct scene frame extraction

`bd-01M35JG7DXD7NSEE4WVEKT3CSF`, acceptance item 3 only. Code: `eb823496` and the
review repair `f43dd612` (parent `604080fc`), `tools/recall-study/extract_scene_frames.py`.

A scene that crosses the part boundary used to keep only its majority part. Now every declared
part receives at least one frame. The remaining frames are split by duration using largest
remainders over the annotation's decimal seconds, with ties going to the earlier declared part and
the total equal to frames-per-scene. Each part's frames are sampled strictly inside that part's own
extent, from that part's own file. Any refusal happens before a frame is written. The manifest is
tagged `scene-frames/v2` and records per-part extents, rows and frame ordinals.

## What this does and does not establish

- **Synthetic generality, not a measured Sherlock failure.** The frozen Sherlock manifest
  (`sha256 06a69083…`, pinned in `sherlock-development-20260923/protocol.json`) records
  `straddlingScenes: []`. That artifact is not regenerated and its digest is untouched.
- **Real annotation, read only** (`sherlock_parity_check.py` → `sherlock-parity.json`): all 50
  Sherlock scenes plan without refusal, all single-part, and all 50 get offsets identical to the
  frozen v1 manifest. No frames were extracted.
- Single-part v1 parity is claimed only for extents of at least 0.5 s. v1 stretched shorter extents
  to 0.5 s and sampled outside them; a control test shows this.
- Exact seek/media correspondence is out of scope (`grab` rounds seeks to milliseconds); a later
  receipt court owns it. So are the caption loader (AC4) and the command and package (AC1/2/5).

## Runs (author's own, `LocallyObserved`)

| artifact | command | result |
|---|---|---|
| `unit-tests.log` | `python3 tools/recall-study/tests/test_extract_scene_frames.py -v` at `f43dd612` | 12/12 pass, exit 0 |
| `mutations/` | `python3 tools/recall-study/tests/mutate_extract_scene_frames.py --out DIR` | 12/12 named mutants compile and fail their named test run in isolation (exit 1, `failures=1`); restored controls pass before and after. `receipt.json` binds the source and test sha256 to `f43dd612`. |
| `sherlock-parity.json` | `STORYMODEL4S_DATA=<data root> python3 docs/refactor/evidence/scene-frames-parts-20260923/sherlock_parity_check.py`, from the repository root | as above |

The mutants were each run against their named test alone. Several also fail other tests in the
full suite; that is not counted here.

## Review

A fresh-context reviewer read `eb823496` cold, reproduced the tests and mutants, and reported three
findings that should be fixed, all reproduced and repaired in `f43dd612`:

- "Exact rationals" was exact over binary floats, not decimals. 0.2 s beside 2.2 s gave `[1, 7]`
  where the decimal tie gives `[2, 6]`.
- The undeclared-part guard had no discriminating test.
- "Strictly inside" was false below float resolution.

Each fix now has a named mutant. Of the reviewer's nits, the unreachable sum invariant stays as
an invariant, and the import-time `git` call is unchanged (pre-existing).
