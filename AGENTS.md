# AGENTS.md

storymodel4s is a Scala 3 library for neurosymbolic representation of written
stories, human recall of those stories, and the alignment between the two —
including the Autobiographical Interview as a latent-source alignment problem.
The design record is `NARRATIVE_PROCESS_ALIGNMENT_NOTES.md`; the synthesized
architecture and roadmap are in `docs/plans/`.

## Layout

Flat module directories, `CrossType.Pure`, cross-built JVM / Scala.js / Native.
Package namespace is flat `storymodel4s.<module>`.

| Module      | Package                   | Responsibility |
|-------------|---------------------------|----------------|
| `core`      | `storymodel4s.core`       | Opaque IDs, `TextSpan`/`SpanSet`, source atlas, claims/evidence/credence, provenance, content hashing |
| `proposition` | `storymodel4s.proposition` | **Canonical local semantic contract**: partial, evidence-backed `PropositionChart` (concept with optional frame, numbered/named roles, polarity, reentrancy, embedded propositions, exact alignment, alternatives) |
| `amr-interop` | `storymodel4s.amr`      | Standards-compatible AMR **adapter**: PENMAN syntax, checked AMR graphs, role canonicalization, isomorphism, frame lexicon, conversion to/from `PropositionChart`. Nothing outside this module depends on AMR types |
| `features`  | `storymodel4s.features`   | Aligned feature tracks over the surface axis: typed spaces/targets, estimates with coverage, window plans + declared reducers, derivation recipes (dependency DAG), sidecar refs, boundary evidence, feature-use ledger (anti-circularity). Depends only on `core` |
| `acquire`   | `storymodel4s.acquire`    | Autonomous acquisition protocol: task packets, proposal-only agent results, critic findings, typed patches, `ResolutionState`, stage-cache keys, prompt-package manifests. Pure; providers are JVM-only adapters |
| `story`     | `storymodel4s.story`      | Narrative ontology: entities, situations, contexts, typed relation layers, hierarchy, trajectory, validators, `AlignmentSource` |
| `document`  | `storymodel4s.document`   | Mention graph (disjoint union of charts), exact-coreference quotient, projection to narrative nodes |
| `recall`    | `storymodel4s.recall`     | Recall units, discourse functions, recall relations, recall graph |
| `align`     | `storymodel4s.align`      | Local costs, unbalanced transport, graph-HSMM trajectory inference, recall signature |
| `interview` | `storymodel4s.interview`  | Transcript atlas, detail atoms, memory addresses, target-episode induction, AI-compatible scores, rich profile |
| `embed-core` | `storymodel4s.embed`     | Portable embedding contract (ADR 0001): `ProviderFingerprint` vs per-recipe `EmbeddingSpace`/`GeometryId` with validated `GeometryPair`, `EmbedBatch → BatchResult` with per-item outcomes and `AttemptReceipt`, `ValidatedVector`/`ValidatedDistance`, `SemanticView`, free deterministic baselines (hashed n-gram, TF-IDF), `EmbeddingCache` + keyed `SensitiveDigest`, `RemotePolicy → AuthorizedRemoteRequest`. Depends on `core`, `features`, `acquire`; providers are JVM-only adapters |
| `embed-grakern` | `storymodel4s.embed.grakern` | **JVM-only** structural channel (ADR 0001 §D4c): `PropositionChart` → grakern `LabelledNeighbourhood` by relation reification with explicit source/target incidence; WL subtree + optimal-assignment kernels; `structural.wl.grakern` spaces; `GrakernStructuralDistance` (`d_wl`) with memoized query rows and `ProviderCall` receipts. Consumes grakern by immutable SHA `ProjectRef` (`-Dstorymodel4s.grakern.build=<local checkout>` required until grakern is pushed); no grakern/graph4s type crosses into portable modules |
| `codec`     | `storymodel4s.codec`      | Canonical circe JSON codecs |
| `fixtures`  | `storymodel4s.fixtures`   | Hand-authored *War of the Ghosts* model, recall paraphrases, interview example |
| `laws`      | `storymodel4s.laws`       | Published Discipline law suites and ScalaCheck generators |

## Build and test

- Scala 3.7.4, sbt 1.12.14, sbt-typelevel 0.8.7.
- `sbt compileAll testAll` (all platforms) before declaring work complete;
  `sbt testJVM` for a fast loop. The JVM-only `embed-grakern` project needs
  `-Dstorymodel4s.grakern.build=/path/to/grakern` (or `STORYMODEL4S_GRAKERN_BUILD`)
  until grakern is published; it is part of `compileAll`/`testAll`/`testJVM`.
- Warnings are errors in spirit: keep `-Wunused:all -Wvalue-discard` clean.
- munit + munit-scalacheck at test scope; law suites in `laws` use discipline-munit.
- `Test / parallelExecution := false`.
- **sbt does not build in a linked git worktree.** sbt-git's jgit backend raises
  `NoWorkTreeException` (or `MissingObjectException`) when `.git` is a file
  pointing at the parent repository, which is how `git worktree add` sets things
  up. This has cost several sessions time. Two workarounds, both proven here:
  export the exact commit and build that — `mkdir -p $DIR && git archive <sha> |
  tar -x -C $DIR` — which is also what the chief's gate does so evidence is
  bound to a SHA rather than a dirty tree; or build in a standalone clone. A
  `zz-worktree-local.sbt` shim pinning `git.gitUncommittedChanges := false` and
  friends works for some tasks but is not reliable for all of them.

