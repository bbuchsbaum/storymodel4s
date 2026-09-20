# Delivery backlog

Reconciled 19 September 2026 against `1113f96864a38a2869e49d9c8c5d4e2dc43f5d10`. Governing direction: [PLAN.md](PLAN.md).

**Planning checkpoint (historical):** applied and verified through Mote CLI. Reconciled 136 existing issues; closed 27 with explicit dispositions; added nine active tickets and superseded one duplicate. That checkpoint had 118 open, 234 closed, none doing/review/blocked. All 87 dependency/relationship/tag operations were accepted. See [execution receipt](EXECUTION_RECEIPT.json); live Mote and the completion evidence below supersede those status counts.

The 30 consultant TA keys are crosswalk references, not another set of tickets. Nine new active tickets cover genuine gaps. A tenth clock ticket was superseded after a concurrent intake filing was discovered; the existing C1 ticket is retained. Existing mapper, film, scoring, reliability and release tickets retain their identities. Broad context-sensitive measurement and joint inference stay deferred in PLAN rather than gaining speculative subtasks.

## What to do first

One implementation slice at a time. Baseline capture, scorer repair, production clock integration, S0 text parity pins, S1's ADR amendment, S2's checked substrate, S3's typed acquisition support and S4a's typed nodes/fallible draft are complete. **S4b's sealed envelope, identity and checked text witness are next on the D1A track, preserving the frozen text behavior.** At-risk branch preservation remains independent foundation work. The account/runner decision blocks CI execution, not local engineering.

The active [film foundation goal](goals/film-foundation-20260919.md) assigns nine existing
Motes to `film_foundation`, beginning with S1. It contains eight executable deliverables
and one parent reconciliation, with a bounded finish line and each ticket's gates retained.

- Completed `bd-01M2WVCEBHAZBKJQ0EB84EA4HV` — [frozen mapping baseline](evidence/sherlock-baseline-20260919/README.md)
- Completed `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2` — [unit manifest and fail-closed scorer](evidence/sherlock-scorer-20260919/README.md)
- Completed `bd-01M2WTGF981AY7RCN8QC42JSPT` — [production ClockRepair integration](evidence/sherlock-clock-repair-20260919/README.md)
- Completed `bd-01M2TAB3QD2YXSPR8THYRGFBKS` — [S0 text parity pins](evidence/d1a-s0-text-parity-20260919/README.md)
- Completed `bd-01M2TABFW72D8WWQF481SD37KW` — [S1 ADR amendment](evidence/d1a-s1-adr-20260919/README.md)
- Completed `bd-01M2TABVXSM72M9DD0SNB6GZ11` — [S2 checked substrate](evidence/d1a-s2-substrate-20260919/README.md)
- Completed `bd-01M2TAF8JH42NYJJAFWPD7HTZB` — [S3 typed acquisition support](evidence/d1a-s3-acquire-20260919/README.md)
- Completed `bd-01M2TAFN5WMXW4NT20YJQAW2FR` — [S4a typed node support, fallible draft and projection ordering](evidence/d1a-s4a-node-support-20260919/README.md)
- Next `bd-01M2TAG1GEAGXCY33P0B17QBJ1` — D1A-types S4b: sealed envelope, identity and checked text witness
- `bd-01M2TA2EMFCWRKXK6QJHGV702Q` — [foundation] Phase 0: rescue the only copy of audit/masc-role-corpus (b756b4bb) into the main repo
- `bd-01M19G69RQCHMT2EMG11XFT4WX` — G0: obtain an executed exact-SHA clean build and CI receipt

Useful Mote commands after reconciliation:

```sh
mote ls --tag lane-foundation --ready
mote ls --tag delivery-20260919
mote show bd-01M2TA01EHVRF6MQ1N00XTVK1K
```

The foundation filter is the immediate executable queue. Other lanes are visible in ticket titles and bodies, and every old issue is enumerated below. A ready research ticket is not permission to start it ahead of the delivery slice. Priorities: 0 foundation, 1 next implementation/film, 2 validation/release/maintenance, 3 deliberately later. Owner decisions apply only to their named track. Containers use nonblocking relationships; dependency edges encode technical prerequisites.

## Critical dependency chain

```text
baseline -> scorer -> synthetic recovery ---------------------> preview
    |      support -> shared local evidence -> reference
    |           records -----------------------> compatibility -> readouts
    |              |                                   |             |
    |              +-> word/timing projection ---------+----------> exports
    +-> clock repair                   structured decode -> command -> preview

S0 -> S1 -> S2 -> {S3, S4a} -> S4b -> S4c -> D1B signatures
                                        -> film plan -> film compile -> film proofs
preview + film proofs + infrastructure/hardening -> stable release
```

The machine-readable [backlog](BACKLOG.json) and [reconciliation](RECONCILIATION.json) contain exact edges. The diagram is a reading aid; the reference chain does not traverse the benchmark checkpoint or film migration. Synthetic recovery gates the scientific-analysis preview; independent human recovery gates empirical claims. It is not necessary to win the mapper comparison to ship the library.

## New and substantially revised execution tickets

### G0: freeze mapping regression inputs, configurations and historical outputs

Mote: `bd-01M2WVCEBHAZBKJQ0EB84EA4HV` · new · lane `foundation` · priority 0.

Prerequisites: none.

Boundary: embed-bench/video, tools/recall-study, docs/refactor/evidence.

1. Commit a content-free baseline manifest with code SHA, input/record hashes, fixed unit/word IDs, source grain/axes, model/cache/render/config identities, source target inventory, outcomes and output checksums. No new gold read or sealed-test output.
2. Capture an independently authored redistributable text and two-part annotated-video miniature; include backward/revisit, external, missing-unit, partial-support and argmax/decode-disagreement cases.
3. On admitted Sherlock local inputs, preserve exact existing TSV/anchor/sidecar outputs and provider replay inputs before behavior changes. State platform and nondeterministic metadata exclusions in advance. An unexplained mismatch fails.
4. Receipt links resolve; changed input/config and dropped/duplicated unit mutations fail identity checks. S0 text compiler pins remain a separate existing ticket.
5. Run the frozen admitted Sherlock baseline twice with unchanged inputs/config/cache and require identical legacy output digests. Also retain a content-free digest of every annotation MediaLocus tuple (row, part, startTick, endTick), including rows unused by a particular recall.

Validation: `tools/data-root.sh --check; existing sherlockRecallMap in frozen replay configuration; proposed baseline-manifest verifier`.

No model tuning, new corpus intake, or full compiler refactor.

### G0: route Sherlock production coordinates through receipted ClockRepair

Mote: `bd-01M2WTGF981AY7RCN8QC42JSPT` · reused · lane `foundation` · priority 0.

Completed 19 September 2026. [Implementation, frozen parity, refusal mutations and clean gate](evidence/sherlock-clock-repair-20260919/README.md). The acceptance criteria below remain the closure contract.

Prerequisites: `bd-01M2WVCEBHAZBKJQ0EB84EA4HV`.

Boundary: corpus-intake/TimebaseRepair.scala, corpus-intake/SherlockAnnotations.scala, embed-bench/sherlock/SherlockRecallMapping.scala, docs/data/sherlock/timebase-repair.json.

1. Parse presentation parts/rates/durations/row ranges/IDs plus certifies, doesNotCertify, whyNotRepaired and explicitlyNotUsed. Committed verified JSON is the single hash authority; bind its digest/schema/version in provenance. Reject unsupported machine-readable semantics and malformed/duplicate/missing/crossed references.
2. Construct MediaManifest from that record; join every declared part/axis to the actual SourceBundle axis with extent/timebase/identity checks. Keep per-run source axes distinct. No self-comparison of a repair target in place of checking the selected bundle.
3. Every locus start/end uses ClockRepair.projectRunLocalSeconds; checked exact rational offset/scale construction avoids Long intermediate overflow. Require integral representable ticks and correct bounds. Atlas exposes row-to-repair receipt linkage. CLI loads the admitted record; no fallback to literal manifest.
4. Refusal witnesses: missing repair, wrong run/target axis, altered annotation/media identity declaration, inconsistent row partition, nonintegral ticks, overflow, unsupported formula/mapping, missing record and changed semantic restrictions. Keep instants, rows 481-482 and both uncovered tails.
5. Real-data same-platform same-config cached-response before/after legacy report bytes, anchor checksums and the complete per-row MediaLocus tuple digest are identical, including rows unused by the recall. New provenance fields are checked separately; report parity is not demanded of intentionally extended sidecars. Two-run synthetic offset/origin consistency (refuse formula-inconsistent declarations) and wrong-axis tests kill direct-multiplication bypass. No-repair overload still refuses.
6. Bind retainedRows to inputRows minus dropped rows and the declared 998-row coordinate system; bind the ordinary run-1 end to the run-2 offset and raw boundary. Verify the upstream notebook digest against a pinned fetch receipt, or explicitly retain declared-unverified status. Mutate each JSON path and assert the mutation applied before requiring its witness to fail.

Validation: `sbt -batch 'corpusIntake/testOnly *TimebaseRepairSuite *SherlockAnnotationsSuite'; real sherlockRecallMap twice with baseline inputs and cmp`.

