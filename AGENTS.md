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
| `amr`       | `storymodel4s.amr`        | PENMAN syntax, checked AMR graphs, role canonicalization, isomorphism, Smatch, alignment sidecar |
| `story`     | `storymodel4s.story`      | Narrative ontology: entities, situations, contexts, typed relation layers, hierarchy, trajectory, validators, `AlignmentSource` |
| `document`  | `storymodel4s.document`   | Mention graph (disjoint union of sentence AMRs), exact-coreference quotient, projection to narrative nodes |
| `recall`    | `storymodel4s.recall`     | Recall units, discourse functions, recall relations, recall graph |
| `align`     | `storymodel4s.align`      | Local costs, unbalanced transport, graph-HSMM trajectory inference, recall signature |
| `interview` | `storymodel4s.interview`  | Transcript atlas, detail atoms, memory addresses, target-episode induction, AI-compatible scores, rich profile |
| `codec`     | `storymodel4s.codec`      | Canonical circe JSON codecs |
| `fixtures`  | `storymodel4s.fixtures`   | Hand-authored *War of the Ghosts* model, recall paraphrases, interview example |
| `laws`      | `storymodel4s.laws`       | Published Discipline law suites and ScalaCheck generators |

## Build and test

- Scala 3.7.4, sbt 1.12.14, sbt-typelevel 0.8.7.
- `sbt compileAll testAll` (all platforms) before declaring work complete;
  `sbt testJVM` for a fast loop.
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

## Style

- scalafmt 3.10.7, `maxColumn = 100`.
- Scaladoc on every public type with a one-line "why", not just "what".
- Tests: one law/invariant per property; adversarial fixtures for every
  prohibited failure mode in the design record (§27.2, §52.3).
