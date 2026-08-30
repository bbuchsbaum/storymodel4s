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
   **THE CLASS THIS FILE IS MOSTLY ABOUT, NAMED ONCE: a value that cannot be told
   apart from a different value it must be told apart from.** Eight instances were
   found on 2026-08-29 alone, all by people reading code, none by a suite going
   red — because a suite cannot detect what is indistinguishable BY CONSTRUCTION:
   an imputed `0.5` identical to a measured `0.5`; `relativePosition` mapping
   absence to `0.0`, a real position; `None` and `Some("")` sharing one
   fingerprint; a schema version that did not move when a required field was
   added, so two incompatible formats shared a tag; `supportWeight` defaulting to
   `1.0`, making "unmeasured" identical to "fully measured"; a tolerance admitting
   a value and then storing the unabsorbed one; `Unknown` used for a field the
   primary record establishes but our bundle omits, conflating *we do not know*
   with *we did not fetch*; and a PENMAN alignment marker whose token set depends
   on an undeclared dialect. **When you find the ninth, you will not recognise it
   from any single rule below — recognise it from this sentence.** The test is
   always the same: name two things that ought to differ, then ask what published
   field differs between them. If the answer is none, it is this.
   **A DEFAULT EPISTEMIC STATUS IS A FABRICATED LICENSE.** An unmeasurable value
   given a plausible number is the defect this file is mostly about; an
   unestablished *status* given a confident one is its deeper form. A weight says
   how much; a status says **what entitles us to say it at all**. Measured
   2026-08-29 in the surface-to-narrative compiler: every accepted
   `CausalProposal` was materialised as `EpistemicStatus.LinguisticallyEntailed`
   while `CausalProposal` carries only a `CausalRelation` — so nothing in the
   provider schema or the compiler established that the relation follows from
   linguistic form, and `CausalEdge`'s own contract says precedence never
   licenses causation and `meta.status` records *how* it was licensed. Found by a
   second reviewer reading code, after the chief had certified the governing
   requirement as met from a prose checkpoint. **When you cannot establish the
   license, take the conservative truthful status**, and file the typed basis as
   its own bead rather than widening the slice.
   *(The chief's original requirement said "never present at a default WEIGHT".
   That was the units of the hour, not the rule. The binding form is never
   present with a default LICENSE.)*
   **And typing a conflated quantity does not un-conflate it — it launders it.**
   When one primitive is carrying two different meanings, wrapping every use in a
   single new type compiles, gates green, passes review, and preserves the exact
   conflation under a name that now *implies* semantics it does not have. That is
   worse than the primitive it replaced: a bare `Int` is honest, advertising that
   it carries no meaning, so a reader knows to go and find out; `Level` announces
   the question is settled. The fix is as many coordinates as there are meanings,
   never one wrapper over all of them. Live case (2026-08-29): `level: Int` spans
   `BoundaryEvidence`/`BoundaryScore` (task-conditioned perceptual grain) and
   `SegmentNode`/`descendantsAtLevel` (structural containment depth) — two
   estimands, one primitive. The sharpest site is
   `BoundaryBeliefInput.weights: Map[Int, …]`, because **a map key is a
   comparability claim**: two entries under key 2 are asserted to be the same
   level by the data structure itself, with no code having decided it. Note also
   that such a migration is **not sliceable**, unlike a per-type sweep: one
   surviving `Map[Int, _]` or codec field re-bridges the coordinates for
   everything downstream, so a half-done migration is not a smaller done one — it
   is an undone one that looks done.
   **Polarity decides whether a numeric guard fails open or closed — prefer the
   fail-closed shape.**

       if x <= 0.0 then SAFE else COMPUTE    NaN falls to ELSE → arithmetic on NaN.  FAILS OPEN
       if x >  0.0 then COMPUTE else SAFE    NaN falls to ELSE → SAFE.               FAILS CLOSED

   Same check, opposite outcome, decided entirely by which branch holds the safe
   path. `if x > 0.0 then compute else safe` is correct under `NaN` **for free** —
   no `isNaN` call, no cost — while the other form needs an explicit defence
   somebody has to remember. This sorts a codebase by grep, without per-site
   judgement: the fail-closed half needs no thought at all. **A NaN finding has
   THREE questions and they must be answered separately:**

   | question | what it asks | how you answer it |
   |---|---|---|
   | **polarity** | how the guard behaves under `NaN` | mechanical — sorts by grep |
   | **reachability** | whether `NaN` can arrive there at all | source tracing |
   | **consequence** | what actually comes out when it does | **must be run** |

   Measured 2026-08-29 on `sinkhorn.scala:44`: polarity predicted the validator
   fails open — correct. Reachability confirmed a public `SinkhornConfig` puts
   `epsilon = NaN` one keystroke away — correct. Running it published **no `NaN` at
   all**: the caller gets a silently degenerate all-zero transport plan after 200
   iterations, with `converged=false` returned, so a caller who checks can detect
   it. The measurer expected `NaN`, did not get it, and led with the contradiction.
   Stopping at polarity would have reported a `NaN` that does not occur; stopping at
   reachability would have missed the `converged=false` mitigation. **So polarity
   replaces only the fail-open/fail-closed judgement — it does NOT replace
   reachability, and neither replaces running the thing.** A
   fail-open guard on a value that cannot be `NaN` is not a defect, and the two
   questions must be answered separately. Measured 2026-08-29 across 19
   Double-literal guards, which found three fail-open *polarities* and exactly **one
   confirmed reachable defect**: `sinkhorn`'s config validator, where `SinkhornConfig`
   is a public case class passed straight to `solve`, so a `NaN` epsilon fails all
   three range checks and is ACCEPTED. Of the other two — `MassRatio.unsafe` admits
   `Some(NaN)` by polarity, but `MassRatio.of` explicitly rejects `NaN`/infinite and
   `unsafe` is `private[align]`, so reachability needs a production-path probe;
   `features/window.scala:122` is guarded upstream at :341, which rejects any sample
   failing `hasValidWeight` (finite and non-negative), so it is not reachable by the
   public path at all. `window.scala` does contain both polarities fifteen lines
   apart, which is the no-convention signature the empty-guard sweep found — but a
   mixed convention is a readability defect, not a live one.

   **A numeric guard must state what it does with `NaN` — every comparison against
   it is false, so `if x <= 0` FAILS OPEN.** Measured 2026-08-29: leaf importance had
   no finite/nonnegative boundary, and the sole degenerate-weight guard
   `if wsum <= 0 then Missing` did not fire for `NaN`, so `Estimate.observed` accepted
   the quotient and `importanceWeightedCoverage` published **`Observed(NaN)`** — a
   value asserting that a measurement was made and handing the consumer something no
   arithmetic can use, which is strictly worse than `Missing`. The author wrote a
   defence against degenerate weights and got one that admits the worst degenerate
   weight there is. Two consequences: validate finiteness **at the source** of any
   weight or score, because catching it at one consumer leaves the hole open for
   every other; and a type whose constructor advertises checked construction must
   **refuse** non-finite input rather than assume its upstream — a checked
   constructor with an internal unsafe path is making a false claim about itself.

   **And an empty-guard constant must declare its class.** `if xs.isEmpty then
   <k>` appears at 16 sites carrying FIVE different meanings, with nothing at any
   site saying which: *forced* (the guarded branch is never evaluated —
   `hsmm.scala:665`), *delete-sentinel* (the value is filtered out downstream —
   `StorySourceView.scala:236`, whose `0.0` is dropped by the next line's
   `.filter(_._3 > 0.0)`), *conservative-as-1.0* (a distance refusing to claim
   similarity — `lexicalJaccard`), *conservative-as-0.0* (a similarity refusing to
   claim one — `lexicalOverlap`), and *flattering* (`dSens` scoring absence as a
   perfect match, `dEnt` as mid-agreement). **Do not unify the constants** —
   `lexicalJaccard`'s 1.0 and `lexicalOverlap`'s 0.0 are both correct, one being a
   distance and the other a similarity. Unifying them would break working code to
   satisfy a pattern. Require instead that each site SAYS which class it is; three
   of the five are then cheap to review and only *flattering* needs argument. This
   is why two adjacent methods on one object (`SoftCompatibility.frameOverlap`
   returning 1.0, `argumentAgreement` returning 0.0, both higher-is-better) can
   disagree with nobody noticing: neither states an intent, so there is nothing to
   contradict.

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
   **A sweep for defeated boundaries cannot find a MISSING one.** The
   `fromProduct` population is private-constructor case classes — types that
   *tried* to be boundaries and were defeated by `Mirror`. A type with no
   boundary at all has no privacy to defeat, so it appears in no such sweep, and
   tightening that sweep's criterion pushes it further out of view. The
   complementary population has a greppable signature: **a validator whose return
   type is its own argument type**, on a type that is publicly constructible.
   `def validated(g: RecallGraph): ValidatedNec[DomainError, RecallGraph]` says,
   in the type system's own words, *I checked this and I have no way to tell
   you* — nothing downstream can distinguish a checked value from an unchecked
   one, and `copy` on a validated instance produces an unvalidated one silently.
   Measured: 13 validators wear the signature; of the nine types resolved, **eight
   are public case classes** (`TranscriptAtlas`, `SurfaceAtlas`,
   `LatePoolingRecipe`, `SidecarManifest`, `FeatureDerivation`, `InterviewSource`,
   `RecallGraph`, `PromptPackageManifest`), four of them in `core` and `features`.
   `ClaimMeta` is the control — identical signature, **not** a defect, because a
   sweep slice gave it a private constructor, which is also the proof that the fix
   moves a type from the defective population into the safe one. The signature
   alone is not the defect: for a private-constructor type, returning its own type
   is correct, since the validator is then the only way to obtain one. Always
   cross-reference against constructibility before filing.

   **This binds new types, not only old ones.** The sweep is a floor, not an
   event: a cleanup that runs once loses to a codebase that keeps growing. On
   2026-08-29 slice 2 was removing forgeable construction from `Credence`,
   `TextSpan` and `StorySource` in the same hours a new candidate introduced it
   in a fresh public type (`SurfaceDetailSupport`, a `final case class ...
   private` whose three fields stand in a derived proof relation). So: **any new
   public type whose fields encode a relation the constructor is supposed to
   establish must be born unforgeable** — it is not enough to audit what exists.
   *The non-ambiguous trigger is the CARTESIAN-PRODUCT TEST* (codex-storymodel-collab,
   2026-08-30): if every combination of individually lawful field values is a lawful
   value of the type, it is honest product data and a public case class is fine; if
   some combination is false because validity depends on a relation among fields,
   provenance, ordering, identity, or external context, it carries a joined claim and
   must not expose product construction. That replaces "supposed to establish" — which
   asks about author intent — with a question anyone can answer about the type alone.
   *And a private constructor is NECESSARY, NOT SUFFICIENT:* the checked factory must
   accept enough context to PROVE the relation, and a court must kill removal of that
   check, or you have total construction over the wrong domain behind a prettier door.
   Courts compile-refuse **four** doors — `apply`, `copy`, the companion's
   `fromProduct`, and `summon[Mirror.ProductOf[T]].fromProduct` — each needing its own
   same-shape positive control, because a control for one mechanism does not license a
   refusal in another. Validated before it was written: stated as a proposal on
   2026-08-30, it produced three live findings within the hour (`CellCoordinates`,
   `CachedParserProposal`, and a `CacheHit` admission bypass an author found in their
   own candidate and self-held).
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

   *`presence` is a lease, not a heartbeat.* `live` means an actor renewed a
   session lease; `expired` means a TTL elapsed. NEITHER MEASURES WHETHER ANYONE
   IS WORKING. Before choosing a reviewer, reassigning work, or concluding that
   someone is gone, read `last=` and their recent ops — not the presence word.
   Measured 2026-08-30, twice in one day and in both directions: an actor was
   named as a substitute reviewer on the strength of `presence=live` while being
   the LEAST recently active of the candidates (3h41m silent against another's
   2h); and `claude-storymodel4s-m1` was written off as "not coming back" on
   `presence=expired` thirty-five seconds after their last op, having merely let
   a short lease lapse — they returned and cleared three rows. The failure runs
   in both directions and the correction is the same: **a status word about a
   lease is not evidence about a person.** An `orphaned` reservation is the same
   error in a different field — it means the work behind the hold resolved, not
   that the holder is absent, so ask them to release rather than reaching past
   it.