Reuse existing vocabulary; this is one integration bead, not a general clock framework.

### G1: checked mapping records, stage provenance and versioned codec

Mote: `bd-01M2WVENSB0P955Y0B603CC20Y` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WVCEBHAZBKJQ0EB84EA4HV`.

Boundary: align mapping result, recall unit inventory, codec mapping codec.

1. One checked mapping result accounts for all requested fixed units and words, source targets, exact support, alternatives, missingness, external outcomes, processing failures and compound-reference/decomposition status.
2. Separate RawScore, NormalizedScoreMass, TransportMass, ModelPosterior and CalibratedProbability. Raw argmax, selected target mass, calibrated chosen-decision event and fill/projection derivations remain distinct. No inherited Faithful label becomes assessed fidelity.
3. Schema includes inference/context/candidate/reference-prior policies, target universe/grain, stage assumption receipts, coverage, inference/organization/projection units and compatibility provenance. Unknown historical provenance cannot self-certify.
4. Preview represents multipart sources with explicit bundle inventory and per-target/axis membership, not a fabricated single bundle; canonical StoryModel remains one bundle under D1A. Long ticks/rational integers survive wire as decimal strings.
5. External-package probes refuse Mirror/copy/alias forgery, foreign identities/axes, duplicates and missing units; independent miniature round-trips. Mutating chosen confidence to raw argmax confidence and dropping failure rows each fail.

Validation: `proposed MappingContractSuite / MappingCodecSuite on JVM, JS, Native; public consumer construction probes`.

0.x mapping schema, separate from StoryModel and HSMM schemas; no new module.

### G1: extract shared local mapping evidence and receipted scoring channels

Mote: `bd-01M2TACM78289S4TECE91GT5K2` · reused · lane `next` · priority 1.

Prerequisites: `bd-01M2WVENSB0P955Y0B603CC20Y`, `bd-01M19956MFSG7076QE4J66T7E9`.

Boundary: align cost/candidates, embed-bench StageTrace/RecallToVideo.

1. Extract candidate nomination, raw local score/cost evaluation and outcome receipts BEFORE HSMM/refinement. Both reference and GraphHsmm consume the same immutable evidence object; historical StageTrace adapter reads it.
2. Register deterministic lexical and existing embedding channels with finite score direction/scale, declared grain/empty-input policy, input/render/config/model/candidate identities and replay. Unavailable scores remain typed unavailable, not 0 or uniform.
3. Represent content-only scorer input separately from source coordinates/adjacency, so strict reference providers cannot read behavioral features. Audit retrieval and equal-score tie handling, not only decoder.
4. Compare evidence/state membership/term values and digest across both uses; same-platform legacy embedding evidence parity holds under the frozen preset. Refined emissions cannot be relabeled as base costs.
5. External support is NotApplicable or its distinct measured contract, not 1.0 from no terms. No scene-score allocation is presented as within-scene measurement. Guard and alias/forgery mutations fail.

Validation: `proposed LocalMappingEvidenceSuite plus existing StageTraceSuite; no-network provider replay`.

Removes D1B/benchmark-checkpoint prerequisite: this extraction uses existing checked target support; full film signature migration remains D1B.

### G1: strict local reference measurement from shared mapping evidence

Mote: `bd-01M2WVF86T8QEEA1TK8Z0ASJHW` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2TACM78289S4TECE91GT5K2`.

Boundary: align reference inference, embed-bench StageTrace adapter.

1. Pure public reference operation consumes checked immutable local evidence without first running GraphHsmm. Reuse stable local normalization from StageTrace; initially deterministic lexical scoring plus vetted existing channels. No transitions/refinement/monotone decoder/fill.
2. State target universe, one grain/hierarchy cut, prior weights, temperature, normalization and external alternatives; reject duplicate target identity. Missing computation is not uniform evidence; distinguish successful indistinguishable evidence from unavailable scores.
3. Restricted retrieval/render/scorer inputs contain opaque target IDs and content, not playback positions, chronological numbers/list order or prior assignments. Source coordinates are supplied separately to readouts.
4. Fixed-packet permutation preserves each packet mapping after ID reconciliation; source storage permutation and bijective opaque-ID rename preserve results. Equal-score shortlist ties are tie-complete or refused, never chronology/ID-truncated. Freeze floating tolerances before runs.
5. Identical candidates/scores/receipt digest feed reference and HSMM paths. Anti-tests injecting chronology, previous assignments, sequential external preference or refined emissions fail. Uniform evidence remains flagged uninformative and uncalibrated.

Validation: `proposed ReferenceMeasurementSuite and ReferenceIsolationSuite on all align platforms; StageTrace parity suite`.

No contextual reference inference or statistical calibration in this slice.

### G1: enforce readout compatibility with executed inference policies

Mote: `bd-01M2WVFV2WGF5BVQHX7JTYYWRH` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WVF86T8QEEA1TK8Z0ASJHW`, `bd-01M2WVENSB0P955Y0B603CC20Y`.

Boundary: align measurement policy, codec stage receipts.

1. Readout request declares quantity, source axis/order, recall organization unit, resolution and unknown-transition policy. Derived stage ledger covers candidates/rendering/scoring/context/refinement/inference/decision/projection.
2. Checked outcomes distinguish CompatibleWithDeclaredPolicy, ModelDependentOnly, Incompatible, UnknownProvenance. A hard-monotone path cannot satisfy a reference backtracking request. PriorScale=0 and Content rung do not pass automatically.
3. Round-trip preserves the decision and its reasons. Receipt omission, caller-label forgery and stage/config/provider/candidate substitution fail; old artifacts remain UnknownProvenance.
4. Strict compatibility establishes implemented policy only. Custom unverifiable scoring cannot mint reference or calibrated authority; it remains explicitly exploratory. Reconstruction summaries are still available under their own policy.

Validation: `proposed MeasurementCompatibilitySuite + codec/public-consumer negative probes`.

Reuse LayerUse details without pretending its transition-only scope covers the pipeline.

### G2: uncertainty-aware organization counts and conditional ambiguity bounds

Mote: `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WVFV2WGF5BVQHX7JTYYWRH`.

Boundary: align organization readouts.

1. Calculate backward/forward/same-target counts over adjacent original organization units. Keep external, incomparable, unresolved and failed adjacencies accounted separately. Name ratio-of-expected-counts denominator; zero denominator is unavailable.
2. Use joint assignments, or row products only under the strict independent local policy. Equal row marginals with different joints give different answers; reject unexplained marginal products. Normalized-score summaries keep score-conditional labels.
3. Independent enumerator verifies point path 2,7,3,8 => backward 1, forward 2, same 0; two uniform binary rows => .25/.25/.5 with backward bounds [0,1]. A,unknown,C has no direct A-C observation. Copy to 15 display words changes no count.
4. First bounds cover additive transition counts on the Cartesian product of per-unit admissible sets (optional explicit local edge restrictions only); compare sparse DP to exhaustive tiny enumeration. General coupled/HSMM admissibility is refused unless supplied by a later certified solver.
5. Bounds carry candidate coverage, admissible-set construction and grain; they are not confidence intervals. No chronology means incomparable; no universe for unknown rows means bounds unavailable or a separately declared completion universe. All-uninformative evidence yields a low-information result, not a scientific precision claim.

Validation: `proposed OrganizationReadoutSuite and independent exhaustive enumeration fixtures`.

No generic joint posterior engine, arbitrary ratio bounds, or mandatory trajectory sampler.

### G2: stable recall words, timing and honest support projections

Mote: `bd-01M2WVH1DC8ZPDC992G4MX3TXG` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WVENSB0P955Y0B603CC20Y`.

Boundary: recall RecallTiming extraction, codec unit_words/support tables.

1. Stable transcript-bound UTF-16 word IDs and explicit unit membership survive segmentation changes; unit IDs change when unitization changes. Unicode/newline/quoted input and missing/onset-only/interval timing are explicit.
2. Keep inference_unit, organization_unit and projection_unit distinct. Word copying does not create event-transition observations; audio timing is not needed for transcript-order analysis.
3. Support uses per-axis interval unions with complete/partial/unknown coverage and separate display hull. Missing child loci cannot create full group support; duplicates/foreign axes refuse.
4. Optional time-grid projection states W/P/H and exposure/allocation policy, preserves missing recall exposure and external/unprojectable mass. No silent uniform scene-to-second or next-onset-as-offset rule.

Validation: `proposed RecallProjectionSuite on recall platforms; exact integer >2^53 and non-BMP consumer fixture`.

Minimal source/word tables first; dense matrices and new granularity models deferred.

### G2: mapping exchange tables and an independent Python or R consumer

