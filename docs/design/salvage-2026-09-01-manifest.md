# Salvage manifest — 2026-09-01

Recovery of work stranded when the Codex agent fleet ran out of tokens. Thirty-two
git repositories under `/private/tmp` plus the primary worktree and sixteen
registered worktrees were surveyed. This is the disposition of every one.

Everything found was first preserved before anything was merged:

- every ref from every `/private/tmp` clone was fetched into `refs/salvage/<dir>/<branch>`;
- every uncommitted file from every repo was copied to the session scratchpad;
- the primary worktree was committed verbatim to `salvage/worktree-snapshot-20260901`.

The salvage refs are local-only and can be dropped with
`git for-each-ref --format='%(refname)' refs/salvage | xargs -n1 git update-ref -d`
once you are satisfied.

## Merged

| Work | Recovered from | Evidence |
|---|---|---|
| Story output bundle contracts (acquire/codec/view, 7,669 lines) | `/private/tmp/storymodel4s-output-recovery`, 6 commits `cb63bf08..e0ede3e7` | clean merge |
| SurfaceAtlas artifact codec | `/private/tmp/storymodel4s-surface-atlas-v1-20260831`, `1111c483..9d8b4a34` | clean merge |
| SurfaceAtlas source-identity oracle suite | `/private/tmp/storymodel4s-surface-identity-oracle-9d8b4a3`, uncommitted | 2/2 pass |
| Sinkhorn input-boundary validation | `.worktrees/cursor-grok-sinkhorn-inputs`, uncommitted | align JVM 234 pass |
| core source-bundle contracts + `laws.SourceLaws` | primary worktree, uncommitted | — |
| Sherlock source policy + `docs/data/sherlock` manifests | primary worktree, uncommitted | — |
| NFRD intake + baseball diagnostic bench | primary worktree, uncommitted | — |
| Design courts, source inventories, CLI-slice handoff | primary worktree, uncommitted | — |
| 237 mote board ops | primary worktree, uncommitted | — |

One correction was applied during salvage. The recovered Sherlock manifests cited
owner-decision post `post-01M1CR50ZYM6Q8MG03CBTH9JVRP`, which is 27 characters and
has no creating op anywhere in the Mote store — only five citations of it exist. The
real post is `post-01M1CR50ZYM6Q8MG03CBTHZXY8` (26-char ULID, created 2026-08-31
20:28), taken from the 2026-09-01 recut in `/private/tmp/storymodel-sherlock-recut`.

## Not merged, and why

**Premerge gate hardening** — six lineages across
`storymodel-premerge-finalize-20260901`, `storymodel-premerge-successor-20260901`
and `storymodel-premerge-recut`. Already on `main` via `9cb7e2de`. Verified by
content, not by commit id: `main`'s `tools/premerge-check.sh` and
`premerge-check-test.sh` are strict supersets of every salvaged variant — zero
lines exist in any of them that `main` lacks — and `main` additionally carries the
`GIT_NO_REPLACE_OBJECTS` and `check_no_legacy_grafts` hardening that the salvage
lineages predate.

**Two construction-bypass probes** — recorded in
[salvaged-construction-bypass-findings.md](salvaged-construction-bypass-findings.md)
rather than landed. Both assert that an attack succeeds, and both pass, so
committing them would make CI green on two open holes and red the moment either is
fixed.

**`storymodel4s-grakern-d736dc5-20260831`** — not this project. A clone of
`~/code/scala/grakern` whose HEAD `d736dc5` is that repo's own HEAD. Nothing
stranded; salvage refs dropped.

**`storymodel-replace-audit.KQvskx`** — a two-commit synthetic fixture (`base`,
`bypass`) with no merge base, built to exercise the merge gate's git-replace
detection. Test data, not work; salvage refs dropped.

**Five mutation clones** (`storymodel4s-mut-basis`, `-mut-profile`, `-mut-report`,
`-mut-target-bind`, `-mut-target-collision`) — each holds a deliberately mutated
`view/output.scala` (1,427–1,439 lines, all different). Mutation-testing artifacts.
Merging one would inject a mutant.

**Two scratch fixtures** in `/private/tmp/sm4s-memb-clone` — `NanSortProbe` and
`ProfileReceiptSuite`, both `println` debugging, the latter marked "SCRATCH
MEASUREMENT, not for landing" by its author.

**`docs-site/`** — held only `node_modules`, `dist` and `.astro`; no source. Now
gitignored.

**Everything else** — the remaining `/private/tmp` clones (`docs-clone`,
`support-honesty`, `visualization-doc`, `nomination-doc`, `memb-clone`,
`sherlock-b0r-clone`, `refscope-*`, `sherlock-gate.*`) hold commits that are
already on local branches, or older drafts of documents whose finalized versions
were recovered from the primary worktree. Nothing unique.

## Stale copies that were superseded

The primary worktree held a partially-applied draft of the output bundle from
08-31 13:13. The `storymodel4s-output-recovery` lineage continued that work
through 19:20 and is substantially larger (view/output.scala 1,680 lines vs 1,089,
adding `VerifiedProfileReceipt` and issuer gating). The clone's version was taken
and the worktree draft discarded — it survives in
`salvage/worktree-snapshot-20260901`.