## Design contract (non-negotiable)

1. **No single-vector core.** Embeddings are sidecar feature views; they never
   participate in identity, equality, or graph structure.
2. **Typed, not stringly typed.** Relation, role, context, status, and modality
   families are Scala 3 enums; unknown ontologies enter via explicit
   `Custom(namespace, label)` cases, never raw strings.
3. **Evidence first.** Every accepted explicit claim cites `SpanSet` evidence;
   inferences cite upstream claims. `Credence.rawScore` is never a probability
   unless a `calibrationModel` is recorded.
4. **Contexts are mandatory.** Every situation lives in a `ContextFrame`; the
   root is `NarratedWorld`, not "objective truth". Reported/believed/intended
   content never becomes root-world fact by default.
5. **Discourse time ≠ story-world time ≠ recall time.**
6. **Partial, unbalanced, hierarchical alignment.** Recall may omit, merge,
   split, reorder, revisit, elaborate, or go external. External destinations
   are explicit states, not error residue.
7. **Truth ≠ salience ≠ phenomenology ≠ veridicality.** Never derive one from another.
8. **Smart constructors + phantom states.** Invalid states are unrepresentable
   when rules are stable (`Checked`/`Unchecked`, `Draft`/`Validated`);
   validated/versioned data when the ontology is open (PropBank frames).
9. **Sparse.** No dense all-pairs allocations in core paths.
10. **Deterministic IDs and receipts.** Content-addressed IDs; builds are diffable.
11. Core depends only on cats-core / cats-collections. No HTTP, LLM, ONNX,
    JVM-only APIs, or graph DB in portable modules.
12. Prefer `Either[DomainError, A]` / `ValidatedNec` over exceptions.
13. **Unattended builds are P0.** No human, AMR expert, or annotator is in the
    loop of a build. Agents return typed proposals with evidence; only the
    deterministic resolver creates `Resolved`/accepted claims; unresolved and
    alternative outcomes are legitimate artifacts, never forced precision.
14. **Fixture policy.** (a) Standards-conformance gold for AMR comes only from
    published guideline examples (or licensed corpora in authorized envs).
    (b) Project AMR/charts for stories are machine-generated *silver* with
    receipts. (c) The *War of the Ghosts* fixture is a researcher-reviewed
    **narrative acceptance fixture** expressed in narrative types and
    plain-language expectations — never hand-authored AMR.

## Coordination and governance

Several agents (Claude and Codex sessions) work in this checkout and its
worktrees at once. Coordination runs on the package-local mote board
(`.mote/`; `mote board`, `mote in-flight`, `mote discuss unread`). Governance
was set by the owner on 2026-08-28 (sticky decision
`post-01M14Y4D3QE7PKRDXTTT1H0PKM` on topic `coordination`):

1. **Chief architect.** `claude-storymodel4s` is the single accountable
   coordinator. It assigns, scopes (exact paths + test gate), prioritizes, and
   closes beads, and holds ADR authority. Claim only beads assigned or
   explicitly offered to you; propose new work as a bead on `coordination`.
   Closing a bead needs the assignee's evidence post (tests + SHA) and the
   chief's ack.
2. **Design.** ADRs (`docs/adr/`) are drafted by the chief or a named
   delegate and reviewed by dispositions on the board; the chief decides,
   dissent stays on the record. No new module, dependency, or public
   vocabulary without an ADR line or an explicit ok on the board.
3. **Merge gate.** Nothing lands on `main` — including worktree merges by any
   session — without a posted candidate SHA, `compileAll` + `testAll`
   evidence (all three platforms for portable modules), and the chief's ack in
   the thread. `codex-storymodel-release` performs the mechanical push to
   GitHub. Commits are narrow: only the paths reserved under your bead.

   *Ancestry.* Before merging, verify the candidate's parent chain contains
   only landed commits: `git merge-base --is-ancestor <unlanded> <candidate>`
   must exit nonzero for every unlanded candidate. A tree-equality check on the
   touched paths cannot see an unlanded — possibly blocked — commit sitting in
   the parent chain, which is how `cf162a9` was landed and `main` had to be
   reset on 2026-08-29. *Unlanded* means **not an ancestor of `main`**: a commit
   already in `main`'s history is landed by definition and needs no check, even
   if it never passed through a merge (docs committed directly under a live
   reservation, for instance). The rule guards the reverse case — a parent chain
   containing something that is not yet in `main` and might never be.

   *Stale bases.* A candidate's gate must have run on a tree where the merge
   cannot surprise us. If the candidate and the upstream commits its base is
   missing touch the **same file**, the author rebases and re-gates — not
   negotiable. If they are in **different modules**, the chief merges locally
   and gates the merged tree himself rather than charging the author a rebase,
   and backs the merge out if it is red.

   *Sibling seam.* `storyatlas4s` is a sibling repository that builds
   `storymodel4s` from source (`-Dstoryatlas4s.storymodel4s.build`), so it
   inherits every dependency edge added here. Whenever `main` moves in a way
   that touches anything it consumes — a new inter-module `dependsOn`, a change
   to `core`/`view`/`features` — compile and **run its test suite** against
   it. This is **part of the gate, not a follow-up**: for a candidate touching a
   consumed module, the seam check runs *before* the ack, exactly like the test
   run. Landing first and checking after is how the parity landing `04fdf6b`
   shipped a `SelectionPlacement[+Mark]` signature that broke the sibling's app
   module — a break no storymodel4s gate can see, because our tests cannot
   compile a separate repository. The two repositories keep separate
   mote stores (relative reservation paths like `build.sbt` would otherwise be
   ambiguous across them); cross-repo work is coordinated on the
   `narrative-atlas-intaglio` topic with provider/consumer SHAs recorded as
   notes, never as dependency edges between stores.