Mote: `bd-01M2WVHF4B5DAXJYY4W91VK4WV` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WVENSB0P955Y0B603CC20Y`, `bd-01M2WVH1DC8ZPDC992G4MX3TXG`, `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR`.

Boundary: codec exchange writer/reader, pipeline export, examples.

1. Document TSV quoting and types plus JSON manifest; export target/word/unit/support/measure/decision/transition tables from one checked result. No inference is reconstructed from display strings.
2. Independent Python or R reader checks hashes, joins, all outcomes, stage policies, exact ticks and interval membership; reproduces manually authored tiny transition answers without importing Scala exporter logic.
3. Schema rejects duplicate/missing keys, foreign source/recall identity, digest mismatch, unsupported versions and partial publication unless explicitly requested. Do not round authoritative measures or cast exact ticks through floating point.
4. Reference and structured views coexist with common evidence digest and separate policies/decisions. Calibration unavailable stays explicit; consumer sees ambiguity and coverage limitations.

Validation: `proposed MappingExchangeSuite; python3 examples/mapping/read_mapping.py <miniature> (path to be implemented)`.

One downstream language is enough; no viewer or Parquet requirement.

### G3: frozen synthetic recovery court for recall organization

Mote: `bd-01M2WVHVVYQBKSBSW573HZYR9Q` · new · lane `validation` · priority 1.

Prerequisites: `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2`, `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR`.

Boundary: tools/recall-study recovery fixtures, docs preregistration.

1. Commit known forward, reverse, jump and revisit packets with independent intended assignments; freeze admissible sets, grain, primary estimands, tolerances, seeds, missingness and aggregation before model comparisons.
2. Packet equivariance uses fixed IDs and packets, not resegmented shuffled text. Full language-pipeline reverse/revisit examples are authored coherently; source order/ID/chronology metadata perturbations reconcile.
3. Assess localization, candidate coverage, specificity and organization recovery bias, reversal sensitivity, group-effect attenuation and uncertainty/bounds coverage. At fixed true organization vary omissions, ambiguity, paraphrase and transcription quality.
4. Uniform/no-information evidence cannot produce a confident behavioral conclusion. Mutating to monotone paths, dropping unresolved units and counting projected word transitions each fails a known-answer witness.
5. All cases/failures remain in results. A negative result closes the executed study with a failed claim gate; tolerances are not loosened. Synthetic success does not establish human recovery.

Validation: `proposed python3 tools/recall-study/recovery.py --frozen-manifest <path>; independent fixture oracle`.

No sealed participant gold read and no numeric human-accuracy promise.

### G1: extract named structured reconstruction and decision policies

Mote: `bd-01M2TAD04SR823TQVG9VPNH6R3` · reused · lane `next` · priority 1.

Prerequisites: `bd-01M2TACM78289S4TECE91GT5K2`.

Boundary: align structured decision policies, embed-bench MonotoneScene/RecallToVideo.

1. Move pure structured decision logic/config into the library; named versioned reconstruction profile declares persistence/order/hierarchy/causal/external/refinement/fill choices. Keep historical Sherlock preset explicit.
2. No fill by default for new explicit profiles; historical fill remains available only by request. Raw evidence and posterior immutable; raw argmax, chosen target, chosen mass and fill origin exported separately.
3. Same recorded inputs/config reproduce historical anchor bytes. Config changes alter policy identity; no raw-argmax confidence is attached to a different chosen target.
4. Structured output cannot satisfy strict reference compatibility. No transfer decoder tuning in this extraction; generic configuration does not require a corpus-schema research comparison.

Validation: `existing MonotoneScene suites plus proposed ReconstructionPolicySuite`.

Supersedes generic decoder extraction only; H2 is later reconstruction research.

### G2: typed mapping facade, explicit config and safe batch publication

Mote: `bd-01M2TADC4VKSDZ2S9SXETH2MYM` · reused · lane `next` · priority 1.

Prerequisites: `bd-01M2TAD04SR823TQVG9VPNH6R3`, `bd-01M2WVFV2WGF5BVQHX7JTYYWRH`, `bd-01M2WVH1DC8ZPDC992G4MX3TXG`.

Boundary: pipeline mapping facade, codec publication manifest.

1. Public typed API and thin CLI accept checked source/recall inputs and explicit versioned profiles; no bench imports. Resolve config once; reject invalid config rather than environment fallback.
2. Produce one outcome per input unit and participant, including failure, abstention and external alternatives; batch failures remain local. Exit policy separates execution, structural validity and requested capability readiness, preserving receipted partial outputs.
3. Write to verified temporary output then atomic rename, or completion manifest last with readers refusing incomplete output. Do not overwrite historical runs silently; interruption and corrupt-output tests fail closed.
4. Cache identities bind source/provider/render/config; enforce offline replay/no network and budgeted retries. Frozen legacy structured preset matches baseline scientific outputs; reference uses its own explicit policy.
5. Reference provenance is derived from registered supported stages. Unverifiable custom/legacy results remain UnknownProvenance/ModelDependentOnly; do not wait for a universal custom-cost authentication protocol to ship honest exploratory output.

Validation: `proposed MappingPipelineSuite and public CLI replay/crash tests`.

The checked record/codec is the records ticket; full HSMM invocation authority stays on the existing invocation ticket.

### G2: demonstrate the public annotation-assisted analysis journey

Mote: `bd-01M2WVJ8HKX6EHG2C5CXYEZYJ6` · new · lane `next` · priority 1.

Prerequisites: `bd-01M2WTGF981AY7RCN8QC42JSPT`, `bd-01M2TADC4VKSDZ2S9SXETH2MYM`, `bd-01M2WVHF4B5DAXJYY4W91VK4WV`, `bd-01M2WVHVVYQBKSBSW573HZYR9Q`, `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2`.

Boundary: pipeline examples, docs-site analysis guide, docs/refactor/evidence.

1. New analyst runs a text miniature and an admitted real annotated-video recall from ordinary local input paths through library/CLI to reference+reconstruction mapping tables and the independent consumer. No normal consumer imports bench.
2. Configuration resolved once; source reused across two recall inputs without rewriting its identity. Offline replay forbids network and yields deterministic scientific payloads; provider failure remains one typed participant/unit outcome.
3. Require reference-compatible organization readouts, per-unit and per-transition coverage and exact support/receipt joins. Declare annotation-assisted origin, inference grain and uncalibrated status. Do not claim automatic visual understanding or independent empirical recovery.
4. Crash/partial-write/digest mutations cause incomplete output refusal. Execution completion, structural validity and requested capability readiness are separate. No media/prose leaks into committed logs.
5. Commit command/config/input/output hashes, platform, totals and one reader walkthrough; private artifacts remain local. This gate is not D1A-film, V1, or the stable 1.0 release.

Validation: `proposed pipeline public recall-map invocation + offline replay + independent reader; docs-site executable example`.

This is the first usable product milestone.

### G0: obtain an executed exact-SHA clean build and CI receipt

Mote: `bd-01M19G69RQCHMT2EMG11XFT4WX` · reused · lane `foundation` · priority 0.

Prerequisites: `bd-01M2TA2283FC7FSA2G7TJ1WSMN`.

Boundary: .github/workflows, build.sbt, docs-site verification.

1. Diagnose actual job-start failure; baseline CI 35406786299 has empty steps and billing/spending-limit annotation. Existing workflows are present; do not report a Scala failure from this.
2. After the chosen account/runner route, obtain clean pinned-dependency JVM/JS/Native and docs receipts with commands, exit, module totals, skipped/environment-gated tasks and immutable code SHA. No private sibling override may establish pin qualification.
3. A local clean archive/standalone-clone check can advance engineering while owner action is pending, but is labeled local and does not close the CI execution requirement. Workflow integrity checks regenerate from build settings.
4. Capture startup and test failures distinctly; a zero-tests exit does not pass. Do not alter billing, paid infrastructure or repository visibility without the existing owner decision.

Validation: `sbt -batch checkAll; githubWorkflowCheck; declared docs verification; CI run/check annotations`.

Historical successful local gates are retained as prior evidence, not a current CI pass.

### OWNER: choose the CI account or runner route (push already complete)

Mote: `bd-01M2TA2283FC7FSA2G7TJ1WSMN` · reused · lane `owner` · priority 1.

Prerequisites: none.

Boundary: GitHub Actions account/runner decision.

1. Record owner choice of billing remediation or a specific permitted runner route; no repository-visibility or spending change is inferred.
2. Push portion complete at baseline: local HEAD, origin/main and live remote main all equal 1113f96864a38a2869e49d9c8c5d4e2dc43f5d10. Preserve this evidence rather than asking again.
3. CI ticket executes the chosen route and verifies tests actually ran; this decision ticket alone is not qualification.

Validation: `git ls-remote origin refs/heads/main; owner decision recorded on this issue`.



### G5: harden the public mapping facade and actionable errors

Mote: `bd-01M2TAM66RTEMY3VVHA3N2MYXK` · reused · lane `release` · priority 2.

Prerequisites: `bd-01M2TADC4VKSDZ2S9SXETH2MYM`.

Boundary: pipeline public error facade, docs-site examples.

1. Public entry points return typed actionable failures with path and exception class where safe; keep source/participant prose out of logs.
2. Requested capability failure and invalid draft are distinct from success; tests cover advertised CLI exit behavior.
3. Unify only errors crossing this facade where a concrete consumer needs it. A repository-wide migration of all 45 refusal types is deferred.
4. A newcomer executes documented local analyze/inspect/export calls without manually assembling internal proof objects; facade cannot bypass identity/privacy/validation.

Validation: `public MappingPipelineSuite consumer tests and docs-site example verification`.

Supersedes the broad progressive-facade request for this delivery; unrelated API ergonomics remain later.

### DELIVERY: usable recall mapping and defensible organization measurement

Mote: `bd-01M2TA01EHVRF6MQ1N00XTVK1K` · reused · lane `container` · priority 1.

Prerequisites: none.

Boundary: docs/refactor/PLAN.md, BACKLOG.md.

1. G0-G2 public annotation-assisted preview demonstrated with complete accounting, reference/reconstruction policies, compatibility and downstream reader.
2. G4 compiled-film API and full D1B proof remain required for stable 1.0 under ruling E; V1/E0 stay 1.x.
3. Release gate closes on execution/structural/API evidence. Scientific recovery, calibrated confidence and superiority are distinct named claim gates; no optional provider or research win gates engineering.
4. Every unfinished issue has a lane and remaining-work statement; only executable prerequisites use dep; no stale fleet authority. Gate review identifies one next failing witness.

Validation: `Mote graph/crosswalk checks and exact-SHA gate receipts`.

Contains work with rel, not a dependency on every child.

## Common implementation completion rule

At the implementation SHA, record exact paths, command receipts, test totals and exit, artifact digests, discriminating failure injections, compatibility impact and a separate cold review. Scope gates with `tools/reference-scope.sh`; land under SD2 `checkAll`. Names of new suites/commands above are proposed acceptance specifications until implemented. A passed constructor suite does not close a real-input demonstration. No tests were rerun to re-certify historical fixes during this planning review.

## Crosswalk of the supplied TA backlog

| Supplied key | Reused/new Mote work |
|---|---|
| TA-01 | `bd-01M2TA01EHVRF6MQ1N00XTVK1K` (epic), `bd-01M2TA3780KD7C5PEQCEZ4ARSD` (hygiene), `bd-01M2TA3KKCFS707G2XJ9TS7ZCJ` (claims) |
| TA-02 | `bd-01M19G69RQCHMT2EMG11XFT4WX` (ci) |
| TA-03 | `bd-01M2WVCEBHAZBKJQ0EB84EA4HV` (baseline) |
| TA-04 | `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2` (scorer) |
| TA-05 | `bd-01M2WVENSB0P955Y0B603CC20Y` (records) |
| TA-06 | `bd-01M2WVH1DC8ZPDC992G4MX3TXG` (projection) |
| TA-07 | `bd-01M2WVENSB0P955Y0B603CC20Y` (records), `bd-01M2TAD04SR823TQVG9VPNH6R3` (decode) |
| TA-08 | `bd-01M2TACM78289S4TECE91GT5K2` (evidence), `bd-01M2TAD04SR823TQVG9VPNH6R3` (decode), `bd-01M2WVH1DC8ZPDC992G4MX3TXG` (projection) |
| TA-09 | `bd-01M2TADC4VKSDZ2S9SXETH2MYM` (command) |
| TA-10 | `bd-01M2TADC4VKSDZ2S9SXETH2MYM` (command) |
| TA-11 | `bd-01M2WVHF4B5DAXJYY4W91VK4WV` (exports) |
| TA-12 | `bd-01M2WVJ8HKX6EHG2C5CXYEZYJ6` (preview) |
| TA-13 | `bd-01M2TAB3QD2YXSPR8THYRGFBKS` (s0) |
| TA-14 | `bd-01M2TABFW72D8WWQF481SD37KW` (s1) |
| TA-15 | `bd-01M2TABVXSM72M9DD0SNB6GZ11` (s2) |
| TA-16 | `bd-01M2TAF8JH42NYJJAFWPD7HTZB` (s3) |
| TA-17 | `bd-01M2TAFN5WMXW4NT20YJQAW2FR` (s4a) |
| TA-18 | `bd-01M2TAG1GEAGXCY33P0B17QBJ1` (s4b) |
| TA-19 | `bd-01M2TAGDJ5F0FEPC3XPWVDSWDH` (s4c) |
| TA-20 | `bd-01M2TAGST4EBFR1HM9Q3EWSYAH` (filmplan), `bd-01M2T32SGVJXR2RT3RTS2DCN9P` (film) |
| TA-21 | `bd-01M2TAC80YRK5JFNKTT7B7CAQN` (d1b), `bd-01M1CQNM5DZZNWD8BN4WRKVZRQ` (filmproof) |
| TA-22 | `bd-01M1CQNM5DZZNWD8BN4WRKVZRQ` (filmproof) |
| TA-23 | `bd-01M19956MFSG7076QE4J66T7E9` (support), `bd-01M2WVENSB0P955Y0B603CC20Y` (records) |
| TA-24 | `bd-01M2WVH1DC8ZPDC992G4MX3TXG` (projection) |
| TA-25 | `bd-01M2TAEW794XCR7D2HZJFNXVJY` (calibration) |
| TA-26 | `bd-01M2WVGFA0J4D9T9ZBNR3MY2MR` (readouts), `bd-01M2WVHVVYQBKSBSW573HZYR9Q` (recovery) |
| TA-27 | `bd-01M2TA67DXJ2W0KX5N5DXSR1S2` (prereg), `bd-01M2TA6KFQV8ADNS3Z0ED4KYMW` (baselines) |
| TA-28 | `bd-01M2TAKEF1GHZVXDQ024EZDR0Y` (finaltest), `bd-01M2TAEW794XCR7D2HZJFNXVJY` (calibration) |
| TA-29 | `bd-01M2TAKTB61XNXV4Y6A9J3CB0C` (infra), `bd-01M2TAM66RTEMY3VVHA3N2MYXK` (hardening), `bd-01M2WVHF4B5DAXJYY4W91VK4WV` (exports) |
| TA-30 | `bd-01M2TAMJ95K9D7H248A8387YW4` (release) |

## Complete disposition of previously unfinished work

All 119 rows from the initial snapshot and all 17 issues in the concurrent intake commit appear exactly once. The superseded new clock ticket is separately accounted for in BACKLOG.json. “Landed” means the cited commit is reachable and its existing evidence was inspected; it is not a claim of a fresh test run. Superseded/retired closures do not claim implementation. Historical assignees are cleared through the CLI; Mote represents an explicit cleared assignee as an empty string.

| Mote | Lane / disposition | Priority | Work and remaining action |
|---|---|---|---|
| `bd-01M2TA3780KD7C5PEQCEZ4ARSD` | closed / completed-planning | 1 | Phase 0: tracker hygiene -- close landed beads against their own titles, move V1 to 1.x, retire the fleet epic — This reorganization accounts for all 119 prior unfinished issues; landed versus unlanded probe commits distinguished, V1 moved to 1.x, fleet epic retired, stale status/assignee fields cleared. Follow-up defect closures require their own evidence. Three unlanded anchors are explicitly retained; no wholesale closure of all twelve is claimed. |
| `bd-01M1DD1KVSVNK35C4Y9XPMSEHX` | closed / superseded | 2 | API ergonomics: progressive facade after the algorithmic core is verified — Superseded for the current product by bd-01M2TADC4VKSDZ2S9SXETH2MYM and bd-01M2TAM66RTEMY3VVHA3N2MYXK; acceptance now requires a real public mapping consumer, with broad unrelated ergonomic work deferred. |
| `bd-01M2WTH9R8SNCMVCS8V3F3RFW1` | closed / decided | 2 | C1 DECISION (owner): admission hashes -- one key (read from timebase-repair.json) or two keys (JSON plus a test-bound Scala literal) — Decision recorded in PLAN section 2 and ADR 0019: one reviewed committed JSON admission authority, with byte verification and bound record identity; arbitrary caller JSON is not trusted. Implementation remains on bd-01M2WTGF981AY7RCN8QC42JSPT. |
| `bd-01M2WTKP5PXP3FJEDWEQ0SWKHV` | closed / superseded | 2 | C1: Atlas carries each row's ClockRepair receipt, so a mapped row cites the derivation that placed it — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M2WTK7H8PPVWKC5E1406ARJX` | closed / superseded | 2 | C1: SherlockAnnotations.locusFor projects through ClockRepair.projectRunLocalSeconds instead of hand arithmetic — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M2WTJRM780AW4BSYYWXR3VMK` | closed / superseded | 2 | C1: apply the admission-hash decision to MediaManifest — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M2WTHRNHANY0JKHC0275ATMS` | closed / superseded | 2 | C1: capture a content-free baseline of sherlockRecallMap output on the real data root BEFORE any crosswalk change — Acceptance retained by the broader frozen baseline ticket bd-01M2WVCEBHAZBKJQ0EB84EA4HV, including repeated real-input output and row-locus digests. No second baseline task. |
| `bd-01M2WTJ7VEA16CY9BDBA7SVB87` | closed / superseded | 2 | C1: extend TimebaseRepair.Record with the edition parts and the scientific restrictions; build MediaManifest's measurements from it — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M2WTM4PYFHJ3XNG28WSZY2ZT` | closed / superseded | 2 | C1: one reusable loader for the admitted Sherlock record, called by every entry point — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M2WTGW0J5YRNZ8M0C1PDNNJK` | closed / completed-doc-correction | 2 | C1: the clockRepairs Scaladoc claims a production caller that does not exist -- make it true or remove it — The planning checkpoint corrects the false production-caller Scaladoc. Integration remains open on bd-01M2WTGF981AY7RCN8QC42JSPT; no production implementation claimed. |
| `bd-01M2WTMGV3C6HYRJN823JDBRWV` | closed / superseded | 2 | C1: verify -- byte-identical sherlockRecallMap output against the baseline, mutations, gate, cold review — Acceptance consolidated into canonical clock integration bd-01M2WTGF981AY7RCN8QC42JSPT. This closure removes duplicate scheduling, not implementation work. |
| `bd-01M1CQ2H7VPSKRT02M5H7HN1K6` | closed / superseded | 2 | CODE RED: executable Sherlock movie-to-recall P/F vertical — Superseded fleet-era CODE RED container by bd-01M2TA01EHVRF6MQ1N00XTVK1K. D1A, film, D1B, V1 and E0 remain linked to the active delivery epic; no product completion claimed. |
| `bd-01M2WTMWXY5QH2QDZ9DB7PCTMX` | closed / superseded | 2 | Gold rule: bind secondsPerTr and the alias rule across timebase-repair.json, SherlockSceneCoding.scala and gold_scene.py — TR and participant-alias single-authority and Scala/Python agreement witnesses move into scorer bd-01M2TA5FB4ATF9QS9ZA9MDJNE2, which edits the same boundary. No independent gold read is authorized by this closure. |
| `bd-01M19BN8T85XTDT3Z9AJW4820S` | closed / retired | 2 | ROLE: chief's deputy for derived state -- measure before the chief asserts — Retired chief-deputy fleet role under AGENTS.md SD4. Scientific evidence duties remain on implementation/release tickets; no code fix claimed. |
| `bd-01M1BZQCM0P8566KACRE28RVAR` | closed / landed | 2 | acquire: anchor CriticFinding construction probes to guarded source — Commit 19e8da05 is reachable from baseline main; affected tests inspected: acquire/src/test/scala/storymodel4s/acquireprobe/ConstructionBoundarySuite.scala, acquire/src/test/scala/storymodel4s/acquireprobe/ConstructionProbeSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M16B4NE7R5HHXT70GFW629ZT` | closed / landed | 2 | align: PopulationAggregate.of refuses an empty subject vector but nothing tests it, and it reports SizeMismatch for a non-mismatch — Commit e285fed5 is reachable from baseline main; affected tests inspected: align/src/main/scala/storymodel4s/align/matrix.scala, align/src/main/scala/storymodel4s/align/population.scala, align/src/test/scala/storymodel4s/align/PopulationSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M174W3D9AZG2FGSRM11FZ7ZQ` | closed / landed | 2 | align: harden MassRatio.unsafe against NaN — UNVERIFIED, the earlier "closed as a side effect" claim was never confirmed (signature.scala:212 takes the else branch on NaN and yields Some(NaN); caller validation unaudited) — Commit 01cfbc46 is reachable from baseline main; affected tests inspected: align/src/main/scala/storymodel4s/align/signature.scala, align/src/test/scala/storymodel4s/align/SignatureSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. Residual forged NaN operand storage is explicitly retained on the signature-migration ticket, not claimed fixed. |
| `bd-01M1A49W8H5MNJNKFXR84ZQFJP` | closed / landed | 2 | amr-interop: anchor AmrGraph construction probe to guarded source — Commit 6ca05d72 is reachable from baseline main; affected tests inspected: amr-interop/src/test/scala/storymodel4s/probes/AmrGraphUnforgeableSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1C1S0BRF8H7EX74CGQZVV1M` | closed / landed | 2 | codec: anchor sidecar construction probes to guarded types — Commit e6602356 is reachable from baseline main; affected tests inspected: codec/src/test/scala/storymodel4s/codecprobe/ConstructionProbeSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1C0XXGBNZ91S4N313JF0HE4` | closed / landed | 2 | document: anchor construction boundary suite to guarded types — Commit c52c641f is reachable from baseline main; affected tests inspected: document/src/test/scala/storymodel4s/documentprobe/ConstructionBoundarySuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1C3GHNJJ2XX4SQ4BV8042ZB` | closed / landed | 2 | embed-core: anchor construction probe suite to guarded types — Commit 8ec2acb1 is reachable from baseline main; affected tests inspected: embed-core/src/test/scala/storymodel4s/embedprobe/ConstructionProbeSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1C0DA89WDXC9H3VTKJ46R9A` | closed / landed | 2 | embed-grakern: anchor StructuralProgram construction probe — Commit d8d16a83 is reachable from baseline main; affected tests inspected: embed-grakern/src/test/scala/storymodel4s/grakernprobe/StructuralProgramBoundarySuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1BY93E78KHABT8CMEASZA4G` | closed / landed | 2 | proposition: anchor PropositionChart construction probe to guarded source — Commit b85ee23b is reachable from baseline main; affected tests inspected: proposition/src/test/scala/storymodel4s/probes/PropositionChartUnforgeableSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M1BZ5PR6T5G598S9QZWJBEWJ` | closed / landed | 2 | story: anchor StoryModel construction probe to guarded source — Commit aa07368b is reachable from baseline main; affected tests inspected: story/src/test/scala/storymodel4s/probes/StoryModelUnforgeableSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M2WTN8ZDYV4YYZRJH1RRHEJS` | closed / superseded | 2 | timebase-repair.json: bind the last three silent fields to the record itself, or declare them unverified — Retained-row, run-boundary and upstream-digest status acceptance moves into record integration bd-01M2WTGF981AY7RCN8QC42JSPT. Missing upstream verification must remain explicit. |
| `bd-01M1C2K4PW5F9YS43NZNJ8ZRXV` | closed / landed | 2 | view: anchor construction probe suite to guarded types — Commit bc6fd710 is reachable from baseline main; affected tests inspected: view/src/test/scala/storymodel4s/viewprobe/ConstructionProbeSuite.scala. Existing locally observed clean gate/mutation evidence: docs/plans/2026-09-17-one-point-oh-session-1.md and original issue review notes. No tests rerun in this planning pass. |
| `bd-01M19CK0DXBAQRWD6DSGN348SV` | closed / retired | 3 | DESIGN: how to run a board of coding agents with mote -- derive what you can, expire what you cannot, classify your blockers — Retired fleet-board design work under single-developer mode. Reopen only if the owner restarts a fleet; no Mote upstream defect claimed fixed. |
| `bd-01M2TA01EHVRF6MQ1N00XTVK1K` | container / rescope | 1 | DELIVERY: usable recall mapping and defensible organization measurement — Contains work with rel, not a dependency on every child. |
| `bd-01M199WBM2YVZ3GVC2GA61TYCJ` | container / retain | 2 | [container] EPIC: story output bundle + pure-HTML/CSS visualization spec (board consensus) — Nonblocking container, not a work reservation or an implementation dependency. |
| `bd-01M2T32SGVJXR2RT3RTS2DCN9P` | film / rescope | 1 | [film] D1A-film: compile a film source through the same deterministic stages — Ruling E is already decided: film compiler stays on 1.0 path. Phase plan resolves its ten concrete questions; no research-win prerequisite. |
| `bd-01M2TAGST4EBFR1HM9Q3EWSYAH` | film / retain | 1 | [film] D1A-film: write and cold-review its phase plan, then file its slice tickets — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TABFW72D8WWQF481SD37KW` | film / retain | 1 | [film] D1A-types S1: ADR 0007 amendment (rulings, vocabulary, canonical form, identity) — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TABVXSM72M9DD0SNB6GZ11` | film / retain | 1 | [film] D1A-types S2: core substrate -- sealed atlas, EvidenceSupport refusals, TypedSupport, PrimaryProjection, evidence-support/v1 — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TAF8JH42NYJJAFWPD7HTZB` | film / retain | 1 | [film] D1A-types S3: acquire carries TypedSupport (film-compiler slice) — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TAFN5WMXW4NT20YJQAW2FR` | film / retain | 1 | [film] D1A-types S4a: node support becomes TypedSupport; draft becomes fallible — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TAG1GEAGXCY33P0B17QBJ1` | film / retain | 1 | [film] D1A-types S4b: envelope, non-text identity, and the TextModel witness — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M2TAGDJ5F0FEPC3XPWVDSWDH` | film / retain | 1 | [film] D1A-types S4c: seal and split AlignmentSource — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M1CQKRG1A4J4BEWCC78F4TEZ` | film / retain | 1 | [film] D1A-types: film-capable StoryModel, nodes, Evidence and AlignmentSource, text byte-identical — Preserved approved film-library scope. Follow S0-S4c order and exact text parity; the earlier annotation preview does not close this ticket. |
| `bd-01M1CQNM5DZZNWD8BN4WRKVZRQ` | film / rescope | 1 | [film] D1B migrate SourceView and HsmmResult to movie intervals with full nomination and P F proofs — This is the end-to-end film proof half; signature work lives on D1B-signatures. Retain original exact nomination/P/F/foreign-identity/backward-revisit courts; reference readouts use the same exported support, with no unsupported fidelity claims. |
| `bd-01M2TAC80YRK5JFNKTT7B7CAQN` | film / rescope | 1 | [film] Phase 2: D1B signatures -- typed source view with exact playback intervals (replaces the joined-text surrogate) — Full public SourceView/HsmmResult signature migration follows S4c, not S2 alone; coordinate with compiler types rather than duplicating them. Preview uses checked anchored targets before this migration. Sherlock parity uses the admitted clock path; current support wire semantics govern. |
| `bd-01M19G69RQCHMT2EMG11XFT4WX` | foundation / rescope | 0 | G0: obtain an executed exact-SHA clean build and CI receipt — Historical successful local gates are retained as prior evidence, not a current CI pass. |
| `bd-01M2WTGF981AY7RCN8QC42JSPT` | foundation / consolidated-owner | 0 | G0: route Sherlock production coordinates through receipted ClockRepair — Canonical C1 implementation ticket; absorbs record, loader, hash application, locus, receipt and verification children. Baseline is bd-01M2WVCEBHAZBKJQ0EB84EA4HV. |
| `bd-01M2TAB3QD2YXSPR8THYRGFBKS` | foundation / rescope | 0 | [foundation] D1A-types S0: text parity baseline (compile, codec, view pins) — Text compiler/model/codec/view parity pins are independent of the benchmark campaign and start now; retain approved S0 mutations. |
| `bd-01M2TA2EMFCWRKXK6QJHGV702Q` | foundation / rescope | 0 | [foundation] Phase 0: rescue the only copy of audit/masc-role-corpus (b756b4bb) into the main repo — At-risk commit b756b4bb is still absent from main object DB. Preserve named refs and audit unique objects before any deletion; this is independent preservation work. |
| `bd-01M2TA5FB4ATF9QS9ZA9MDJNE2` | foundation / rescope | 0 | [foundation] Phase 1a: unit manifest and a fail-closed scorer with fixed denominators (gold_scene.py) — Preserve all existing fixed-population/unit-ID criteria; add separate timing eligibility, transcript-order eligibility and transition coverage. Synthetic load_arm probe on baseline retained 1 of 3 rows; no corpus gold read. Includes cross-language TR/alias agreement from the concurrent intake review. |
| `bd-01M2TA3KKCFS707G2XJ9TS7ZCJ` | foundation / rescope | 1 | [foundation] Phase 0: correct false and overstated claims in README and the recall-study docs — Correct active claims, including ClockRepair production-caller and RecallOrderControl shuffle-as-bias-correction sentences. Preserve historical outputs and label reconstruction vs reference honestly. |
| `bd-01M19W21RG7WY4E7Q4BDR9K7MH` | later / retain | 3 | [later] AGENTS.md: 1207 lines, +15% today, and its author broke two of its rules while writing it — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M2WTNN04AYE8GA7CG2ZWW4AA` | later / retain | 3 | [later] Coarsening admits non-contiguous grouping -- decide whether a contiguity-checked claim is wanted (ADR first) — Coarsening remains deliberately order-free. No defect or new public claim without a concrete consumer and ADR; unrelated to the preview. |
| `bd-01M1CQSWJ538PAEME49200YARR` | later / retain | 3 | [later] E0 (1.x): recall-map over a compiled film source -- the story-spine route, keeping E0's terminal courts — Ruling E: 1.x full film-model wire/terminal capability; separate from the initial mapping exchange preview. |
| `bd-01M2WTQ5PNB7RJ6NEAAAYCHYRY` | later / retain | 3 | [later] EPIC (ADR first): a canonical on-disk emission of Segmentation, SegmentLink and Coded, so consumers stop re-reading raw files — Canonical corpus interchange is distinct from mapping output. Park the ADR-first proposal until a concrete second consumer needs it; do not gate shared mapping evidence or preview on a universal intake format. |
| `bd-01M17MT2JC0TS46BH9RTYMEVQ7` | later / retain | 3 | [later] NFRD Baseball: verify and freeze the external diagnostic-court inputs — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M17FAGN83C3ZT2VZ2VKHWTYJ` | later / retain | 3 | [later] NFRD: primary-source inventory and observation-versus-derivation audit — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M17QW2A4S992ETKVWRTZKZKD` | later / retain | 3 | [later] OSF 5qxkh: non-vendored human detail-recall benchmark — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M17JFDHKHPD6N6JWA7NCMM4A` | later / retain | 3 | [later] OSF gptkz (Ost 2022): real human serial reproduction of War of the Ghosts - the story we already model — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M2TA1P0GXBHRF24W0VE7B66D` | later / retain | 3 | [later] Optional scope change P1 (parked; ruling E remains governing) — P1 is an optional future scope reduction, not a pending blocker. Ruling E governs this plan; no request to re-decide it is needed. Reopen active decision only if the owner explicitly proposes changing stable film scope. |
| `bd-01M1DB6J4VFETMCFG096713DCY` | later / retain | 3 | [later] P1B scientific Baseball proposition-to-narrative recall vertical — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M19XMM8BZ27KZWGX8R9PN5RE` | later / retain | 3 | [later] S4 output bundle: locally-openable HTML/CSS renderer contract extending CodexHtml — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M19XMQN4VK4WSTVSHBD3X1YB` | later / retain | 3 | [later] S5 output bundle: declared-lossy external exporters — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M1CQQDCWN2RTT1Z8XNNB7VMY` | later / retain | 3 | [later] V1 codec and renderer-neutral lowering for movie recall P F artifacts — Ruling E: 1.x full film-model wire/terminal capability; separate from the initial mapping exchange preview. |
| `bd-01M175Y5FEZJ661PPBSGJFT8RX` | later / retain | 3 | [later] docs: split rule 4 (548 lines, 37% of AGENTS.md at 1491 lines) into docs/EVIDENCE.md - the contract is no longer readable as one — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M15BM35EY8P8FXZ9K65MT9D8` | later / retain | 3 | [later] interview: Profile drops induced specificity and carries no contributor receipt — cannot descend from a profile number to DetailIds — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M15G280ZN0ZVNJEREVPJG1M5` | later / retain | 3 | [later] interview: export placement bases with evidence — PlacementBasis (Seed / ContinuityStay / ExplicitReturn(markerSpan) / ContinuityReturn(partnerUnit) / Unattached / Ambiguous) on InductionResult/ClaimMeta, not encoded by mass convention — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M19GPDP08NHEW2S64ZNYPFPE` | later / retain | 3 | [later] interview: per-metric membership exclusion (reading C) -- PlacementResolution is one summary per account, not one per metric — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M15G2835J1RK08G22JES7BHG` | later / retain | 3 | [later] interview: return-rule extensions — negation/reported-speech guard on ReturnMarker; resumption of an earlier (non-current) digression; ClusterContinuity consults recall relations (causal/temporal edges) — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M19HHV5P0A0MCNB4ZS9222Z7` | later / retain | 3 | [later] interview: the six membership-gated metrics have no conditional gate to abstain through — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M1606BEK9P4437GKVHFV1KPY` | later / retain | 3 | [later] interview: typed fractional placement-resolution summary (the vehicle every estimand fix needs) — Interview-specific scientific work remains valid but follows the mapping preview; reuse measurement policy when this work resumes. |
| `bd-01M1717JYQZ2T22PKQ1X1X5XFH` | later / retain | 3 | [later] mote-dx: a reservation can outlive its bead and ONLY its holder can free it - no chief override, blocking is unresolvable by anyone else — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M17W5DZWHA0RTV3Q8R5DGEV6` | later / retain | 3 | [later] mote-dx: ancestor_abandoned blocks descendants even when the abandoned commit is already in their base — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M1890HYQTY50KM80EB3C2ZKH` | later / retain | 3 | [later] mote-dx: only a candidate's proposer can retire it, so an expired proposer leaves a pending row that permanently blocks its own successor — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M183K7YXA46F66NMTEF8T3ZR` | later / retain | 3 | [later] storyatlas4s pins storymodel4s 50 commits behind main (3a6d73d8 at storyatlas4s/build.sbt:36), so sealing work on main does not protect the atlas — Outside the current preview path; preserve evidence and acceptance scope, resume only for a named consumer or demonstrated blocking defect. |
| `bd-01M17GC475EPDVW5Z4D776AYH0` | maintenance / retain | 2 | [maintenance] Add typed narrative relation licensing basis — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M199ABAG5SXR9C6Y0G4X6J2T` | maintenance / retain | 2 | [maintenance] Amend movie time and mapping contract before C1 — Core now has exact axes and mappings, but the six-distinction amendment and all listed mutation courts were not fully requalified here. Keep open; reconcile each against D1A S2 and the clock ticket. No automatic closure from type presence. |
| `bd-01M1998654XQRSNA6R16S2ABDQ` | maintenance / retain | 2 | [maintenance] CLI: Unicode text to source and surface bundle now; semantic markup waits for Stage 1b — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M1D215EY4T5BR0VRJ694AMBQ` | maintenance / retain | 2 | [maintenance] Characterize and govern Native HSMM 1-ULP posterior and flow divergence — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2TA2TWN5QS8PT01VW7JX234` | maintenance / retain | 2 | [maintenance] Phase 0: free disk by removing merged worktrees and redundant clones (owner OK required first) — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2TA3ZEEGAE07PEVDQR94HX5` | maintenance / retain | 2 | [maintenance] Phase 0: make the checkAll alias run correctness before formatting — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2TA4BFRMK8WNA79PPRT8YCD` | maintenance / retain | 2 | [maintenance] Phase 0: record the FilmFestival media -- a content-free hash manifest and corrected excerpt boundaries — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2TAEG8RBTA6DQK474W4GEDR` | maintenance / retain | 2 | [maintenance] Phase 2: LLM score-contract pilot (a separately counted development arm) — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2TAE44D7S8T0RHGH6FWWVP8` | maintenance / retain | 2 | [maintenance] Phase 2: label-producing provider interface, and the R1 winner behind it (n/a under R2) — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2WTPDBB4MQBXEJ181Q85ZG2` | maintenance / retain | 2 | [maintenance] Retrieve the undelivered remainder of the post-fix cold review: MINOR A3, the rest of C1, the refuse-to-merge verdict — Retain the incomplete intake-review scope as a bounded cold review of A-C. The current planning review does not certify the missing findings. |
| `bd-01M17MET0E4TWW304YKXPHBSBG` | maintenance / retain | 2 | [maintenance] a real proposition provider: 50 of 50 sentences, not 4 — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M17010MM68DG7B49DMEKA2AA` | maintenance / retain | 2 | [maintenance] acquire/core: there is no admitted-story loader - provenance headers have no machine-readable boundary, so every consumer writes its own parser — Corpus readers do not by themselves prove that the admitted-story header/text boundary is fixed. Keep open for a direct WOG-loader/header checksum witness; do not build a second corpus framework. |
| `bd-01M17ZNXY6AS1CMBQJRH3JMNVX` | maintenance / retain | 2 | [maintenance] align: CostBreakdown and five sibling types are forgeable via Mirror.fromProduct, so every AlignWire invariant is advisory outside package align — Original six-type report predates sealing work; re-inventory every named type at the implementation SHA and probe from an external package before changing code. Retain unresolved holes only; do not repeat already-landed conversions. Mandatory for the surfaces exported as checked. |
| `bd-01M1C6XV4F99RVRWCTF3YX16G7` | maintenance / retain | 2 | [maintenance] align: anchor macro-only construction probes — NOT landed: 4c09362c exists on salvage/candidate refs but git merge-base --is-ancestor 4c09362c HEAD is nonzero, and current probe paths lack these anchors. Recover/review current diff, prove incremental invalidation with mutation, gate, then land. The nine landed probe closures do not cover this ticket. |
| `bd-01M17AGFAVE90MA9QS50V0FTZD` | maintenance / retain | 2 | [maintenance] align: support accessor name erases count-vs-mass grain — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M16E05TWX28QKJF2GFY67ZZV` | maintenance / retain | 2 | [maintenance] align: the remaining 14 unmigrated RecallSignature fields (ADR 0003 backlog) — Preserve ADR 0003 signature migration. Explicit residual from MassRatio polarity fix: forged NaN operands still stored although ratio abstains; inspect construction/consumer consequences before claiming closure. New organization readouts must use explicit counts/denominators rather than inheriting these fields blindly. |
| `bd-01M19NYZT593MXWEERP80RHY8J` | maintenance / retain | 2 | [maintenance] align: which parameterised enum cases carry an invariant construction could violate - judgement pass over the receipt-reachable surface — Whole-surface invariant judgement remains unexecuted here. Prioritize receipt/result-reachable types at the current API boundary; broad inventory is maintenance, not a blocker for unrelated local evidence extraction. |
| `bd-01M16DBEH9PKER423BZ47ZKBMV` | maintenance / retain | 2 | [maintenance] features: Estimate cannot express Ineligible - the eligible/missing distinction is portable and currently exists only in embed-bench — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M1C4Q11FWAY7MZFNNMXTTJEJ` | maintenance / retain | 2 | [maintenance] features: anchor construction boundary suites to guarded types — NOT landed: 658e3044 exists on salvage/candidate refs but git merge-base --is-ancestor 658e3044 HEAD is nonzero, and current probe paths lack these anchors. Recover/review current diff, prove incremental invalidation with mutation, gate, then land. The nine landed probe closures do not cover this ticket. |
| `bd-01M1C5Y9ESYXAFNT91BVBTKZB9` | maintenance / retain | 2 | [maintenance] interview: anchor macro-only construction probes — NOT landed: 50eea3a7 exists on salvage/candidate refs but git merge-base --is-ancestor 50eea3a7 HEAD is nonzero, and current probe paths lack these anchors. Recover/review current diff, prove incremental invalidation with mutation, gate, then land. The nine landed probe closures do not cover this ticket. |
| `bd-01M16TYAMC8TA58V0TW6CJET6Y` | maintenance / retain | 2 | [maintenance] policy: every empty-guard constant must declare its class (forced / delete-sentinel / conservative / flattering) — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M1836WDBB72KXXPKG23PSSWT` | maintenance / retain | 2 | [maintenance] probe coverage: 12 of 21 construction-boundary suites test the companion fromProduct door but not the Mirror.ProductOf summon door — Keep open: nine dependency anchors landed but three did not; Mirror.ProductOf coverage is a separate property from dependency anchoring. Audit actual remaining named doors; no blanket sweep completion claim. |
| `bd-01M168TZ3VRQYSE7TNT5JKJTSJ` | maintenance / retain | 2 | [maintenance] recall: segmenting a PseudonymizedTranscript loses its certification - RecallGraph carries no privacy witness — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M1CGP2K4H93WM8CW5EFEPTJK` | maintenance / retain | 2 | [maintenance] reference-scope: fail closed on declaration-free module API changes — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M19N0W937KVZCK78F6X57X1D` | maintenance / retain | 2 | [maintenance] sweep: enumerate CONSTRUCTIBLE PRODUCT TYPES WITH INVARIANTS, not case classes -- 422 parameterised enum cases were never in any population — Retained with its original acceptance scope; recheck against current code before implementation or closure. Not a prerequisite for the annotation-assisted preview. |
| `bd-01M2WTP16RXN7S7SZ25JZ4PQ7F` | maintenance / retain | 3 | [maintenance] Rename B1ProbeSuite.scala to SegmentLinkCompositionSuite.scala (file and class disagree) — Keep the test-file rename as an optional narrow cleanup; it does not gate the preview or scientific claim. |
| `bd-01M2WTPSFGC31SJ3GMMQT4806M` | maintenance / watch | 3 | [maintenance] Watch: corpusNative compileIncremental failed once after module cleans and did not reproduce — A single unreproduced compile failure is a watch item, not an inferred race or automatic release block. On recurrence retain the full log and exact tree. |
| `bd-01M2TAD04SR823TQVG9VPNH6R3` | next / rescope | 1 | G1: extract named structured reconstruction and decision policies — Supersedes generic decoder extraction only; H2 is later reconstruction research. |
| `bd-01M2TACM78289S4TECE91GT5K2` | next / rescope | 1 | G1: extract shared local mapping evidence and receipted scoring channels — Removes D1B/benchmark-checkpoint prerequisite: this extraction uses existing checked target support; full film signature migration remains D1B. |
| `bd-01M19956MFSG7076QE4J66T7E9` | next / retain | 1 | G1: honest support assessment before shared evidence and confidence — Foundation prerequisite of shared local evidence and the preview. Baseline capture precedes this migration; remove the old D1B prerequisite. Retain typed external/empty-eligible NotApplicable semantics and full wire migration tests. No empty eligible set may advertise fully supported. |
| `bd-01M2TADC4VKSDZ2S9SXETH2MYM` | next / rescope | 1 | G2: typed mapping facade, explicit config and safe batch publication — The checked record/codec is the records ticket; full HSMM invocation authority stays on the existing invocation ticket. |
| `bd-01M1DA6NJXYT4NEA18745FM3KY` | next / rescope | 1 | [next] P0 align: bind HsmmResult totals to an admitted cost-model invocation receipt — Preserve custom-cost authentication protocol and recomputable-default-total acceptance; it gates invocation-authenticated/calibrated HSMM claims. Strict reference uses a bounded registered checked producer; unknown custom provenance must refuse reference compatibility. No universal authentication subsystem required before preview. |
| `bd-01M2TA2283FC7FSA2G7TJ1WSMN` | owner / rescope | 1 | OWNER: choose the CI account or runner route (push already complete) — Operative scope and acceptance criteria replaced by the bounded current delivery contract. |
| `bd-01M2TA0G1VSRQ992HJFWP13ZCM` | owner / retain | 2 | OWNER: may participant recall prose go to hosted model APIs (per corpus), and at what spend? — Owner/data decision retained only for its named downstream track; it does not gate the local preview. |
| `bd-01M2TA0X79Y4BQZP0B6AT919C9` | owner / retain | 2 | OWNER: people and protocol approval for second human codings (human ceiling) — Owner/data decision retained only for its named downstream track; it does not gate the local preview. |
| `bd-01M2TA19PH7GDT1Q2WXEKD6RE2` | owner / retain | 2 | OWNER: supply the Catch Me If You Can and The Prisoner excerpts (and Memento media if T4 is wanted there) — Owner/data decision retained only for its named downstream track; it does not gate the local preview. |
| `bd-01M184XP908JX51S3ZYFX20HC7` | owner / retain | 2 | [owner] Q5 owner-action list: four remaining dispositions (pyannote terms, MF2, FilmFestival REB, recall-corpus REB) — Owner/data decision retained only for its named downstream track; it does not gate the local preview. |
| `bd-01M2TAM66RTEMY3VVHA3N2MYXK` | release / rescope | 2 | G5: harden the public mapping facade and actionable errors — Supersedes the broad progressive-facade request for this delivery; unrelated API ergonomics remain later. |
| `bd-01M19FPY1EC5QNTW6SBG3QYT3R` | release / retain | 2 | [release] DOCS: complete current reader journey and visual qualification — Astro site exists and September 17 records executable examples. Remaining: current reader journey/claims and visual qualification; deployment is the release-infrastructure ticket. A fictitious tech-writer fleet seat is no longer required. |
| `bd-01M2TADR4KM1S4J46HK2C3BN76` | release / rescope | 2 | [release] Phase 2: recall-map HTML report and benchmark migration; retire run-arm.sh — HTML/migration remains useful after the table/API preview. Do not delete historical serializers or run-arm.sh until each historical arm has a replay/parity replacement; no preview dependency on viewer or benchmark campaign. |
| `bd-01M2TAMJ95K9D7H248A8387YW4` | release / rescope | 2 | [release] Phase 4: 1.0 release gate and stability table — Retain ruling E film compiler/types/D1B and public stability requirements. Add the reference analysis preview, support honesty, compatibility and deterministic recovery gate; empirical superiority/optional provider/calibration wins are separate claim gates. Remove mandatory empirical calibration/label-provider prerequisites; unavailable calibration must be explicit. |
| `bd-01M2TAKTB61XNXV4Y6A9J3CB0C` | release / retain | 2 | [release] Phase 4: release infrastructure -- publishing, MiMa baseline, deployed docs — Stable-release work follows preview and the preserved film API commitment; superiority remains a separate claim gate. |
| `bd-01M2TA53FEKFQY7R4XC48BZMR8` | research / retain | 2 | [research] Phase 1a: admit Memento, fix its task, and seal a condition-stratified test split — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA4QG638M6ZH8Q23CAK401` | research / retain | 2 | [research] Phase 1a: draw and seal the Friends participant-level test split — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA7BM74N2WKAQJA5EA4X2R` | research / retain | 2 | [research] Phase 1a: hosted arms H1 (frontier model) and J1 (Jev), only after the owner's hosted-API decision — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA6ZHJGYPCYQBAN15PFZ0D` | research / retain | 2 | [research] Phase 1a: local LLM arms L0-L3 on Sherlock, L1/L3 on FilmFestival — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA5VFE5X7E4SP1AM4HJ530` | research / retain | 2 | [research] Phase 1a: local pinned LLM runtime, gold-free model and prompt pilot, provenance receipt — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA67DXJ2W0KX5N5DXSR1S2` | research / retain | 2 | [research] Phase 1a: pre-register the Phase 1a comparisons in the study-log ledger before any gold read — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA7QJW98EJ9JQ3T447HZK7` | research / rescope | 2 | [research] Phase 1a: readout and owner checkpoint — The comparison checkpoint selects optional provider/reconstruction investments under preregistered rules. It no longer decides whether shared evidence, reference measurement, public mapping extraction or film engineering may start. |
| `bd-01M2TA6KFQV8ADNS3Z0ED4KYMW` | research / retain | 2 | [research] Phase 1a: retrieval baselines B0-B4 and B1t/B2t on Sherlock; film identity on FilmFestival — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA8VM5B6XQWR6CVH25HB7V` | research / retain | 2 | [research] Phase 1b: FilmFestival clock work for the scene-within-film task — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA8FJYASVK1WNBQZ26ASTG` | research / retain | 2 | [research] Phase 1b: benchmark v1 leaderboards regenerated by one command — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA97MQMGHTPQK3VYRXW9HG` | research / retain | 2 | [research] Phase 1b: evaluate RecallSegmenter against Friends development gold; add a gold-unit oracle row — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAABPTGFSQMNG0G9G1D9XM` | research / retain | 2 | [research] Phase 1b: published comparator -- Heusser, Fitzpatrick & Manning (2021) on Sherlock — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAAQNS7RCCBBM8WFFZF6ZZ` | research / retain | 2 | [research] Phase 1b: repair the instruments -- risk-coverage on the emitted decision; retire agreement as a decoder judge — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA83GA06RRHR193AV3XYA5` | research / retain | 2 | [research] Phase 1b: task, track and estimand fields in the corpus descriptor (schema only) — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TA9ZK64GTG3526RMNWNHCT` | research / retain | 2 | [research] Phase 1b: the within-scene human lane (about 90 minutes of one person's answers) — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAHHXRTZPV9JWCKBXT6KVE` | research / retain | 2 | [research] Phase 3 H1: language-model scoring through the decode — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAHYA8QWB9QXBX759KR82N` | research / rescope | 2 | [research] Phase 3 H2: transfer-safe decoding — Reconstruction optimization only. A participant-adaptive order penalty cannot turn its own decoded organization into reference measurement. Compare reference/reconstruction on fixed evidence and report recovery as well as localization; sensitivity is not bias correction. |
| `bd-01M2TAJABHFWN5ZDYNST15Y9G5` | research / retain | 2 | [research] Phase 3 H3: coder-independent inputs (dialogue, captions, crowd) and the independence ablation — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAJPFF1GK8YKPN5PNWM7TF` | research / retain | 2 | [research] Phase 3 H4: confidence and abstention — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAK2EFCDWR2A01Q3XSNS79` | research / retain | 2 | [research] Phase 3 H5: structural-spine pilot, with the s43 validation fix — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAH5VA3WJBGR8WKAAH14Z8` | research / retain | 2 | [research] Phase 3: development-climb protocol and the exposure / read-count ledgers — Research comparison retained; frozen exposure/eligibility rules apply. Not an engineering prerequisite or proof of behavioral recovery. |
| `bd-01M2TAKEF1GHZVXDQ024EZDR0Y` | research / rescope | 2 | [research] Phase 3: final freeze and the single test opening (Friends, Memento) — Keep one sealed opening/exposure guard and preregistered frozen comparison rules. This is a claim gate, not a shipping gate. No final-test read occurred in this planning exercise. |
| `bd-01M2TA9KKVFWPQ74HN90P0SM68` | validation / rescope | 2 | G3: independent human coding and organization-recovery study — Extend the existing second-coding study to organization recovery; freeze sampling/tolerances before reads, include real reverse/revisit and repeated-occurrence recalls, permit ambiguity/multiple occurrences, no chronology-enforcement instruction. Measure candidate/localization/specificity and transition/group-effect recovery; independent annotation is not automatically truth. |
| `bd-01M2TAEW794XCR7D2HZJFNXVJY` | validation / rescope | 2 | [validation] Phase 2: reliability contract for the final emitted decision — Retain empirical fit/evaluation separation and event-specific confidence. Bind context, candidate, decoder, fill, grain, population and projection policies; unit calibration does not certify transitions. An uncalibrated release remains allowed; any certified profile waits for this evidence. |

