# AGENTS.md

storymodel4s is a Scala 3 library for neurosymbolic representation of stories and videos, human
recall of them, and the mapping between the two, for downstream psychology and neuroimaging work.
The design record is `NARRATIVE_PROCESS_ALIGNMENT_NOTES.md`. The delivery index is
`docs/refactor/PLAN.md`, and the tracker is Mote (`.mote/`, `mote ready`).

This file is the short, current guide. Longer rationale and the measured cases behind each rule
live in linked documents. **Rule ids are stable, so cite them:** L1-L10, T1-T7, SD1-SD8, design
rules 1-14 and governance items 1-7. The text before the 2026-09-23 refactor is
`git show 50a07f69:AGENTS.md`.

| Read | For |
|---|---|
| This file | how work runs, the design contract, landing, build |
| [docs/EVIDENCE.md](docs/EVIDENCE.md) | rule 4 in full: what counts as proof, how to run a gate, how to scope a finding |
| [docs/design/design-contract-notes.md](docs/design/design-contract-notes.md) | the cases behind design rules 1-14 |
| [docs/design/unforgeable-types.md](docs/design/unforgeable-types.md) | the working pattern for rule 8 |
| [docs/governance/fleet-mode.md](docs/governance/fleet-mode.md) | fleet protocol, liveness history, single-developer mode (archived) |

## 1. How work runs (operating mode, owner decision 2026-09-23)

Several agents work in parallel. The owner sets the mode and the lead; nobody else does.

- **Coordination is on Fray**, whose shared board lives in the sibling storyatlas4s checkout:
  `fray --home ~/code/scala/storyatlas4s/.git/fray --as <your-name> ...`. Do not start a second
  board. Use a distinct identity per session. Fray carries questions, answers, reviews and
  pointers. It does not own work.
- **Mote is authoritative for tickets and ownership.** Claim a bead (`status=doing`, `assignee`)
  in the primary store before you edit, and reserve narrow paths when others work nearby.
  Respect existing claims. Set your actor per call (`--actor`) or with `MOTE_ACTOR`. Never run
  `mote actor set` in a shared checkout: it relabels other actors' posts.
- **The lead integrates and lands.** The lead is currently `codex-p1-lead`, and the owner
  reassigns it. An author proposes an exact SHA with gate evidence. A reviewer who did not write
  the code checks that exact SHA. The lead integrates reviewed slices and lands them. Any named
  role has a substitute ladder when its holder goes idle (L10).
