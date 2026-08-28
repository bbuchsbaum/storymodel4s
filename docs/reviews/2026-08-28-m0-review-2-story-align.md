# M0 fresh-context review 2 — story, recall, align, document, laws, WOG fixture

Reviewer: independent agent (fresh context), 2026-08-28. Disposition column filled by the fix pass.

## P0

| # | Location | Finding | Disposition |
|---|---|---|---|
| 1 | story/alignment.scala:88, align/bridge/StorySourceView.scala:110, story/trajectory.scala:58 | World-time relation ignores the edge's context; a `Before` scoped to `Speech(w)` between narrated situations becomes narrated-world chronology. WOG suite enshrines a speech-order artifact as a `JumpBackward` | fix-story (world time = same-context edges only; WOG assertion corrected) |
| 2 | align/candidates.scala:23, hsmm.scala:211 | Provider abstention → zero candidates → 100% external → booked as `Intrusion` | fix-align (`Unranked` state) |
| 3 | story/graph.scala:98, model.scala:32 | `StoryModel.claims` omits entity-label and segment-summary metas; explicit-without-spans validates | fix-story (+ core private `ClaimMeta`) |
| 4 | story/validate.scala:331 | Strict-closure acyclicity composes only Before/Meets; `A<B, C During B, C<A` accepted | fix-story (interval endpoint encoding) |
| 5 | story/validate.scala:59 | Atlas never validated nor compared with the model's source | fix-story |
| 6 | align/bridge/StorySourceView.scala:340 | WOG vocabulary hard-coded in the bridge lemma table (fixture leakage) | fix-align (generic normalizer; test-only lexical resource) |
| 7 | align/hsmm.scala:311 | Relation-preservation refinement undoes contradiction gating | fix-align (gated candidates excluded) |

## P1 / P2 (8–40)

Gating as fixed margin not exclusion (#8); ∃-leaf segment gating (#9); `ContextConflict` as a gate blocks the canonical "reported-as-fact" distortion (#10); WorldTime adjacency as full closure, Θ(n²) (#11); EntityContinuity all-pairs for hubs (#12); hierarchy layer inconsistency (#13); segment density spike (#14); localizability K (#15); backward mass definition (#16); causal preservation via root coverage (#17); WOG expectations unchecked, 3/11 disagree (#18); missing positive ambiguity, cause-of-death edge from belief content (#19); no entity membership relation (#20); broken narrated-world chain across the battle (#21); no adversarial mutants (#22); segmenter cue false positives (#23–24); document quotient erases identity, kind phantom-only (#25); validator gaps (#26); context partition vs scope inconsistency (#27); laws generators valid-only (#28); Sinkhorn convergence/NaN (#29); HashMap summation order, locale (#30); Viterbi/posterior cost mismatch (#31); unvalidated configs (#32); bridge constructor bypasses `Validated`, dropped layers (#33); `discourseOrder` by first ref (#34); status discarded in relation weights (#35); modelling nits (#36); string-matching prohibition tests (#37); world-time density drops unranked (#38); external masses not surfaced (#39); calibration test/ECE binning (#40). Dispositions: fix-story (#18–22, #26, #27, #34–37), fix-align (#8–17, #23–24, #29–33, #38–40), fix-doclaws (#25, #28).

## Direct answers recorded

Laws genuinely enforced: single primary root, root-reachability, no empty primary segment, context scope, containment acyclicity, Equal-vs-strict, strict cycles with Equal union-find. WOG: reported injury speech-scoped; ghosts belief-scoped attribute on one entity; one canonical battle with Prospective reference; recounting with nested speech contexts — correct. HSMM: log-space forward–backward and flow posteriors correct and property-tested; external-in mixed before source softmax.

## Well done (do not churn)

Stable law names and sorted reports; `Draft→Validated` only via `private[story] withStatus`; iterative cycle finder; canonical-form-only temporal storage with total converse; log-space forward–backward with deterministic Viterbi ties; PropertySuite on normalization/flow/Viterbi; Missing importance excluded rather than zeroed; Sinkhorn kept as an explicit ablation; WOG nested speech contexts (`speechYm2Inner`), `iWasShot` Reported+Retrospective, token-anchored discourse order, retained rejected boundary; state-change layer used for sunrise/death rather than causation; order-independent union–find coreference; true disjoint union with collision errors; Kahn ranking with deterministic ties; hedge cues masked before negation.