4. **Evidence discipline.** A passing test suite is not evidence; it is the
   absence of one kind of counter-evidence. Three checks, each earned by a
   defect that a green suite did not catch:

   *Mutation proof.* If a candidate adds a guard, delete the guard, show a test
   goes red, restore it. "The guard exists" and "the guard is load-bearing" are
   different claims.

   *Capacity to fail.* Assert the preconditions of a test's own meaningfulness
   before asserting content — that the probe set is large enough, the corpus
   non-empty, the rendering non-trivial. A leak canary that probed whole
   sentences missed a six-word slice straddling two of them; a metric that is
   always `Missing` passes a no-failures check forever.

   *Estimand check.* For every number the code publishes: name what it claims to
   measure about the subject; say where **our own uncertainty** goes — numerator,
   denominator, abstention, or silently nowhere; and ask whether that number
   moves in a consistent direction when our uncertainty correlates with a
   property of the subject (vagueness, disorganisation, length). A yes to the
   last is **P1 by default**: it can manufacture a group difference that is not
   there. Unresolved placement scored as an external detail, unranked mass
   summed into `externalMass`, and absent admissibility scored as gate
   compliance were all found this way, and none failed a test. Note also that
   the naive repair — dropping the uncertain observations — replaces one bias
   with its mirror image: carry the uncertainty through the arithmetic instead.

   *Sweep the shape, not the spelling.* When you find one instance of a defect
   class, sweep for its **shape** across the codebase, not its literal text. A
   fix for a chronology default missed an identical defect nine lines away
   because the search was for `.getOrElse`; sweeping four shapes instead — `if
   X.isEmpty then <literal>`, `.getOrElse(<numeric>)`, `math.max(1, n)` as a
   denominator, vacuous `forall`/`exists` in a scoring position — found the
   relation-preservation prior that defaulted to *perfectly preserved* and so
   biased the aligner itself, not merely a number computed from it.

   *Distinguishability.* Where a test separates a correct value from a specific
   wrong one, assert that the two differ by more than the comparison tolerance.
   Capacity to fail is not enough: inputs whose right and wrong answers fall
   within `eps` produce a test that passes under both the fix and the defect
   while looking rigorous.
5. **Actors and reservations.** One mote actor per session. Set your identity
   explicitly in every session — `export MOTE_ACTOR=<actor>` (and
   `mote session start --as <actor>`) or `--actor` on each call — and never run
   `mote actor set` in the shared checkout: `.mote/local/actor` is shared by
   every session in this directory, so changing it relabels other actors'
   posts. Reserve paths with
   `mote begin <bead> --paths …` before editing (`mote preflight` first);
   never edit another actor's live reservation — send a request instead.
   `build.sbt`, `AGENTS.md`, and `README.md` edits are announced on
   `coordination` before they are made. `docs/adr/*.md` are exempt from
   single-holder reservation for paragraph-disjoint edits: edit only the
   paragraph your bead names, announce it, include the hunk in your candidate;
   the chief resolves textual overlap at merge. Mote syncs commit with an
   explicit pathspec (`git commit -- .mote/ops`), never the whole index, and
   candidates stay unstaged in the shared tree until the chief's ack.
6. **Working together (owner directive, 2026-08-28).** Keep work clean and
   commit when it is safe and feasible — do not let green work sit
   uncommitted in the shared tree. Don't step on toes. Collaborate and
   discuss on the board: post design choices before they harden, read and
   answer each other, disagree with evidence. The goal: the best library in
   history for representing stories and their recall.
7. **Reporting.** Every active agent posts a check-in on `coordination` at
   each bead transition and at least hourly: bead id, state, blockers, next.
   Silence longer than two hours on a claimed bead means the chief reassigns
   it.

## Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Scaladoc on every public type with a one-line "why", not just "what".
- Tests: one law/invariant per property; adversarial fixtures for every
  prohibited failure mode in the design record (§27.2, §52.3).
