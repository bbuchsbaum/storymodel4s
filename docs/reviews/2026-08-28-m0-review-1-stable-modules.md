# M0 fresh-context review 1 — core, proposition, amr-interop, acquire, features, interview

Reviewer: independent agent (fresh context), 2026-08-28. Disposition column filled by the fix pass.

## P0

| # | Location | Finding | Disposition |
|---|---|---|---|
| 1 | interview/atlas.scala:101 | `Pseudonymized.mapOffset` resolves boundary offsets to the preceding segment; spans adjacent to replaced names are stretched | fix-interview |
| 2 | interview/atlas.scala:136 | Regex lookahead (rejected on Scala Native); case-insensitive whole-word rewrites ordinary words ("will", "mark") | fix-interview |
| 3 | interview/atlas.scala:171 | Many-to-one relational pseudonyms collapse the key; `reverse` uses whole-text replace | fix-interview |
| 4 | core/atlas.scala:348 | Unclosed quote/bracket suppresses every sentence boundary in the paragraph | fix-core |
| 5 | core/atlas.scala:376 | Tokenizer splits surrogate pairs (spans cut inside code points) | fix-core |
| 6 | core/span.scala:91 | `SpanSet.isContiguous` pairwise, fails on nested members | fix-core |
| 7 | core/claim.scala:39 | `ClaimMeta` public constructor: `SurfaceExplicit` without spans representable; validators bypass `ClaimMeta.validated` | fix-core (private ctor, call sites) |
| 8 | proposition/identity.scala:101 | Tie-class factorial overflow / hang; fallback to input order breaks content addressing | fix-proposition |
| 9 | proposition/identity.scala:155 | `ChartIsomorphism.mapping` unpruned brute force | fix-proposition |
| 10 | amr-interop/Types.scala:197 | `prep-*-of` roles decoded as inverses | fix-amr |
| 11 | amr-interop/Isomorphism.scala:124 | Canonical labelling breaks WL ties by original variable name — not label-invariant | fix-amr |
| 12 | amr-interop/AmrCandidates.scala:25 | `~e.N` markers discarded, no `Lossy` | fix-amr |
| 13 | amr-interop/FromChart.scala:38 | `lossReasons` incomplete; `Polarity.Unknown` becomes `Positive` | fix-amr |
| 14 | acquire/resolve.scala:201 | `requireAgreement` counts proposals, not distinct providers | fix-acquire |
| 15 | acquire/resolve.scala:162 | Bundle-level calibration attached to whichever candidate wins | fix-acquire |
| 16 | acquire/resolve.scala:228 | Missing raw score rendered as 0.0 | fix-acquire |
| 17 | acquire/resolve.scala:192 | Acceptance status-blind; `Accepted` carries no evidence | fix-acquire |
| 18 | interview/scoring/Scoring.scala:129 | `RepetitionRule.Ignore` counts repetition as `Other` | fix-interview |
| 19 | interview/scoring/Scoring.scala:322 | Phenomenology = detail count + rating (contract 7) | fix-interview |
| 20 | interview/scoring/Scoring.scala:92 | Expected counts from uncalibrated rule masses; Missing→0 | fix-interview |
| 21 | interview/induce.scala:64 | `DiscourseFunction` routing not total; Uninterpretable → Episodic | fix-interview |
| 22 | interview/induce.scala:213 | Alternative clusters materialized twice; scope mismatch | fix-interview |
| 23 | interview/induce.scala:290 | Unvalidated `InductionConfig`; negative remainders; `Distribution.unsafe` | fix-interview |
| 24 | interview/model.scala:121 | "Assessed exactly once" not enforced | fix-interview |
| 25 | features/window.scala:46, derivation.scala:20, interview/induce.scala:391 | `Double.toString` in ids differs across platforms | fix-features (done), fix-interview |
| 26 | features/window.scala:223 | `Aggregate.overTargets` omits `lexicalOnly` from derivation | fix-features (done) |
| 27 | features/window.scala:109 | Kernel fallback returns samples outside bandwidth | fix-features (done) |
| 28 | features/boundary.scala:40 | `TemporalHypothesis.relation: String` | fix-features (done) |
| 29 | story/trajectory.scala:134 | Duplicate `FeatureSpace`/`ScoreEstimate` in story | resolved by integration merge (story aliases features) |

## P1 / P2

30–40 as reported: `SourceRole.Named` spelling + unlawful `Order`; validator gaps for normalized roles / embeddings; serialization injectivity and number literal spelling; sentence-basis coverage; missing-reason collapse and `require`; anti-circularity opt-in and unbounded `outputSpaceId`; cache key omissions, `Patch.++` provenance, no `supersedes`, vacuous `FoilReport.passes`, critic score ignored; abbreviation list and number tokens; centered windows and atlas overlap validation; parser totality and `FrameId.Shape`; stringly-typed escapes, locale-dependent lowercasing, `Eq[AmrGraph]` ignoring nodes. Dispositions: assigned to the module fix agents; residuals recorded in `docs/reviews/residuals.md` after the pass.

## Well done (do not churn)

Portable SHA-256; `Credence`/`Probability`/`RawScore` split; `PropositionChart` private ctor; exact `AmrIsomorphism.findIsomorphism`; PENMAN parser coverage; `AmrValidator` evidence values; table-driven conservative `ToChart`; `Estimate.Missing` threaded through reducers; `DerivationGraph` cycle rejection; atomic `PatchApplier`; injective prompt manifest checksum; deterministic `StageDag`; `TranscriptAtlas` overlay design; `Distribution` normalized by construction; `EpisodeModel.of` status guard; binary-search token lookup.
