# Mission

Deliver a usable, reproducible system for modeling written and audiovisual narratives, modeling their recall, and exporting evidence-grounded recall-to-source mappings for downstream data analysis.

## The delivery contract

For a versioned source representation and a recall transcript, the system must produce a versioned mapping artifact that accounts for every requested recall unit. Its public views must locate the corresponding recall words and any measured recall timing; identify source referents and their text or playback support; retain alternatives and non-localizable outcomes; and state the inference method, decision rule, support coverage, and calibration status.

The product must support two interoperable source-building routes: a lightweight route from checked, anchored annotations, and a narrative-model route that compiles source evidence into a richer story representation. Both feed the same public mapping and analysis contract. Annotation-assisted video mapping must be identified as such. A claim of automatic video-derived modeling requires an executed path from audiovisual material, not merely matching against human scene descriptions.

## Commitments

### 1. Keep source, evidence, and interpretation separate

Pin source bytes or externally supplied source identities, editions, streams, coordinate axes, annotations, provider outputs, and transformations. Preserve the distinction between presentation time and story-world chronology. Derived caption or annotation text is not canonical film content. Build source representations independently of the recall outputs used to evaluate mapping.

### 2. Make addressing exact and precision honest

Preserve exact text spans, stable word identifiers, and edition-bound playback coordinates. Store exact timebases and ticks as authoritative; decimal seconds are derived conveniences. Preserve discontiguous supports, uncertain occurrence identity, and missing timestamps. Never infer a word offset from the next word's onset without an explicit estimation policy.

### 3. Keep the useful path small

A researcher can perform basic localization using anchored units without complete semantic parsing, complete event graphs, or an external visualization application. Rich semantics, structural comparisons, and trajectory models are capability-bearing enrichments rather than universal prerequisites. The approved film-capable library path remains an explicit delivery commitment, not an indefinitely experimental branch.

### 4. Distinguish localization, fidelity, and processing status

A recall may refer to an event and distort it. Missing propositional evidence cannot establish fidelity or distortion. Missing candidates do not establish intrusion. Provider failure does not describe the rememberer's behavior. Represent each distinction in data, not only in prose warnings.

### 5. Preserve one result with explicit views

Raw scores, posterior or transport measures, discrete decisions, imputation, temporal projection, and trajectory summaries have named derivations. A decoded anchor is never accompanied by another anchor's confidence without an explicit label. A post-processing step cannot silently rewrite the original posterior. Multi-event recall is not misrepresented as uncertainty between mutually exclusive events.

### 6. Expose uncertainty without overstating it

Declare the meaning and normalization of every measure. Model posteriors are conditional on the model, candidate set, source representation, and policy. Empirical calibration requires a separately identified evaluation protocol and a calibration artifact bound to the relevant pipeline and decision. Uncalibrated outputs remain usable for declared exploratory purposes and are always labeled.

### 7. Make analysis outputs part of the core product

Publish documented tables, sparse relations, and machine-readable manifests. Provide a working example that loads a mapping in R or Python, recovers discrete and probabilistic views, and checks accounting and coordinate invariants without reading internal Scala implementation code. Derived measures name their denominators, support policies, and assumptions.

### 8. Prefer deterministic validation at the boundaries

Providers propose evidence or interpretations. Checked constructors and validators enforce identity, membership, coordinate, referential, and serialization rules. Validation does not establish scientific truth. Preserve useful partial outputs with explicit readiness and failures; do not fabricate completeness or require irrelevant semantic layers for a basic localization request.

### 9. Make operation reproducible and economical

Use explicit, versioned configuration; resolve it once per run. Cache reusable source-side work and content-addressed provider exchanges. Make batch failures local, retries budgeted, and completed artifacts verifiable. Offline replay and no-network modes must be enforceable. External transmission of participant content requires an explicit permitted policy for the relevant data.

### 10. Evaluate the entire inference-to-analysis path

Freeze input and gold eligibility independently of predictions. Include every eligible failure and abstention in the appropriate primary denominator. Match comparisons by stable unit identity and participant population. Separate annotation-assisted, transcript-only, and audiovisual-derived input tracks. Test reorderings, silent visual events, ambiguous summaries, missing timing, multiple editions, and insufficient source coverage, not only favorable examples.

### 11. Separate delivery evidence from scientific claims

Maintain a current capability/status record with commit, command, test or example, result, and limitations. A passing unit test, a historical local execution, a clean-machine reproduction, and a held-out scientific validation are different achievements. Engineering may proceed without winning a research comparison; claims of accuracy or superiority may not proceed without the relevant evidence.

### 12. Converge rather than accumulate parallel products

Production inference belongs in library and orchestration modules, not in benchmark scripts. Benchmarks consume the public API. One active delivery plan, a dependency-ordered tracker, and explicit acceptance artifacts govern the current release. New abstractions must close a named delivery gap or remove demonstrated duplication.

## Release scope and scope changes

The accepted film-capable library requirement is preserved: a film source can be compiled through the API, recall can be aligned against it, and results preserve exact playback support. Lightweight mapping previews can precede that completion but cannot be represented as completing it.

The accompanying turnaround plan proposes an earlier generic analysis preview and documented exports. That is a proposed sequencing change; it does not claim that previously deferred film-bundle or corpus-specific terminal interfaces have already been approved for the stable release.

Current implementation status belongs in the capability/status record, not in this mission. Unimplemented features must be marked there and must not be advertised as available.
