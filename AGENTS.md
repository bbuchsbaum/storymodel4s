# AGENTS.md

storymodel4s is a Scala 3 library for structured, symbolic representation of stories and films,
of people's recall of them, and of the mapping between the two, for psychology and neuroimaging.

| Read | For |
|---|---|
| [VISION.md](VISION.md) | what we are building and the commitments that don't change |
| [docs/ROADMAP.md](docs/ROADMAP.md) | the one current plan: milestones, the finish line, open decisions |
| This file | how work runs, the design contract, build and landing |
| [docs/adr/README.md](docs/adr/README.md) | architecture decisions, with one status each |
| [docs/EVIDENCE.md](docs/EVIDENCE.md) | what counts as proof of a fix or of a measurement |

Mote (`.mote/`, `mote ready`) is the tracker, and it holds current status. Documents hold
direction, not status. Documents bannered as historical (listed in
[docs/README.md](docs/README.md)) are records, not guidance.

## How work runs

Since 2026-11-08 the owner has ruled that we do the best engineering we can and do not let the
good be the enemy of progress.

- **Work from a Mote bead.** Set the actor per call (`--actor` or `MOTE_ACTOR`), and run
  `mote begin` before editing. Close the bead when its exact commit lands. Never run
  `mote actor set` in a shared checkout.
- **Keep one writer per scope.** A second agent should take a separable task or an independent
  review. Communicate with the owner in chat.
- **Size beads to land.** A bead is about a day's work with one acceptance criterion. Prefer the
  smallest slice that lands.
- **Commit early to a branch.** Uncommitted work is lost. Never let a scratch clone, `/tmp` or an
  ignored directory hold the only copy of anything. Push work-in-progress branches you care
  about, because in early November 2026 a deleted ignored directory took unlanded branches with it.
- **Review substantial work cold.** A fresh-context agent, or Codex, reviews the exact commit.
  Rewriting your own work catches prose errors; only running it catches a wrong number.
- **Split findings into two kinds.** A BLOCK is correctness, a broken boundary, or an invalid
  measurement, and it holds a landing. Anything else becomes a follow-up bead.
- **Match ceremony to the claim.**
  - Development work on seen data is engineering: commit inputs, commands and results, and move
    on.
  - Pre-registration and frozen gold apply only to held-out or new-dataset validation, and to
    claims of accuracy or superiority.

Retired: the fleet, Fray, message-board, liveness (L1-L10), candidate and premerge protocols.
Their text is in `docs/governance/fleet-mode.md` and in `git show 9b5a1f270:AGENTS.md`.

## Layout

The module directories are flat and use `CrossType.Pure`. Each module is cross-built for JVM,
Scala.js and Native unless its row says JVM-only. Packages follow `storymodel4s.<module>`.

| Module      | Package                   | Responsibility |
|-------------|---------------------------|----------------|
| `core`      | `storymodel4s.core`       | Opaque IDs, `TextSpan`/`SpanSet`, source atlas, claims/evidence/credence, provenance, hashing |
| `proposition` | `storymodel4s.proposition` | Partial, evidence-backed `PropositionChart` |
| `amr-interop` | `storymodel4s.amr`      | AMR adapter (PENMAN, checked graphs, chart conversion); nothing depends on AMR types |
| `features`  | `storymodel4s.features`   | Aligned feature tracks, window plans, derivation DAG, sidecars. Depends only on `core` |
| `acquire`   | `storymodel4s.acquire`    | Acquisition protocol: task packets, proposals, critic findings, typed patches, resolution |
| `story`     | `storymodel4s.story`      | Narrative ontology: entities, situations, contexts, relation layers, hierarchy, validators |
| `document`  | `storymodel4s.document`   | Mention graph, coreference quotient, referent identity, projection to narrative nodes |
| `recall`    | `storymodel4s.recall`     | Recall units, inventories, timing, discourse functions, recall relations and graph |
| `align`     | `storymodel4s.align`      | Local evidence, costs, candidates, graph-HSMM inference, mapping records, readouts |
| `interview` | `storymodel4s.interview`  | Autobiographical Interview: transcript atlas, detail atoms, episodes, profiles |
| `embed-core` | `storymodel4s.embed`     | Portable embedding contract: fingerprints, spaces, vectors, baselines, cache |
| `embed-grakern` | `storymodel4s.embed.grakern` | **JVM-only** WL-kernel channel over charts; grakern by pinned SHA |
| `embed-onnx` | `storymodel4s.embed.onnx` | **JVM-only** local sentence embeddings with pinned model checksums |
| `view`      | `storymodel4s.view`       | Portable semantic view artifacts for Narrative Codex and Atlas |
| `codec`     | `storymodel4s.codec`      | Canonical circe JSON codecs, mapping records and exchange |
| `corpus`    | `storymodel4s.corpus`     | Portable corpus-intake contract: coordinates, cells, segmentations, manifests; no I/O |
| `corpus-intake` | `storymodel4s.corpus.intake` | **JVM-only** corpus readers, byte verifier, receipts |
| `media`     | `storymodel4s.media`      | **JVM-only** media acquisition: ffprobe ingest, scene/caption/ASR workers; only module that spawns processes |
| `provider-parser` | `storymodel4s.provider.parser` | **JVM-only** parser-provider contract with receipted transport |
| `provider-agent` | `storymodel4s.provider.agent` | **JVM-only** remote parser adapter with content-keyed record/replay |
| `pipeline`  | `storymodel4s.pipeline`   | **JVM-only** orchestration and commands; owns I/O and exit status, no semantics |
| `fixtures`  | `storymodel4s.fixtures`   | Hand-authored War of the Ghosts model, paraphrases, interview example |
| `laws`      | `storymodel4s.laws`       | Discipline law suites and ScalaCheck generators |
| `embed-bench` | `storymodel4s.bench`    | **JVM-only** evaluation harness; no portable module depends on it |

