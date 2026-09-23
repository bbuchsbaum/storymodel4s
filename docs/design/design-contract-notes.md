# Design contract: full text and case record

Moved verbatim from `AGENTS.md` as of `main` `604080fc` on 2026-09-23 (owner directive: refactor
AGENTS.md). Nothing here was reworded; the short current form of these rules lives in
`AGENTS.md`, which links here. The numbered rules 1-14 are unchanged and binding; this file keeps the measured cases and rationale behind them.

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
   `unsafe` is `private[align]`, so reachability needs a production-path probe
   (traced 2026-09-17: no production path — `HsmmResult.validated` rejects NaN
   flow mass — only forgery from an `align` sub-package; polarity flipped to
   fail-closed anyway in `01cfbc46`, because the type's contract is the claim);
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

   *AN IMPOSSIBILITY CLAIM NEEDS MORE SCRUTINY THAN A POSITIVE ONE, NOT LESS.* A
   negative result establishes that a thing did not happen IN THE SETUP TESTED; it
   never establishes that no setup exists. Measured 2026-08-30: an author showed
   four sound measurements concluding that `typeChecks` cannot reach the companion
   members of a type compiled in the same run, and therefore that a test-only
   probe anchor could NEVER exercise the companion door. The chief verified the
   measurements were internally consistent and built a rule on them — that the
   door must be anchored on main source. A reviewer then produced the working
   configuration in one scratch mutation: the DERIVED companion member is
   unreachable in the same run, an EXPLICITLY DECLARED one is. Every measurement
   had been correct; only the "never" was wrong, and it was the chief who promoted
   it to a rule. **Ask of any impossibility: is there a configuration in which it
   would work?** These are the most expensive claims to accept on trust, because
   they close a design space permanently and nobody re-tests a door someone has
   declared welded shut.

   *AN IDENTITY OR A CLASS MUST BE DERIVED FROM WHAT IT DESCRIBES, NEVER ASSERTED
   BY THE CALLER.* A construction boundary asks whether a value can be built; this
   asks whether a field that CLAIMS SOMETHING ABOUT the value was filled by
   computing it or by someone typing it. Three live instances in three modules on
   2026-08-30, each found by a different reviewer, none of whom could see the
   pattern because each saw one: (1) a bench facade accepted any `Embedder[Id]`
   and unconditionally asserted `NeuralEncoder`, so a `HashedNgramEmbedder`
   compiled, ran, and RENDERED AS A NEURAL ENCODER — the scientific class was
   caller-selected rather than derived from admitted provider authority; (2)
   `Channel.identityChecksum` hashed a render that TRUNCATED provider and geometry
   identities to twelve characters, so two distinct fingerprints sharing a prefix
   produced one checksum; (3) `ParserBatchResult.conforms` compared a
   caller-selected `ParserRequestId` and NOT `attempt.receipt.requestChecksum`, so
   conformance checked the label instead of the content. **The question to ask of
   any provenance, class, or identity field: if the caller lied here, what would
   catch it?** If the answer is nothing, the field is decoration with the
   authority of a receipt — worse than absent, because it survives audit. Note
   that (1) sat INSIDE THE REMEDY for the same defect one level up: the whole
   reason that module existed was that a lexical channel had been called
   semantic on the strength of its identifier.
