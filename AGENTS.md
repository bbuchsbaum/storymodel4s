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

## Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Scaladoc on every public type with a one-line "why", not just "what".
- Tests: one law/invariant per property; adversarial fixtures for every
  prohibited failure mode in the design record (§27.2, §52.3).