## Build and test

- **Toolchain.** Scala 3.7.4, sbt 1.12.14 and sbt-typelevel 0.8.7. Tests use munit,
  munit-scalacheck and discipline-munit. `Test / parallelExecution := false`.
- **Gates.**
  - Use `sbt testJVM` for a fast loop.
  - A slice gate covers the modules printed by
    `bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND`. That is every module
    that references a changed type, run serially on every platform they build (JVM, JS,
    Native).
  - The full gate is `checkAll` (`compileAll; testAll; scalafmtCheckAll; scalafmtSbtCheck`).
    CI runs it with `'set ThisBuild / tlFatalWarnings := true'`, so keep
    `-Wunused:all -Wvalue-discard` clean.
  - After touching build settings, run `sbt githubWorkflowGenerate`.
- **sbt does not build in a linked git worktree** (jgit raises `NoWorkTreeException`).
  - Gate in a standalone `git clone --shared` or in a `git archive <sha>` export.
  - Never gate the shared primary tree.
  - A `--shared` clone borrows the primary's objects, so run `git fsck` in the primary.
- **Disk.** Check `df -h /System/Volumes/Data` before a full gate. A gate export takes about
  3 GB, and you must delete your own exports when you finish.
- **Overrides.** `embed-grakern` clones its pinned grakern revision unless
  `-Dstorymodel4s.grakern.build` or `STORYMODEL4S_GRAKERN_BUILD` names a local checkout.
- **Reporting a gate.**
  - Run one heavy gate at a time.
  - Never pipe a gate's output.
  - Capture the exit status.
  - Report sbt's Passed, Failed and skipped counts. A run with no totals did not run.
  - Run formatting last.
- **Scripts.**
  - `tools/reference-scope.sh` gives a gate's modules.
  - `tools/nan-polarity.sh` surveys fail-open numeric guards (rule 7).
  - When a rule has caught a real break more than once, write a script for it.

## Design contract

Rules 1-14 bind every change. The cases behind them are in
[design-contract-notes.md](docs/design/design-contract-notes.md).

1. **No single-vector core.** Embeddings are sidecar feature views and one evidence channel among
   several. They never take part in identity, equality or graph structure.
2. **Typed, not stringly typed.**
   - Relation, role, context, status and modality families are enums.
   - Unknown ontologies enter through `Custom(namespace, label)`.
3. **Evidence first.**
   - Every accepted explicit claim cites `SpanSet` or playback evidence.
   - An inference cites its upstream claims.
   - `Credence.rawScore` is never a probability without a recorded `calibrationModel`.
4. **Contexts are mandatory.**
   - Every situation lives in a `ContextFrame` rooted at `NarratedWorld`.
   - Reported, believed or intended content never becomes root-world fact by default.
5. **Discourse time, story-world time and recall time are different.**
6. **Alignment is partial, unbalanced and hierarchical.**
   - Recall may omit, merge, split, reorder, revisit, elaborate or go external.
   - External destinations are explicit states.
7. **Truth, salience, phenomenology and veridicality are different, and none is derived from
   another.**
   - Never give a value that can't be told apart from one it must be told apart from.
   - An unmeasurable value gets no plausible number.
   - Prefer fail-closed NaN guards (`if x > 0 then compute else safe`), and validate finiteness
     at the source.
   - An empty-guard constant declares its class.