The concurrent intake review was committed as `46d476e720ac715a247619b615aa32cab792935d`. Its clock micro-tickets are consolidated into C1, its TR/alias agreement witness into the scorer, and five distinct later/maintenance findings remain open. No implementation closure is inferred from consolidation.

## Cold review and execution record

SD6 fresh-context design review identified five contract gaps: normalized score semantics, exported assumption evidence, multipart preview identity, the old order-ablation recommendation, and unbounded ambiguity-solver scope. All were corrected. Its graph pass found no cycles or research/film prerequisite on the preview. Two further tracker contradictions (support dependency wording and human agreement mislabeled as a ceiling) were corrected. This is specification review, not independent empirical reproduction.

This pass ran a synthetic scorer-drop probe (3 input units, 1 retained; no real gold read), verified code identity outside edited Scaladoc blocks, inspected reachable versus salvage commits, checked live remote equality and CI startup annotation, and ran formatting checks. Implementation suites/recovery studies and real-data clock parity remain future ticket gates.

The intake delta review added the complete per-row MediaLocus digest witness, covering rows unused by a chosen recall. Final verification matched live scalar state and nine critical ticket bodies/dependency sets, checked the planned DAG and preview isolation, and passed Mote doctor with a clean store. Formatting passed; executable Scala content is unchanged. Full runtime gates, real-data parity and recovery studies were not run.

Immediate ready foundation queue at this checkpoint:

- `bd-01M2TA2EMFCWRKXK6QJHGV702Q` — [foundation] Phase 0: rescue the only copy of audit/masc-role-corpus (b756b4bb) into the main repo
- `bd-01M2TAB3QD2YXSPR8THYRGFBKS` — [foundation] D1A-types S0: text parity baseline (compile, codec, view pins)
- `bd-01M2WVCEBHAZBKJQ0EB84EA4HV` — G0: freeze mapping regression inputs, configurations and historical outputs
- `bd-01M2TA3KKCFS707G2XJ9TS7ZCJ` — [foundation] Phase 0: correct false and overstated claims in README and the recall-study docs