- **Heavy gates are serialized** through the Fray gate-lock card (#16): claim it, gate, release
  it. JS and Native runs always need the lock. Scoped JVM-only test runs in your own clone do
  not.
- **Announce edits to shared governing files** (`AGENTS.md`, `build.sbt`, `README.md`,
  `docs/refactor/PLAN.md`) on Fray before making them. ADRs may be edited paragraph-disjoint
  (item 5).
- **The audit trail is git.** A commit message carries what changed, the evidence, and what the
  evidence does not establish.

## 2. Liveness and throughput

Every other rule in this file catches bad work landing. None of them fires when nothing lands.
The rules in this section do, and they outrank the others.

- **L1. Zero-ship is a P0.** If no non-`.mote` commit lands on `main` in 24 hours, diagnosing the
  merge path comes before any review.
- **L2. A landable slice has a named lander within an hour.** "I will not land my own work" is
  always followed by who will.
- **L3. A block is priced.** A BLOCK carries a patch, or a named owner with a deadline. Otherwise
  it is marked *advisory, not blocking*, and it expires after 4 hours.
- **L4. Reap the ledger before reading it.** Landed and superseded rows do not block anything.
- **L5. A design thread terminates.** Within 50 posts or 24 hours it produces a landed change, a
  bead with an owner, or a written "not doing this".
- **L6. No disclaimer rituals.** State what you are doing, and omit what you are not.
- **L7. Correct in place, once, then act.**
- **L8. Prefer the smallest landable slice** to the most complete analysis.
- **L9. These rules expire.** If they don't raise the number of landed commits within a week,
  replace them rather than add to them.
- **L10. No permanent single point of failure.** The ladder is the named actor, then the lead,
  then any active actor who is neither proposer nor reviewer, then the owner. "Active" is
  measured with `tools/who-is-live.sh`, never with a presence lease. A recut that only swaps a
  stale role-holder is bookkeeping, not a new proposal.
- **T1. Size beads to land.** A bead is about a day of work with one acceptance criterion. Split
  epics into child beads, close each child when its exact SHA lands, and let parents close
  with their children.
- **T2. Land reviewed slices promptly.** Don't batch many slices behind one gate. Changes that
  touch only docs or `.mote` are gate-inert: they need review and a format check, not a full
  gate.
- **T3. Tier the gate.** A bounded slice lands on the `tools/reference-scope.sh` modules, run on
  every platform they build, under the lock, with bound totals. The full `checkAll` with CI's
  fatal warnings runs on `main` on a schedule. Red on `main` is a P0.
- **T4. Price every review finding** as BLOCK or FOLLOW-UP.
  - BLOCK means correctness, forgeability, measurement validity, or a failing witness.
  - Only a BLOCK holds a landing.
  - A FOLLOW-UP becomes its own bead.
- **T5. Waiting is the main cost.** An agent who owns a reviewable or blocking item runs
  `fray drive`, or checks `fray inbox --addressed-to-me --unresolved` at every boundary.
  Objections arrive as separate linked cards. Answer a direct question within about 30 minutes,
  or say when you will.
- **T6. Agree the seam, then build in parallel.** Post the contract and a failing consumer test,
  written from outside the module, first (see the `fray-seam` skill).
- **T7. Count what passed.** Report sbt's `Passed`, `Failed` and skipped counts. `Total`
  includes skipped tests.

The measured cases behind L1-L10 are in `fleet-mode.md`. T1-T7 came from 2026-09-22/23: 111
commits landed and 49 more waited reviewed on an integration branch, while few beads closed.

## 3. Standing rules carried over from single-developer mode

- **SD1. A mechanism replaces confidence.** A claim that needs a reviewer also needs a runnable
  falsifier. Mutation is the default: delete the guard, watch a *named* test fail, restore it.
- **SD2. Landing authority is a green gate on the exact merge result**, with bound test totals
  and command receipts. It is scoped per T3. The gate log must contain totals: a run with no
  totals did not run.
- **SD3. Uncommitted is lost, and unreferenced is invisible.**
  - Commit to a branch as soon as the code compiles.
  - Never let a scratch clone or `/tmp` be the only copy of anything, including drafts.
  - Before you conclude that work is gone, sweep `git fsck --dangling`.
- **SD5. ADRs gate vocabulary.** No new module, dependency or public vocabulary without an ADR
  line that records the rejected alternative. The lead, or the owner, decides.
- **SD6. Read your work cold, in a separate pass,** with a fresh-context agent or reviewer. Never
  approve in the same breath as authoring. Rereading catches prose. Only measurement catches a
  wrong number or a wrong object.
- SD4, SD7 and SD8 governed the one-developer protocol. They are archived in `fleet-mode.md`.

## 4. Layout

Flat module directories, `CrossType.Pure`, cross-built for JVM, Scala.js and Native unless the
row says JVM-only. The namespace is flat: `storymodel4s.<module>`.

| Module      | Package                   | Responsibility |
|-------------|---------------------------|----------------|
| `core`      | `storymodel4s.core`       | Opaque IDs, `TextSpan`/`SpanSet`, source atlas, claims/evidence/credence, provenance, content hashing |
| `proposition` | `storymodel4s.proposition` | Canonical local semantic contract: partial, evidence-backed `PropositionChart` |
| `amr-interop` | `storymodel4s.amr`      | Standards-compatible AMR adapter (PENMAN, checked graphs, conversion to and from charts); nothing outside depends on AMR types |
| `features`  | `storymodel4s.features`   | Aligned feature tracks: typed spaces, estimates with coverage, window plans and reducers, derivation DAG, sidecars, feature-use ledger. Depends only on `core` |
| `acquire`   | `storymodel4s.acquire`    | Autonomous acquisition protocol: task packets, proposal-only results, critic findings, typed patches, `ResolutionState` |
| `story`     | `storymodel4s.story`      | Narrative ontology: entities, situations, contexts, relation layers, hierarchy, validators, `AlignmentSource` |
| `document`  | `storymodel4s.document`   | Mention graph, exact-coreference quotient, projection to narrative nodes |
| `recall`    | `storymodel4s.recall`     | Recall units, inventories, timing, discourse functions, recall relations and graph |
| `align`     | `storymodel4s.align`      | Local evidence, costs, candidate policy, graph-HSMM inference, checked mapping records, readouts |
| `interview` | `storymodel4s.interview`  | Autobiographical Interview: transcript atlas, detail atoms, target-episode induction, profiles |
| `embed-core` | `storymodel4s.embed`     | Portable embedding contract (ADR 0001): fingerprints, spaces, batches, validated vectors, deterministic baselines, cache, remote policy |
| `embed-grakern` | `storymodel4s.embed.grakern` | **JVM-only** structural WL-kernel channel over charts; grakern by pinned SHA (`-Dstorymodel4s.grakern.build=<checkout>` to override) |
| `embed-onnx` | `storymodel4s.embed.onnx` | **JVM-only** local sentence-embedding channel with pinned model and tokenizer checksums |
| `view`      | `storymodel4s.view`       | Portable semantic view artifacts for Narrative Codex and Atlas |
| `codec`     | `storymodel4s.codec`      | Canonical circe JSON codecs, mapping records and exchange |
| `corpus`    | `storymodel4s.corpus`     | Portable corpus-intake contract (ADR 0018): coordinates, cells, segmentations, links, profiles, manifest, refusals; no I/O |
| `corpus-intake` | `storymodel4s.corpus.intake` | **JVM-only** corpus readers (xlsx/zip/tsv), byte verifier, receipts; owns I/O, no semantics |
| `media`     | `storymodel4s.media`      | **JVM-only** media acquisition (ADR 0007 §4): fixture manifests, ffprobe ingest, scene/caption workers; the only module that spawns processes |
| `provider-parser` | `storymodel4s.provider.parser` | **JVM-only** AMR parser-provider contract with receipted transport and admission |
| `provider-agent` | `storymodel4s.provider.agent` | **JVM-only** remote parser adapter (ADR 0008) with content-keyed record/replay |
| `pipeline`  | `storymodel4s.pipeline`   | **JVM-only** orchestration and commands (`storyBuild`, exchange export); owns I/O and exit status, no semantics |
| `fixtures`  | `storymodel4s.fixtures`   | Hand-authored War of the Ghosts model, paraphrases, interview example |
| `laws`      | `storymodel4s.laws`       | Published Discipline law suites and ScalaCheck generators |
| `embed-bench` | `storymodel4s.bench`    | **JVM-only** evaluation harness against checksum-verified frozen gold; no portable module depends on it |

## 5. Build, test and mechanised checks

- The toolchain is Scala 3.7.4, sbt 1.12.14 and sbt-typelevel 0.8.7. Tests use munit and
  munit-scalacheck, and the laws use discipline-munit. `Test / parallelExecution := false`.
- **Gates.** `sbt testJVM` for a fast loop. A slice gate uses the modules printed by
  `bash tools/reference-scope.sh "$(git merge-base main CAND)" CAND` (T3). The full gate is
  `checkAll` (`compileAll; testAll; scalafmtCheckAll; scalafmtSbtCheck`). CI's setting is
  `'set ThisBuild / tlFatalWarnings := true'`: keep `-Wunused:all -Wvalue-discard` clean. After
  touching build settings, run `sbt githubWorkflowGenerate`.
- **sbt does not build in a linked git worktree** (jgit raises `NoWorkTreeException`). Gate in a
  standalone clone (`git clone --shared`) or in a `git archive <sha>` export. Never gate the
  shared primary tree: it contains other agents' untracked files.
- `embed-grakern` clones its pinned grakern revision unless `-Dstorymodel4s.grakern.build`, or
  `STORYMODEL4S_GRAKERN_BUILD`, names a local checkout.
- **Scripts. Run these instead of re-deriving the rules by eye:**
  - `tools/reference-scope.sh`: the modules and exact command a gate must cover. It exits 3
    rather than emit an empty command.
  - `tools/premerge-check.sh`: merge-result tree and gate-log totals checks. Its fleet checks are
    inert.
  - `tools/nan-polarity.sh`: a survey of fail-open and fail-closed numeric guards (rule 7).
  - `tools/who-is-live.sh`: who actually acted recently. Run it before assigning work or waiting
    on someone.
- When a rule has caught a real break more than once, the next step is a script, not a firmer
  sentence.

## 6. Design contract (non-negotiable)

Rules 1-14 bind every change. The measured cases behind them are in
[design-contract-notes.md](docs/design/design-contract-notes.md).

1. **No single-vector core.** Embeddings are sidecar feature views. They never participate in
   identity, equality or graph structure.
2. **Typed, not stringly typed.** Relation, role, context, status and modality families are
   enums. Unknown ontologies enter through `Custom(namespace, label)`, never through raw strings.
3. **Evidence first.** Every accepted explicit claim cites `SpanSet` evidence, and inferences
   cite upstream claims. `Credence.rawScore` is never a probability without a recorded
   `calibrationModel`.
4. **Contexts are mandatory.** Every situation lives in a `ContextFrame` whose root is
   `NarratedWorld`. Reported, believed or intended content never becomes root-world fact by
   default.
5. **Discourse time, story-world time and recall time are different.**
6. **Alignment is partial, unbalanced and hierarchical.** Recall may omit, merge, split, reorder,
   revisit, elaborate or go external. External destinations are explicit states.
7. **Truth, salience, phenomenology and veridicality are different, and none is derived from
   another.** Most defects here fall into one class: *a value that cannot be told apart from a
   different value it must be told apart from.* The test: name two things that ought to differ,
   then ask which published field differs between them.
   - **No default licence.** An unmeasurable value never gets a plausible number, and an
     unestablished status never gets a confident one. When the licence cannot be established,
     take the conservative truthful status.
   - **Typing a conflated quantity launders it.** Use one coordinate per meaning, never one
     wrapper over both. A map key is a comparability claim.
   - **NaN polarity.** Prefer the fail-closed shape, `if x > 0 then compute else safe`. A NaN
     finding has three separate questions: polarity, reachability, and what actually comes out,
     which must be run. Validate finiteness at the source of a weight or score.
   - **An empty-guard constant declares its class:** forced, delete-sentinel,
     conservative-as-1.0, conservative-as-0.0 or flattering. Do not unify the constants.
8. **Unforgeable boundaries.** Invalid states are unrepresentable where rules are stable
   (phantom states, smart constructors).
   - A private constructor on a `case` class is not a boundary: `fromProduct`, `Mirror` and a
     qualified-private `copy` all remain.
   - A validating type is a `final` non-case class with explicit accessors, structural
     `equals`/`hashCode`, an intentional `toString`, and construction private to its checked
     factory. A type that hides a field must close its read doors too.
   - **Cartesian-product test.** If some combination of individually lawful field values is
     false, because validity depends on a relation, provenance, order, identity or context, the
     type is born unforgeable. That applies to new types as well as old ones.
   - The checked factory must take enough context to prove the relation, and a court must kill
     removal of the check.
   - Courts compile-refuse `apply`, `copy`, `fromProduct` and `Mirror.ProductOf`, each with its
     own same-shape positive control. They probe from *outside* the defining package, or from a
     subpackage for `private[x]`.
   - A validator whose return type is its own argument type, on a publicly constructible type,
     signals a missing boundary.
   - See [unforgeable-types.md](docs/design/unforgeable-types.md).
9. **Sparse.** No dense all-pairs allocations in core paths.
10. **Deterministic IDs and receipts.** Content-addressed IDs; builds are diffable.
11. **Portable modules stay portable.** Core depends only on cats-core and cats-collections. No
    HTTP, LLM, ONNX, JVM-only API or graph database in portable modules.
12. Prefer `Either[DomainError, A]` or `ValidatedNec` to exceptions.
13. **Unattended builds are P0.** No human is in the loop of a build. Agents return typed
    proposals with evidence. Only the deterministic resolver accepts claims. Unresolved and
    alternative outcomes are legitimate results.
14. **Fixture policy.**
    - (a) AMR gold comes only from published guideline examples or licensed corpora.
    - (b) Project charts are machine-generated silver with receipts.
    - (c) War of the Ghosts is a researcher-reviewed narrative acceptance fixture, never
      hand-authored AMR.
    - (d) No story text, recall transcript or excerpt enters the repository before it passes
      `docs/design/story-text-admission-checklist.md`. Participant recall is barred until the
      owner records an REB basis.

Two more principles belong with these rules:
- **An impossibility claim needs more scrutiny than a positive one.** Ask whether any
  configuration would make it work.
- **An identity or class is derived from what it describes, never asserted by the caller.** Ask:
  if the caller lied here, what would catch it?

## 7. Evidence discipline (rule 4)

Rule 4 has 35 sub-rules, all in [docs/EVIDENCE.md](docs/EVIDENCE.md). Its core:

- **A finding has a chain.** Say which link you reached:

  | link | question | method |
  |---|---|---|
  | permits | can the code produce this? | read it |
  | occurs | does it actually happen? | measure the corpus |
  | produces | what comes out when it does? | run it |
  | consumes | who depends on that output? | trace forward |

- **Read the artifact, not the status.** Check the body length, the test totals, the log and the
  directory size, not the report that the step succeeded.
- **Proof of a fix.**
  - A control must fail for the *named* property.
  - A mutation kill counts only if the mutant compiles and the named assertion fails while its
    siblings pass.
  - A fixture must tell the hypotheses apart.
  - An inequality is not a discriminating assertion.
  - A positive control requires old code capable of the tested thing; otherwise call it a
    regression guard.
  - Compile-time probes need a clean recompile.
- **Running a gate.**
  - Never pipe a gate.
  - Capture the exit status in the log.
  - Totals or it did not run.
  - Run formatting last.
  - A compile error anywhere means the gate is inconclusive.
  - Verify the export before gating it.
  - A backgrounded gate's exit is the fork's, not the run's.
  - An author's gate is `LocallyObserved`: cite CI by run URL, matrix cell and SHA.
- **Shell traps.**
  - Never put message bodies in inline double-quoted arguments: backticks execute. Use a file
    or `--body-file`.
  - Never `head` a list whose length is the finding.
  - `>` inside `[ ]` is a redirect.
  - An equality test over two failed substitutions passes.
- **Scoping a finding.**
  - Sweep the shape, not the spelling, and sweep your own work first.
  - Reading a branch establishes permission, not occurrence.
  - Trace the consequence before escalating it.
- **What a number may claim.**
  - Run the estimand check: where does our own uncertainty go, and does it correlate with a
    subject property? If it does, that is P1.
  - No value and low support are different failures.
  - Dropping a term from a normalized aggregate rescales the rest.

## 8. Integration and landing

The lead holds these rules when integrating, and authors hold them when preparing a slice. The
full text is governance item 3 in `fleet-mode.md`.

- **Verify the branch before merging.** Afterwards, print the SHA that should have moved. Never
  `reset --hard` shared branches.
- **Ancestry.** Nothing unlanded may sit in a candidate's parent chain. Git is authoritative
  over any ledger: run `git merge-base --is-ancestor` rather than trusting a status string.
- **A conflict-free merge is not a working merge.** When two changes touch one file, test the
  merged tree.
- **Reference scope is the union** across a stacked candidate's commits, test sources included.
- **Stale bases.** A delta that is gate-inert (touching only docs, `AGENTS.md` or `.mote`)
  never invalidates a gate. A delta that touches a gated input does.
- **Sibling seam.** `storyatlas4s` builds this repository from source. When `main` changes
  anything it consumes, compile and run its tests before the landing is final.
- **Commit narrowly.** Use `git commit --only <paths>` in shared trees, and check the whole
  `git status --short` first. Record a landing where it is tracked before announcing it.

## 9. Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Every public type has Scaladoc with a one-line "why", not just "what".
- One law or invariant per property, and adversarial fixtures for every prohibited failure mode
  in the design record (§27.2, §52.3).