Similarly, the three `sherlock-gate` doc variants: `19N7ms` (15:56) and `dpDs9i`
(16:42) match what the worktree held; `FXQsSA` (16:28) is a superseded
intermediate. All three carry an older ADR-0001 describing `hsmm/v1`; the
worktree's `hsmm/v4` version was kept.
(Reverted to `hsmm/v3` on 2026-09-17: the v4 codec source never landed, so the kept docs
described a schema the library does not emit — bd-01M1D214VVRE118RTEQQ09P7SA.)

---

# Addendum — corrections after a second, complete sweep

The survey above was incomplete. Two gaps were found and closed.

## Gap 1: dangling commits were never swept

The first survey covered branches and `/private/tmp` clones. It never ran
`git fsck --dangling` on this repository, so it missed commits that no ref
points to. There were 100 of them; most are stash entries, but two were real
and both were approved work:

- **`84b6ce8d`** — the approved tip of the story-output bundle
  (`cand-6D9Z91FK56DE5Q88QGH3XGKXF5`, approved by codex-cli-vertical-slice on
  2026-09-01 12:50). The first salvage merged `e0ede3e7`, **nine commits
  earlier**. Those nine commits close both construction bypasses that this
  salvage had recorded as open findings — see
  [salvaged-construction-bypass-findings.md](salvaged-construction-bypass-findings.md),
  which has been corrected.
- **`73bc9af9`** — the entire `provider-parser` module, 4,638 lines
  (`cand-13J7K02VCBNBYWQG8354Y9MGZY`, approved by codex-storymodel-collab
  20:32 and codex-storymodel4s-recall-collab 20:36, **chief authorization
  granted 23:18**). It had never landed and `main` had no `provider-parser`
  directory at all.

Both are now merged.

## Gap 2: the `/private/tmp` scan was top-level and name-matched

The first sweep found 32 repositories. A `find -maxdepth 3 -name .git` finds
**108** — many agents worked in a nested `repo/`, `tree/`, `clone/` or
`exact/` subdirectory. All 103 non-grakern repositories have now been fetched
into `refs/salvage2/*` and their working trees snapshotted.

The expanded sweep found no further *lost* commits: every commit in every one
of them is now either on `main` or on a local branch. It did confirm the
mutation-clone population is larger than reported above — the
`storymodel4s-output-*-mutant-*` family adds ten more deliberately mutated
trees, all correctly excluded.

## Still unlanded, and why

- **`bd50c89f` — `fix: refuse aggregate cost-weight overflow`**
  (`align/cost.scala` +37/−15, `CostSuite` +97). A real fix; `main` has no
  overflow guard in `cost.scala`. Proposed as `cand-2J8257JACMEGGE9H4HRK2TN2SV`
  on 2026-09-01 10:38 and **reviewed by nobody**. Left unmerged: this salvage
  landed approved work, and merging never-reviewed library changes is a
  separate decision.
- **The longer `provider-parser` lineage** (`4393c3d9` → `fce00c84`).
  `cand-70K5PT8QSX3JPC8X0X7ZHEAWX1` carries an approve *and* a block from
  codex-storymodel-collab. The separately approved and authorized `73bc9af9`
  was landed instead.
- **The Astro docs-site workstream** (`bac401d9` and its lineage). `main` has
  no `docs-site` source; this lives on `scout/astro-capability-tour-v4` and
  neighbours, and is unmerged rather than lost.
- **`recall-lineage.json`**: the 08-31 draft `2c179b99` carries a
  `binaryDoubleCellDifferences` field per entry (22 entries, two non-zero:
  19 and 10) that the 09-01 recut on `main` does not. No board post discusses
  the field, so whether its removal was deliberate is unresolved. Flagged, not
  decided.

## Audit trail

Candidates whose approved commits are now reachable from `main` were closed
with `mote candidate reconcile --operator-override`, actor `bbuchsbaum-owner`,
recording that formal review and authorization did not govern the landing:

- `cand-13J7K02VCBNBYWQG8354Y9MGZY` — provider-parser
- `cand-6D9Z91FK56DE5Q88QGH3XGKXF5` — story-output bundle
- `cand-08QSQGY8XMA3FMETGHVR6ZKN1K` — SurfaceAtlas artifact codec

Nine further pending candidates could not be reconciled: `mote` reports they
do not exist in this store, because their proposal ops were written in clone
stores whose `store_id` differs. Their content is on `main` regardless.

Note for whoever fixes the tooling: a reconciled `landed_out_of_band` row still
emits its full pre-transition blocker list, which is exactly the condition
AGENTS.md §L4 says must not happen. There is a 32-commit branch
`fix/candidate-target-scope` in `/private/tmp/mote-target-scope-fix-20260901`
that belongs to the `mote` repository, not this one.

## Gate

`sbt checkAll` on the final tree: exit 0, scalafmt clean, compileAll clean,
**4,827 tests across JVM/JS/Native, 0 failed, 0 errors**, 49 module test runs.