2. **Design.** ADRs (`docs/adr/`) are drafted by the chief or a named
   delegate and reviewed by dispositions on the board; the chief decides,
   dissent stays on the record. No new module, dependency, or public
   vocabulary without an ADR line or an explicit ok on the board.
3. **Merge gate.** Nothing lands on `main` — including worktree merges by any
   **The chief must not close a bead holding another actor's live reservations.**
   `mote done` closes and releases together, but the release half is owner-only —
   so it is unavailable to the chief on someone else's holds. `mote close` is
   available and releases nothing. `mote unreserve` refuses outright. **The only
   close available to a chief is the one that orphans**, which makes this a
   structural failure mode rather than carelessness: landing a candidate and
   closing its bead in one tidy motion is exactly how it happens. Before closing
   another actor's bead, run `who-has` on its paths; if anything is held, ask the
   holder to `mote done` it instead. (Recorded 2026-08-29 after the chief orphaned
   two reservations this way, twenty minutes after advising a different agent to
   prefer `done` for precisely this reason, and blocked a third party who could not
   clear them either.)

   **Mint your own child bead under a scoped parent.** If the chief has posted a
   scope in public — slices A–E of a sweep, say — and you volunteer for one, create
   the child bead yourself, claim it, reserve, and start. Do not wait for the chief
   to mint it. The scoping was the decision; minting afterwards is clerical and
   serialising it makes the chief a bottleneck on a step that carries no judgement.
   (Added 2026-08-29 after an agent sat idle thirty minutes waiting for a bead on a
   slice that had already been scoped in a board post.) **What stays with the chief
   is the merge gate itself** — the merged-tree run, the captured exit status, the
   branch check, the landing — not because the chief runs sbt better, but because
   the gate's value is concentrated in the checks that are *about* someone else's
   work: the tree recompute that catches `main` moving under a candidate, the
   mutation court that turns "these probes look weak" into "these probes are live",
   the narrowing that separates bookkeeping churn from a real conflict. Those are
   cheap for a disinterested party and awkward for an author.

   **Verify the branch before you merge.** `git rev-parse --abbrev-ref HEAD` must
   read `main` before any `git merge` of a candidate. Nothing warns you otherwise:
   `git merge` succeeds identically on the wrong branch, and the only symptom is
   that `main` does not move. Measured 2026-08-29: a candidate merge landed on
   another agent's slice branch, which someone had checked out in the shared
   checkout, minutes before they gated it — the gate would have PASSED and carried
   106 unattributed lines into their candidate. Caught only because the landing
   routine prints `main`'s SHA afterwards and it had not changed. **Print the thing
   you are trying to change, not the command's report of itself.** Repairing such a
   mistake: check the working tree FIRST (another agent's uncommitted reserved work
   may be in it), switch **unforced** so git refuses rather than clobbers, and move
   the branch pointer back with `git branch -f <branch> <their tip>` — never
   `reset --hard`. Corollary: branch switches in the shared checkout change HEAD for
   everyone, so use a worktree.
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

   *Ledger phase is advisory; git is authoritative.* Before treating any
   candidate as outstanding — to work it, review it, report it as blocked, or
   cite it as a hold — run `git merge-base --is-ancestor <candidate-commit>
   main`. Exit 0 means **the work is landed** and the ledger row is a recording
   artifact, not a hold. This is the mirror of *Ancestry*: that rule asks
   whether a candidate's parents are landed, this one asks whether the candidate
   itself already is. Measured 2026-08-29: four of six candidates carried
   `ancestor_abandoned`, and of the three showing `pending blocked`, **two were
   already on `main`** (`e022c5f`, `7ae2ec9`) — including an audit the chief had
   been citing all evening as a landed result while the board called it blocked.
   A `pending blocked` row cannot distinguish *not yet landed* from *landed,
   recorded late*, so reading the board without this check reports finished work
   as a backlog and gets people assigned to it. `mote board` already prints
   *[advisory: read from git, not from replayed state]* over RECENT COMMITS; the
   same staleness applies to CANDIDATES, where the consequence is larger.
   Corollary for the chief: when a state machine mislabels one candidate it
   mislabels every candidate in that position — **rule on the class, not the
   instance**, and the first question about any reported block is *how many*.

   *Recording a landing is half of landing it, and the unrecorded half rots.*
   The rule above tells a reader how to compensate for a stale row; this one says
   do not create it. `git merge` and `mote candidate landed` are two acts, and
   deferring the second to the end of a tick is how 2026-08-30 produced a queue
   that was 42% bookkeeping: **five of twelve pending candidates were already
   ancestors of `main`**. The cost is not cosmetic. An unrecorded landing keeps
   its author's reservations alive: `cand-7SRW` held `cost.scala`, `Laws.scala`
   and eight other paths under `codex-storymodel-new-engineer` for an hour on
   nothing but a row nobody had written, and the reservation released within
   seconds of the record being made. **Record the landing before you report the
   merge**, not at the end of the batch.

   *A reason string phrased as a measurement may be a cached one.* Landability
   reasons are replayed from recorded evidence, but they are written in the
   vocabulary of live git — `ancestor_ambiguous: base relation is missing; tip
   relation is not_ancestor`. Measured 2026-08-30: that exact string was reported
   for `cand-4X710` while `git merge-base --is-ancestor 7ae2ec9 main` exited 0
   and the commit sat in `main` at `795fe12`. The ledger asserted a false git
   fact in git's own idiom, which is much harder to disbelieve than a phase
   label is. Never let a reason string stand in for the command; run the command.

   *Evidence is minted in a clone, and a clone cannot see an uncommitted op.*
   The store is tracked in git, so a producer's `.mote` is only as current as
   their last pull. Measured 2026-08-30: 641 ops sat untracked in the shared tree
   while three candidates — all with approving reviews and granted authorization
   — reported `git_evidence_stale` naming a proposal op from `13:23:12Z`. The
   producer minted a fresh receipt at `13:28:38Z`, five minutes later, and it
   still did not cover it, **because a receipt minted after a proposal cannot
   miss it by timing — only by not being able to see it**. Each new proposal then
   invalidated every outstanding receipt, and no producer could mint a covering
   one, so with six live actors the covered set could never close. **Commit
   `.mote` before asking anyone to re-mint.** When a producer reports stale
   evidence they cannot fix, suspect the store's visibility before suspecting
   their receipt — and note the asymmetry: the only actor who can clear it is the
   one holding the shared tree, and nothing in the error says so.

   *Stale bases.* A candidate's gate must have run on a tree where the merge
   cannot surprise us. If the candidate and the upstream commits its base is
   missing touch the **same file**, the author rebases and re-gates — not
   negotiable. If they are in **different modules**, the chief merges locally
   and gates the merged tree himself rather than charging the author a rebase,
   and backs the merge out if it is red.

   *A base is stale only if the delta can INFLUENCE the gate — the test is
   influence, not recency.* Let D be the commits in `main` since the candidate's
   base. The base is **gate-current** when no commit in D touches either a file the
   candidate touches or a build input the candidate's gate compiles (a `.scala` file
   in any gated module, `build.sbt`, `project/`). Commits touching **only** `docs/`,
   `AGENTS.md`, or `.mote/` are **gate-inert by construction** and never invalidate
   a gate. If the test were tip-currency instead, no candidate on an active board
   could ever be simultaneously gated and current: a gate takes minutes, `main`
   moves during those minutes, and the author rebases into the next gate forever.
   Measured 2026-08-29: an eight-minute cross-platform gate finished to find `main`
   two commits ahead, both documentation, one file each — and both authored by the
   two people reviewing that candidate. A rule letting documentation invalidate
   someone else's gate evidence charges authors for the reviewers' commit rate.
   Note the pairing with *Ledger phase is advisory*: there the board reported
   landed work as pending, here a base's recency reports an inert delta as a
   hazard. Both are a status that cannot distinguish *something changed that
   matters* from *something changed*.

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
4. **Evidence discipline.** *Thirty-three sub-rules; find yours here.* **What
   counts as proof of a fix (12):** a control must fail for the property under
   test · mutation proof · capacity to fail ·
   distinguishability · a negative compile-time assertion needs a positive control ·
   or kill it with a mutation, which is stronger · a fixture must tell the
   hypotheses apart · prefer a fixture derived from the pipeline over one
   synthesised at the consumer · check a fixture's inputs are in the form the
   pipeline produces · an inequality is not a discriminating assertion · a pinned
   literal can be updated but a behavioural assertion has to be argued with · a
   positive control requires the old code to be capable of the thing tested. **How
   to run the gate (14):** never pipe a gate · the gate is not the last step, re-read
   the board before merging · a check and the action it gates must
   not share a batch · a trailing success line is not an exit
   status · a compile error anywhere makes the gate inconclusive · run the format
   check last · verify the export before you gate it · a compile-time probe needs a
   clean recompile · a gate with no test totals did not run · a shell equality test
   over two failed command substitutions passes · a backgrounded gate's exit status
   describes the fork not the run · a shared-tree gate compiles other agents'
   untracked files · never head a list whose length is the finding · `>` inside `[ ]` is a redirect.
   **How to scope a finding (4):**
   sweep the shape not the spelling · sweep your own work first · reading a branch establishes permission
   not occurrence · trace the consequence. **What a number may claim (3):** the
   estimand check · no value and low support are different failures · dropping a
   term from a normalized aggregate rescales the rest. *(Twelve of the thirty-three are
   about whether a test can actually fail, and FOURTEEN are about whether the gate
   measured anything at all — that second group went from three to fourteen in a
   single day, every entry added after a run reported a status with nothing behind
   it, and the last of them after a run reported SUCCESS with nothing behind it.
   On 2026-08-29 alone, THREE separate red gates measured infrastructure rather
   than a candidate: a truncated archive, a linked worktree, and a directory name
   used as an sbt project id. This index is
   load-bearing and it ROTS: it was stale by five before 2026-08-29, was corrected
   that day, and was stale by four again within two hours because the same person
   who wrote "if you add one, re-count" added four without re-counting. Re-count
   mechanically; do not trust the number above without checking it.)*

   **A finding has a CHAIN, and reporting one link as the whole chain is the most
   common way work here has been wrong.** Four questions, each answered by a
   different method, each capable of overturning the last:

   | link | question | method |
   |---|---|---|
   | **permits** | can the code produce this? | read it |
   | **occurs** | does it actually happen? | measure the corpus |
   | **produces** | what comes out when it does? | **run it** |
   | **consumes** | who depends on that output? | trace forward |

   Measured against the chief's own rulings on 2026-08-29, all four caught by other
   agents: `trajectory.scala` was ranked worst-placed on a consumer that does not
   exist (*permits*, never traced *consumes*); the WOG segmenter override was sized
   as systemic when it fires for one paraphrase of ten (*permits*, never measured
   *occurs*); three NaN guards were called live when one is reachable (*permits*,
   never traced *occurs*); and that one reachable site publishes a degenerate
   all-zero plan rather than the `NaN` predicted (*occurs*, never ran *produces*).
   **Each correction moved one link further along a chain the chief had not
   walked.** Severity is a property of the whole chain: a defect that is permitted,
   occurs, produces garbage, and is consumed by a published figure is a different
   thing from one that stops at any earlier link. Say which link you reached.

   **The one principle under most of what follows: READ THE ARTIFACT, NOT THE
   STATUS.** A step that reports success while having done nothing — or part of a
   thing — is the single most common way work here has gone wrong, and it has
   appeared in four different tools in one day: `mote discuss post --body -` printed
   *"posted"* and stored a hyphen; `sbt console` with redirected input exited 0 and
   executed nothing; a `scalafmtCheckAll; compileAll; testJVM` chain returned an exit
   status with **zero tests** behind it; `git archive | tar` truncated by a full disk
   exited clean and left a directory that still looked like a project. In every case
   the status was green or explicable, and the artifact — the body length, the test
   totals, the log contents, the directory size — was obviously wrong the moment
   anyone looked. Several sub-rules below are this principle wearing different
   clothes. If you remember one thing from rule 4, remember to look at the thing that
   was produced rather than the report that it was produced.

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

   *THE GATE IS NOT THE LAST STEP — RE-READ THE BOARD IMMEDIATELY BEFORE THE
   MERGE COMMIT.* A gate takes minutes and reviewers work in parallel, so a hold
   can arrive INSIDE your gate window. Measured 2026-08-29: a reviewer posted an
   exact-SHA HOLD at 20:40:24 and the chief merged that candidate ninety seconds
   later, having checked the board BEFORE gating and not again after. The hold was
   correct and the defect went to main — `GraphHsmm.infer` returning `Left` on a
   lawful weighting, found by a runnable probe no test in the suite constructs.
   **A 950-test green gate is evidence about the code, not about the board.**
   Checking before you start tells you what was known then; the merge needs what is
   known now.

   *And re-read for what changes the CLAIM, not only for what withdraws
   PERMISSION.* The failure above is a hold arriving inside the gate window. On
   2026-08-30 the chief hit the same window with nothing forbidden: m1 posted at
   13:52:44 — addressed to the chief by name — that two ancestor rows had just
   been refreshed clean and "it changes what your merge commit should say"; collab
   posted the same correction independently at 13:54:13; the chief committed at
   13:54:51, having read the board before gating and not after. Nothing was
   blocked, the gate was green, every substantive claim in the message was true,
   and the merge was still WRONGLY DESCRIBED: it announced three overridden
   ancestor reasons when those reasons had already dissolved and the rows only
   needed attesting — which the chief then did anyway, forty minutes later. A
   scan for holds would have passed. **A merge commit makes claims, and the board
   can falsify a claim without revoking a permission**, so the question on the
   re-read is not only "may I still merge" but "is what I am about to write still
   true". Correct a shared commit's message with `git notes` rather than a rewrite;
   ad3f8c2 carries one.

   *A check and the action it gates must not run in the SAME BATCH.* Piping
   destroys the exit status; batching destroys the DECISION. If the format check,
   the commit and the push are one command sequence, there is no point at which the
   check's answer can change what happens next — you read the failure after the
   push has already landed. Measured 2026-08-29, by the chief, on a commit whose
   subject was "NormTolerance states the precision it defends": `scalafmtCheckAll`
   returned 1 and main took the unformatted file anyway, four minutes before the
   repair. The same batch also released the reservation, so repairing an unverified
   push required re-reserving a file already given up. **Run the gate, READ IT, then
   act** — and never release a hold in the same breath as the work it protects.

   *Never pipe a gate.* In a shell pipeline the exit status is the **last**
   command's, so `sbt -batch "..." | tail` reports `tail`'s status — which
   succeeds even when the build fails — and a line-limited pipe silently
   discards most of the evidence. Redirect the whole run to a file, report the
   unpiped command's own exit status, and `grep` the file for the summary. An
   `exit 0` in an evidence post means nothing unless the command producing it
   was unpiped. (Caught here by comparing against a previous run: three
   `Passed:` lines became one, for candidates differing by a trimmed test file.
   Keep the prior gate's numbers visible for exactly this reason.)

   *Verify the export before you gate it.* An export is an INPUT to the gate, not
   infrastructure that either works or crashes — it does neither. `git archive | tar`
   truncated by a full disk exits clean and leaves a directory that still looks like
   a project; sbt loads the partial tree happily, and every failure after that is
   attributed to the code under test. Measured 2026-08-29: a merged-tree gate
   returned `GATE_EXIT=1` with **311 error lines** on a sound candidate, because the
   export held 3 of 27 top-level directories. The tell was the FIRST error —
   `FileNotFoundException` writing a JUnit report XML, a shape no Scala defect
   produces. After extracting, assert the entry count and that `build.sbt` exists,
   before starting sbt. Two seconds, and it separates "the candidate is broken" from
   "I never gave the compiler the candidate."

   *Run the format check LAST.* sbt's `;` aborts the chain on first failure, so
   `scalafmtCheckAll; compileAll; testJVM` lets a whitespace nit destroy the
   correctness signal for everyone. Formatting stays IN the gate — nothing lands
   unformatted, and the captured exit status still covers the whole chain — but it
   belongs at the end. Measured on one candidate, same tree, order the only
   variable: format-first gave `GATE_EXIT=1`, 7 errors, **zero** test totals and no
   information; format-last gave the same exit status and the same 7 errors *plus*
   17 suite totals and 1355 passing tests, which said the candidate's logic was
   sound and only its whitespace was not. Same verdict, one of them actionable.
   Note this is the *inconclusive* rule above arriving through chain ORDER rather
   than through anyone's broken code — a rule you have just written is at its least
   useful when you believe you already comply with it.

   *A compile error anywhere makes the gate INCONCLUSIVE, not partially clean.*
   `compileAll` aborts before any test runs, so a green-looking partial log means
   nothing was measured — a runtime break in an unrelated module stays hidden
   behind it. Never report "module X passed" from a run whose compile failed
   elsewhere; the honest word is *inconclusive*, and the remedy is to fix the
   compile and re-run, not to read around the error.

   *And a trailing success line is not an exit status.* Redirecting to a file is
   only half of it; if you do not actually record the status, the log's last line
   is what you are left with, and that line lies in a specific way. sbt prints
   `[success] Total time: Ns` for **each command in the chain**, so a run killed
   after its last test suite ends in exactly the text a completed run ends in.
   Zero `[error]` lines do not close the gap either — a run that never reached a
   failing suite has none. Append the status to the log itself
   (`... > log 2>&1; echo "GATE_EXIT=$?" >> log`) and certify on that marker, not
   on the shape of the tail. Caught on slice 7: the first run showed 17 suite
   totals, zero errors and a trailing `[success]`, and was **unverifiable** —
   re-running it in the same export with the status captured gave `GATE_EXIT=0`
   and 1295 tests. Same result, but only the second one was evidence.

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

   *A control must fail when THE PROPERTY UNDER TEST is broken — not merely when
   something is broken.* "Able to fail" is necessary and not sufficient. A control
   that dies when you break something ADJACENT looks exactly like a control that
   works, and keeps looking that way until someone mutates the specific property
   by name. Measured 2026-08-29: a chart-eligibility candidate shipped a positive
   control that a reviewer killed with `val structuralEligible = false` — and THE
   TEST STAYED GREEN, because term production happens BEFORE eligibility, so the
   term was still present and the totals still differed. The test proved provider
   EXECUTION, not ELIGIBILITY. It was not vacuous; it discriminated something real,
   just not the thing it was named for, which is far harder to see than a test that
   always passes. **Reading the test cannot find this** — the assertions look
   correct and they are, about something else. Mutate the property BY NAME, and if
   the control survives, it is a control for a different property.

   *A positive control requires the OLD CODE TO BE CAPABLE of the thing being
   tested.* Where the fix INTRODUCES the capability, no positive control exists —
   only a regression guard, and the two must not share a name. Measured
   2026-08-29: the chief required a law stating that dropping an unmeasurable term
   must not change which state wins, and justified it as "today's code fails this,
   so it is a positive control rather than a hope." Today's code has NO ELIGIBILITY
   CONCEPT, so "drop a term that could not be measured" is not an operation the
   unfixed model can perform and no arm of it can fail. The engineer wrote the law
   twice — once VACUOUS (it passed with the scaling disabled, found only by
   mutating) and once TOO STRONG (it failed on the correct implementation, and
   should have: a unit that supplies a sensory term and gets it wrong ought to cost
   more than one that never claimed sensory content, and the law as specified would
   have made the term useless) — then reported that the specification could not be
   met instead of adjusting it until it passed. **Ship the regression guard and say
   in the test that it is one.** A regression guard wearing a positive control's
   name is how a suite acquires authority it has not earned.

   *Or kill it with a mutation, which is stronger.* The control is a PROXY: it
   asks "would this snippet compile if the door were open?" by compiling an
   equivalent snippet against a forgeable stand-in. A mutation asks the same
   question **directly, of the real type** — open the door and watch the probe go
   red. So a demonstrated mutation kill SATISFIES this rule and the control is
   the substitute for when you cannot mutate. **But a mutation kill counts only
   if the mutant COMPILES and the failure is the NAMED ASSERTION rather than a
   blanket red** — a mutation that breaks the module turns every test in it red,
   and "the probe failed" then carries no information whatever. The signature to
   report is the discriminating one: the mutant compiled, the sibling test in the
   same suite still PASSED, and the failure cites a line. One failed and one
   passed is evidence; all red is not. Measured on the three slice 7 phantom
   witnesses, whose only structural control was
   `summon[Mirror.ProductOf[(Int, String)]]` — a tuple, which always has a Mirror
   and so cannot fail for any reason connected to the type under test. Minimal
   mutation `final class` → `final case class`, clean recompile per module:
   AmrGraph → suite:27, StoryModel → suite:23, PropositionChart → suite:28, each
   1 failed / 1 passed / 2 total. Weak control, live probes — which is exactly
   why the mutation is worth running rather than reasoning about.

   *Check a fixture's inputs are in the form the pipeline produces before pinning
   a literal from it.* A pinned expectation is only as good as the shape it was
   computed from, and a fixture that feeds the code a shape production never
   emits produces real numbers that measure nothing. Three separate defects came
   from this one thing: unstemmed lemmas where production stems, text without the
   punctuation its span includes, and point masses in a module whose subject is
   distributed mass.

   *Prefer a fixture DERIVED from the pipeline over one SYNTHESISED at the
   consumer.* A synthesised fixture defaults to the easy shape, because the easy
   shape is what you reach for when the fixture is not the thing you are testing.
   A derived one inherits the real distribution whether you were thinking about it
   or not. Measured across two modules: `interview` tests call
   `Distribution.point` 20 times and `Distribution.of` 4 times — and three of
   those four are in `InterviewSuite` testing `Distribution.of`'s OWN validation
   (that it rejects zero and negative mass), leaving ONE that builds a real
   spread. `InterviewProfileSuite`, which scores every profile metric, had zero
   spread-mass cases. `align`, whose fixtures come from 8 `GraphHsmm.infer` calls,
   carries spread rows for free. So an entire module whose subject is DISTRIBUTED
   PLACEMENT was scored almost exclusively on point masses, and every defect that
   appears only when mass is spread was structurally invisible — not missed, but
   unseeable, and green the whole time. **When you must synthesise — and sometimes
   you must, to isolate — synthesise the AWKWARD shape**, because the easy one is
   what you will write by accident.

   *A gate that produced no test totals did not fail — it did not RUN.* A
   non-zero exit is not evidence about the code until you have found a test count
   behind it. Measured twice on 2026-08-29, both on sound candidates: a truncated
   `git archive | tar` under a full disk reported `GATE_EXIT=1` with 311 errors
   from 3 of 27 directories, and a gate run inside a LINKED GIT WORKTREE reported
   `GATE_EXIT=1` with zero totals because sbt-git's jgit call sees a worktree's
   `.git` FILE as a bare repository and project loading fails before compilation
   starts. Neither result said anything about the candidate; both looked exactly
   like a failing test suite. **Gate in a clone, never in a linked worktree**, and
   before you report a red gate, grep the log for `Passed: Total` — if there is
   none, you have measured your own infrastructure.

   *A gate in the SHARED TREE compiles other agents' untracked files, so its exit
   status is not about your commit.* `compileAll`/`testAll` build the working tree,
   not the index and not the commit — every untracked `.scala` another session has
   left in a source directory enters the run. A green result therefore certifies
   "the commit plus whatever else was lying there", and a red one may be somebody
   else's half-written file. Reported 2026-08-29 by an actor who ran JVM/JS/Native
   to exit 0 and **disclosed the run as non-exact** because untracked NFRD sources
   had entered it, rather than reporting a clean green — that is the standard. Check
   with `git status --short` for untracked sources under any module you are gating;
   if there are any, either gate in a clone or state the contamination in the
   evidence post. Exact-SHA evidence REQUIRES a clean tree, and this is a second
   reason the clone rule above is not merely about worktrees.

   *Never `head` a list whose LENGTH or COMPLETENESS is the finding.* If the question
   is "how many" or "is X absent", truncation MANUFACTURES the answer — and it
   manufactures the reassuring one, because what falls off the bottom is by definition
   what you did not see. Count first (`grep -c`), then display. Measured twice on
   2026-08-29 by the same person in one session: a contamination incident reported as
   **9** files under `git show --stat | head -12` when it was **13**, and eight hours
   later a candidate declared review-satisfied under
   `mote candidate show … | head -6` when `review_missing` was line **7** — which
   nearly merged two candidates on a review status never actually read. The second one
   happened AFTER the first had been written up as a rule, which is the argument for
   putting the mechanism here rather than trusting the memory of it.

   *The exit status of a BACKGROUNDED gate is the exit of the backgrounding, not of
   the work.* `nohup sbt ... &` returns 0 the instant the fork succeeds, so "the gate
   passed" and "the gate was successfully LAUNCHED" render as the identical value.
   Measured on 2026-08-29: a Native gate exceeded a foreground timeout, was relaunched
   in the background, and was reported COMPLETED, EXIT CODE 0 while sbt was still
   mid-optimisation — no totals, no `[success]`, zero errors, exit 0. The only evidence
   is a `Passed: Total` line PER MODULE plus a terminal `[success]`/`[error]`, and you
   must confirm the process has actually ended before reading either; poll the log for a
   terminal line rather than trusting the launcher's status. This is the sub-rule above
   in its most dangerous form: there, a bad exit code looked like a failing suite; here,
   a good one looks like a passing gate, which is the direction you are hoping for and
   therefore the one you will not question. Native is the platform slow enough to make
   backgrounding tempting, so this will be met there first.

   *A shell equality test over two command substitutions PASSES when both of them
   fail.* `[ "$(cmd_a)" = "$(cmd_b)" ]` compares two empty strings and succeeds, so
   a verification step reports agreement precisely when it learned nothing. Measured
   on 2026-08-29 in this repo's own gate scaffolding: a check that the merged tree
   equalled the candidate tree printed a confident `YES` while every `rev-parse` in
   it had failed with `cannot change to ...: No such file or directory`. This is the
   fail-open guard of rule 7 wearing shell syntax, and it is worse than the ones we
   hunt in Scala because nothing type-checks it. **Assert non-emptiness before
   comparing** — `[ -n "$a" ] && [ -n "$b" ] && [ "$a" = "$b" ]` — and prefer `set
   -o pipefail` plus explicit exit checks in any script whose OUTPUT IS A VERDICT.

   *`>` and `<` inside `[ ... ]` are REDIRECTS, not comparisons.* `[ "$x" > 0.0 ]`
   reduces to `[ "$x" ]` — true for ANY non-empty left operand — and silently creates
   a file named `0.0` in the working directory. So a numeric threshold written this way
   is a guard that CANNOT FAIL, and its only symptom is an unexplained file named after
   its own right-hand side. Verified 2026-08-30: `[ "5" > 0.0 ]` and `[ "0" > 0.0 ]`
   both return true and both create `./0.0`, while `awk 'BEGIN{...}'` correctly reports
   `0 > 0.0` as false. Diagnosed by claude-storymodel4s-m1 after a zero-byte `0.0` sat
   unexplained at the repo root for six hours, visible in every `git status` any of us
   ran. Use `awk 'BEGIN{exit !(a>b)}'`, `bc -l`, or `[ "$x" -gt "$y" ]` for integers.
   Third member of this family, with the two above: a shell construct that looks like a
   test, is not one, and fails toward "pass".

   *A pinned literal can be updated; a behavioural assertion has to be argued
   with.* When a model change moves a number, a test pinned to that number tells
   you only that it moved — and re-pinning is the normal, correct response, which
   is exactly the problem: re-pinning an improvement and re-pinning a regression
   are the same edit. A behavioural assertion states the CLAIM rather than the
   value (`"'Stephen King' goes to the Association external state, not to a source
   node"`), so it cannot be re-pinned — making it pass again means writing down
   something false, which is a decision instead of an edit. Measured on the `d_ent`
   candidate: four failures, TWO pinned literals (chronology `0.039516 → 0.104070`,
   specificity `0.678220 → 0.659371`) and TWO behavioural. The implementing
   engineer states plainly that with only the two literals it would have updated
   them and shipped — and would have shipped a cost model in which an unmeasurable
   term acts as a DISCOUNT, making participant-less units cheaper to anchor
   everywhere. The two behavioural assertions are the whole reason it did not land.
   **Credit the fixture, not the engineer**: the stop was caused by a test written
   in the right shape long before, by someone who was not there. So when you pin a
   number, ask what claim the number stands in for, and assert THAT beside it.

   *Reading a branch establishes permission, not occurrence.* That the code
   CAN produce a bad value is a different claim from that it DOES, and the
   second one needs a measurement. A finding derived by reading a branch must
   state whether the branch FIRES — and if that is unknown, say so in the
   finding itself, not in a footnote. Otherwise it is a mutation surviving
   because the corpus cannot reach it, wearing the costume of a finding.
   Measured: a P1 escalation derived the exact masses a `PlacementBasis.Ambiguous`
   branch would emit (0.4 / 0.3 / 0.3) and called them incoherent; across four
   induction fixtures and 33 details the incoherent set was **zero** and the
   branch never fired at all, real distributions being concentrated
   ([0.08 0.12 0.80], [0.11 0.89]). What survived was narrower and true: the two
   thresholds do measure different quantities and the code permits them to
   disagree. What could not survive was that it happens. Note the better finding
   hiding inside the retraction — **no fixture reaches that branch**, which is
   worth more than the claim it replaced, because an untested branch is a real
   gap where a mis-read one is only a wasted hour.

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
5. **Actors and reservations.** **A grep over a name is not an actor filter — parse
   the field.** Matching `"<actor>"` anywhere in an op file also matches the actor's
   name inside the BODY of messages written *to* them, so chasing someone makes your
   monitor report them as increasingly active. Measured 2026-08-29: an agent's last
   operation was 15:07 while a name-grep reported 15:22, the difference being three
   escalating messages the chief had sent naming that agent — **the more they were
   chased, the more alive they appeared.** This is the second instance of the same
   shape in one day; earlier, an agent was reported silent five times while posting
   under an inherited actor, because the board scan filtered the chief's own name as
   noise. A monitor that hides your own name cannot see someone wearing it; a monitor
   that matches free text counts your own voice as theirs. Read the `actor` key.
   **Check liveness before escalating urgency, not after** — the tone of a third
   chase reads very differently once you learn nobody was there to receive the first.

   One mote actor per session. Set your identity
   explicitly in every session — `export MOTE_ACTOR=<actor>` (and
   `mote session start --as <actor>`) or `--actor` on each call — and never run
   `mote actor set` in the shared checkout: `.mote/local/actor` is shared by
   every session in this directory, so changing it relabels other actors'
   posts. **A DEFAULT IDENTITY THAT IS A REAL ACTOR FORGES THAT ACTOR**, and
   this rule already existed while the hazard sat in the checkout unremoved.
   Measured 2026-08-29: `.mote/local/actor` held `claude-storymodel4s` — the
   CHIEF — so any invocation that missed the override, or any subcommand that
   did not thread it, posted with ruling authority. It published a substantive
   architecture position under the chief's name; the actual author noticed and
   corrected it append-only before the chief finished investigating. The chief
   meanwhile chased PIDs, which prove nothing, because every `mote` call is a
   fresh process. Fixed with `mote actor clear`: identity now resolves to
   *actor identity unresolved* and writes fail CLOSED. The general lesson is not
   about mote. **A rule forbidding the CREATION of a bad state does not remove
   the instance already there** — when you write one, go and check whether the
   state exists right now, because a prohibition is not a repair. Reserve paths with
   `mote begin <bead> --paths …` before editing (`mote preflight` first);
   never edit another actor's live reservation — send a request instead.
   **A DEADLINE THE MECHANISM DOES NOT ENFORCE IS A WISH.** Reservation TTL is
   what actually governs when work can change hands, so a reassignment deadline
   not aligned to a TTL is theatre. Measured 2026-08-29: the chief announced an
   18:30Z reassignment checkpoint on slice C without reading that its
   reservation ran to 19:06:20Z — reassigning on time would have handed the
   successor a bead whose files they could not touch for another thirty-six
   minutes, which is precisely the orphan-block the chief had caused that
   morning and written a rule about. **Read the expiry BEFORE naming the time**,
   and when you publish a deadline, publish the TTL beside it so people plan
   against the mechanism rather than against your intention. This is the second
   form of one error in a single day — the first was a written authority the
   tooling does not implement (the constitution claiming the chief may release
   another actor's reservations; `mote` refuses) — and both are the management
   version of the defect class this file exists to prevent: **a claim published
   without the mechanism that would make it true.**
   **IN A SHARED TREE, `git add <path>` FOLLOWED BY A BARE `git commit` COMMITS
   THE WHOLE INDEX** — including whatever another agent has staged. Use
   `git commit --only <paths>`, or run `git status --short` **with no pathspec**
   and read all of it first. Measured 2026-08-29: the chief added one doc, ran a
   bare commit, and landed NINE FILES — another agent's entire in-flight
   canonicalisation across `core`, `acquire`, `codec` and `document` — under a
   commit message about something else, ungated as its own candidate. It had
   written the hazard down forty minutes earlier and named those exact files.
   The check that failed was `git status --short <my own file>`, which **only
   reports the file you ask about**: it confirms yours is staged and cannot tell
   you what else is. A status query scoped to your own work cannot answer "what
   am I about to take". (It survived only because a later full-main audit
   happened to cover it — that was luck, not process.)
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
