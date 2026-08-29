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
- Candidate authors run a **scoped gate**: `scalafmtCheckAll`, `compileAll`, and
  the test tasks for the modules the change touches, on all platforms those
  modules cross-build to. The full `testAll` court is the **chief's**, run once
  before a push — concurrent full courts on one machine were the cause of
  stalled and killed audits, not any one slow suite. `sbt testJVM` for a fast
  loop. The JVM-only `embed-grakern` project needs
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
   **A private constructor on a `case` class is not a boundary** (see
   `docs/design/unforgeable-types.md` for the criterion and the working pattern). Scala 3 gives
   every case-class companion a public `fromProduct` through `Mirror.Product`,
   and marking the constructor private does not remove it. That is the whole
   defect **only when the constructor is bare `private`**. A qualified
   `private[x]` constructor gives `copy` the same `private[x]` access, so `copy`
   still exists and is callable from anywhere inside `x` — and `copy` is the
   more dangerous door, because it is idiomatic: `valid.copy(field = bad)`
   produces an invalid instance from a valid one, and in-package code does that
   without thinking. Measured: 54 of the repo's private-constructor case classes
   are bare `private` (no `copy` emitted); **25 are `private[x]` and do emit
   `copy`**, across ten modules. Verified with a control — `PopulationAggregate`
   and `SensitiveDigest` (bare) have zero `copy` methods; `CheckedSidecarPrelude`,
   `RemoteCapability`, and `AlignmentRow` (qualified) each have one.
   `unapply`, the positional accessors, and `Product` membership are read-only
   and expose only what the public accessors already do, so do not close them —
   **unless the type hides a field**, below. **The
   exception is a type whose purpose is to hide a field**, where `_1` hands out
   exactly what the type promised to gate; such a type must not be a case class
   at all, because every *read* door has to close too. That case is rarer and
   strictly worse: a forged instance announces itself as invalid at the next
   validation, a leaked gated value announces nothing, ever. (**One instance exists on `main`**, found only after the
   first sweep's filter was corrected: `CheckedSidecarPrelude` declares
   `private[codec] val blockDigests`, and `javap` confirms a public `_2()`
   returning it. The first sweep reported zero because its exclusion filter
   dropped every class that *also* had a private constructor — which is nearly
   all of them. The mechanical shape is
   greppable; a *public* field that should have been gated is a judgement, and
   is yours to raise.) A validating type must be a `final`
   **non-case** class with explicit accessors, structural `equals`/`hashCode`,
   an intentional `toString`, and construction private to its smart
   constructor. Prove the boundary from *outside* the defining package: a probe
   inside the package cannot fail on private-scoped access, so it cannot
   distinguish a closed door from an open one. Ceiling to state honestly: Scala
   privacy is compiler-enforced, not JVM-enforced — the constructor is public
   in bytecode — so this buys soundness for Scala consumers, not for Java ones.
   **This binds new types, not only old ones.** The sweep is a floor, not an
   event: a cleanup that runs once loses to a codebase that keeps growing. On
   2026-08-29 slice 2 was removing forgeable construction from `Credence`,
   `TextSpan` and `StorySource` in the same hours a new candidate introduced it
   in a fresh public type (`SurfaceDetailSupport`, a `final case class ...
   private` whose three fields stand in a derived proof relation). So: **any new
   public type whose fields encode a relation the constructor is supposed to
   establish must be born unforgeable** — it is not enough to audit what exists.
   Note what was and was not at risk there, because the distinction is the whole
   skill: the *compiled* path was safe, since the compiler recomputed the proof
   rather than trusting the value; the *public preflight contract* was not,
   because a caller could mint a report claiming a capability the inspector
   would have refused, then serialize or display it. And note that the candidate
   was correctly author-gated and mutation-tested when the defect was found. Both
   facts hold at once. A gate proves what code **does**; forging is not a
   behaviour the code performs, so neither a green suite nor a mutation score can
   see a missing refusal at a construction boundary. That is why the static pass
   runs *alongside* the gate and not downstream of it.
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
    (d) **No story text, recall transcript, or excerpt enters this repository
    until it passes `docs/design/story-text-admission-checklist.md`**, whose
    answers live in the text file's own header and are checked by someone other
    than the proposer. Participant recall text is barred outright until the
    owner records an REB basis for redistribution; pseudonymization is a
    technical control, not consent.

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
   session — without a posted candidate SHA, scoped-gate evidence (see *Build
   and test*: `scalafmtCheckAll`, `compileAll`, and the touched modules' tests
   on every platform they cross-build to), and the chief's ack in the thread.
   The chief gates the merged tree and runs the full court before a push. `codex-storymodel-release` performs the mechanical push to
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

   *A conflict-free merge is not a working merge.* Textual non-conflict says
   nothing about whether the types still agree. When two candidates touch the
   same file, run the affected module's suite on the **merged** tree before
   landing — regardless of whether either base is stale. Observed twice on
   `SignatureSuite.scala`: two actors appended to different regions while the
   types underneath moved, `git` reported a clean merge and then a clean rebase,
   and both results failed to compile with the same two errors.

   *Reference scope.* The gate must cover **every module that references the
   changed type**, not only the module that contains it. Find them
   mechanically — grep the type name across `*/src/main/scala` — and name them
   in the evidence post. `compileAll` catches a broken signature; it does not
   catch a law that still compiles and now asserts something different, which
   is what a change to a receipt's identity or canonical ordering does to
   `laws/Laws.scala`. Observed on the population reference-shape candidate:
   author evidence was `alignJVM` only and the chief's gate was `align` on
   three platforms, while `Laws.scala` calls `PopulationAggregate.of` in ten
   places and asserts on the very receipt fields being reshaped — neither of us
   ran `laws`.

   *A stacked candidate has the union of its commits' reference scopes.*
   Computing the scope for the headline commit is not computing it. Take the
   union across **test** sources as well as `main`: `compileAll` does not compile
   tests, so a break living in a test generator is invisible to it. Observed on
   a five-commit stack whose signature changes implied `align` + `embed-bench`
   (gated) while a recall change in the same stack implied eight modules
   including `codec` (not gated) — the author found that break, not the chief's
   gate. Bundling a stack into one gate is right for machine load and invites
   this error; the bundling is not the mistake, failing to take the union is.

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
4. **Evidence discipline.** *Thirteen sub-rules; find yours here.* **What counts
   as proof of a fix:** mutation proof · capacity to fail · distinguishability · a negative
   compile-time assertion needs a positive control ·
   a fixture must tell the hypotheses apart · an inequality is not a
   discriminating assertion. **How to run the gate:** never pipe a gate · a
   compile-time probe needs a clean recompile. **How to scope a finding:** sweep
   the shape not the spelling · sweep your own work first · trace the
   consequence. **What a number may claim:** the estimand check · no value and
   low support are different failures · dropping a term from a normalized
   aggregate rescales the rest. *(Five of the thirteen are about whether a test
   can actually fail, and all five were added after a mutation survived — mostly
   one lesson, learned five times.)*

   A passing test suite is not evidence; it is the
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
   denominator, vacuous `forall`/`exists` in a scoring position — surfaced
   several more instances that a string search had missed. (The sweep's most
   dramatic hit, a relation-preservation prior said to bias the aligner, was
   **retracted**: the defaulting function has one caller and it is a test. See
   *Trace the consequence* below — that retraction is why this paragraph no
   longer cites it.)

   *Sweep your own work first.* When a review catches a shape in **your** code,
   sweep your own work for that shape **before** fixing the instance: the
   reviewer found one, you own the rest. The sweep instinct fires readily on
   other people's code, where a defect is a discovery, and poorly on your own,
   where it is a correction. Observed three times in five hours on one shape —
   case-class machinery defeating a private constructor — met once as a
   hand-written reflection filter, once as a reviewer's block, and only then
   generalized, with two more instances sitting two files away in the same
   commit. **This binds reviewers hardest.** A defect you ratify a one-site fix
   for is a defect you have declined to sweep; the chief did exactly that here,
   and 45 forgeable types stayed open until someone walked into one by hand.

   *No value and low support are different failures.* **No value** means the
   term cannot be computed and any substitute is invention — **refuse**. **Low
   support** means it *was* computed, from little evidence — **publish it with
   the evidence**, because refusing discards a real measurement. Most defects
   found here have been the first mistake, which primes everyone to see
   invention everywhere; the over-correction is a library that abstains whenever
   support is thin, and that is not more honest, it is differently dishonest —
   it withholds real evidence and leaves the researcher with nothing. Where an
   aggregate's support can only be computed over *some* of its terms, report the
   minimum over the terms that carry support **and** the count of contributing
   terms that carry none: a support figure covering four of eleven terms is a
   different claim from one covering eleven.

   *Dropping a term from a normalized aggregate silently rescales the rest.*
   Excluding unplaceable mass from a distribution does not remove its influence
   — normalization redistributes it over the survivors, so a detail half of
   whose mass could not be placed starts contributing a **whole** detail's worth
   of counts. Scale the item's weight by the fraction actually retained, so
   counts track evidence rather than proportion. On the Birthday fixture the
   difference is 49.0 expected detail-counts renormalized against 33.7 honest: a
   third of the account's counted detail manufactured from mass nobody placed.
   **The dangerous part is that it looks like a fix** — it is the natural repair
   for over-counting, and it replaces one bias with its mirror image while
   appearing to remove it. Encountered twice: once in a chief-prescribed fix
   caught in review before it was written, once inside the very candidate
   correcting the bias it mirrors.

   *An inequality is not a discriminating assertion.* `!=`, `<=`, and bounds
   generally can be satisfied by **both** the correct implementation and the
   mutant, so a suite built on them records kills it never made. The fix is to
   **recompute the expected value independently** — a different expression over
   the same inputs — and assert equality against it. Observed on mass-weighted
   fidelity: `conditioningMass != unitCount` and `conditioningMass <= sourceMass`
   both passed under the mutation, because the surviving-unit count was 2.0 and
   the source mass 2.63, an inequality that happens to hold. The assertion that
   kills it recomputes the denominator as the mass of every anchored state whose
   assessment specified at least one facet. Its author reports this as the sixth
   such survival in one session.

   *A fixture must be able to tell the hypotheses apart.* When you replace one
   formula with another, the fixture has to be one on which the two **disagree**
   — otherwise the mutation survives, the suite is green, and the evidence post
   records a killed mutant that was never killed. Observed on the compression
   estimand: reverting ratio-of-sums to mean-of-ratios survived, because the
   assertions checked only that the value abstains without conditioning mass,
   which *both* formulas satisfy; the two diverge only when units carry
   **unequal** source mass. The killing assertion is that the conditioning mass
   equals the summed source mass and not the row count (2.629, not 4.0). This is
   protocol law **I6** — a set must be able to falsify the model — applied to
   unit-test fixtures rather than benchmark corpora.

   *Never pipe a gate.* In a shell pipeline the exit status is the **last**
   command's, so `sbt -batch "..." | tail` reports `tail`'s status — which
   succeeds even when the build fails — and a line-limited pipe silently
   discards most of the evidence. Redirect the whole run to a file, report the
   unpiped command's own exit status, and `grep` the file for the summary. An
   `exit 0` in an evidence post means nothing unless the command producing it
   was unpiped. (Caught here by comparing against a previous run: three
   `Passed:` lines became one, for candidates differing by a trimmed test file.
   Keep the prior gate's numbers visible for exactly this reason.)

   *A negative compile-time assertion needs a positive control.* `typeChecks`
   returns `false` if the snippet produces **any** error and does not say which,
   so `assert(!typeChecks(...))` can pass for a reason unrelated to the property
   under test — and keep passing after a mutation that should break it. Put a
   positive control in the same file and scope: assert `typeChecks` is **true**
   for a type that certainly has the property. If the control fails, every
   negative assertion beside it is meaningless. **The control must match the
   shape under test**, not merely be some type with the property — a control
   that differs structurally can fail for an unrelated compiler-shape reason and
   tell you nothing. Observed on slice 5: only a control matching a *live
   private-constructor case class* showed the probe could see the actual
   `fromProduct` door. Use `typeCheckErrors` to read
   the actual message when diagnosing. (Same shape as the `copy` control:
   "`PopulationAggregate` has zero `copy` methods" proved nothing until
   `SubjectAlignment` in the same compile showed five. An absence proves nothing
   without a present case beside it.)

   *A compile-time probe needs a clean recompile.* A mutation that changes a
   **type's shape** — `case` to non-`case`, a constructor's visibility, or
   anything a compile-time assertion inspects — must be proved after a **clean**
   recompilation of the module. `typeCheckErrors` and friends are compile-time
   macros, and Zinc may reuse stale test bytecode across such a mutation: the
   probe never re-runs, the suite goes green, and the author records a killed
   mutation that was never tested. A false green in the exact test whose job is
   to prove the boundary holds. State in the evidence post that the recompile
   was clean; "mutation killed" without it is not accepted for a compile-time
   probe. (Chief gates satisfy this by construction — they run from a fresh
   `git archive` export with no target directories — so the hazard belongs to
   authors gating in a warm worktree.)

   *Trace the consequence.* A claim about what a defect **feeds** — what depends
   on it, what it corrupts downstream — is a claim about the call graph, and the
   call graph is cheap to check. Do not escalate a consequence you have not
   traced. A finding that matches an already-confirmed pattern needs **more**
   verification than one that does not, not less: it arrives feeling
   pre-validated, which is exactly when the check gets skipped. This rule exists
   because the chief escalated a pattern-matching finding to P1 above all other
   work without running one `grep` for its callers.

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