8. **Unforgeable boundaries.**
   - Cartesian-product test: if some combination of individually lawful field values is false,
     the type must be born unforgeable.
   - Where validity depends on a relation, provenance, order, identity or context, the type is
     a `final` non-case class.
     - Its construction is private to a checked factory that takes enough context to prove the
       relation.
     - It has explicit accessors and structural `equals`/`hashCode`.
   - A private constructor on a `case` class is not a boundary.
   - Prove a boundary from outside the defining package, with a compile-refusal probe that has
     a positive control. See [unforgeable-types.md](docs/design/unforgeable-types.md).
9. **Sparse.** No dense all-pairs allocations in core paths.
10. **Deterministic IDs and receipts.** IDs are content-addressed, and builds are diffable.
11. **Portable modules stay portable.**
    - Core depends only on cats-core and cats-collections.
    - No HTTP, LLM, ONNX, JVM-only API or graph database in a portable module.
12. **Errors are values.** Prefer `Either[DomainError, A]` or `ValidatedNec` to exceptions.
13. **Unattended builds.**
    - No human is in a build's loop.
    - Providers, including LLMs, return typed proposals with evidence.
    - Only deterministic resolvers accept claims.
    - Unresolved and alternative outcomes are legitimate results.
14. **Fixture policy.**
    - (a) AMR gold comes only from published guideline examples, or from licensed corpora in
      authorized environments.
    - (b) Project charts are machine-generated silver with receipts.
    - (c) War of the Ghosts is a researcher-reviewed narrative fixture, never hand-authored AMR.
    - (d) No story text, recall transcript or excerpt enters the repository before it passes
      `docs/design/story-text-admission-checklist.md`, checked by someone other than the
      proposer, with the answers in the text file's own header. Participant recall is barred
      until the owner records an REB basis; pseudonymization is not consent. Data stays under
      `data/`, which is ignored.

Two principles go with the rules:

- An impossibility claim needs more scrutiny than a positive one.
- An identity or class is derived from what it describes, never asserted by the caller.

## Evidence

The full text is in [docs/EVIDENCE.md](docs/EVIDENCE.md).

- **Say which link of the chain a finding reached.** A finding *permits* when you read the code,
  *occurs* when you measured it, *produces* when you ran it, and *consumes* when you traced it
  forward.
- **Read the artifact, not the status line.** That means test totals, logs and file sizes.
- **Prove a fix with a mutation.** Delete the guard and watch a *named* test fail.
  - A mutant counts only if it compiles and the named assertion fails while its siblings pass.
  - A fixture must tell the hypotheses apart; an inequality is not a discriminating assertion.
  - A positive control needs old code capable of the tested thing; otherwise call it a
    regression guard.
  - Compile-time probes need a clean recompile; mutation runs leave stale probes.
- **A gate is inconclusive** if anything fails to compile. Verify an export before gating it.
  A backgrounded gate's exit status is the fork's, not the run's.
- **Report metrics at the stage they measure.** Retrieval, local ranking and decoding are
  reported separately, so that an order prior cannot hide a weak content channel.
- **Shell traps.**
  - Put message bodies in files, never inline in double quotes.
  - Never `head` a list whose length is the finding.
  - Use `/usr/bin/grep` on source, because ugrep skips files containing a NUL byte.

## Integration and landing

- **Before merging:**
  - verify ancestry with `git merge-base --is-ancestor`;
  - test the merged tree, because a conflict-free merge is not a working merge;
  - print the moved SHA afterwards.
- **Shared trees:** commit with `git commit --only <paths>`, and never `reset --hard` a shared
  branch.
- **Ancestry and stale bases.**
  - Nothing unlanded may sit in a candidate's parent chain.
  - For a stacked candidate, gate the union of its commits' reference scopes.
  - A delta touching only docs or `.mote` never invalidates a gate; one touching a gated
    `.scala` file, `build.sbt` or `project/` does.
- **Refresh before integrating.** Check Git status, Mote claims and reservations, and the
  owner's latest instruction immediately before landing.
- **Others' work.** Reserve narrow paths. Never close a bead that holds another actor's live
  reservation. Before concluding work is lost, run `git fsck --dangling` in the primary
  repository.
- **Sibling seam.** `storyatlas4s` builds this repository from source. For a change to anything
  it consumes, compile and test storyatlas4s before landing.
- **New vocabulary.** A new module, dependency or public vocabulary needs an ADR line that
  records the rejected alternative. The owner decides ADRs.

## Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Every public type has Scaladoc with a one-line "why".
- Write one law or invariant per property. Every prohibited failure mode gets adversarial
  fixtures.
