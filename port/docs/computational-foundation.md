# First computational slice

This slice implements a small, inspectable account and the operations needed to test its
semantics. It does not yet extract an account from arbitrary text or reproduce the full
Scala mapper. The owner has prioritized computation over visual refinement.

| Area | Implemented boundary | Remaining work |
|---|---|---|
| Text and identity | Document hashes, edition/session keys, exact discontinuous UTF-16 spans, deterministic pilot IDs | Scala canonicalization/identity compatibility beyond the legacy reader |
| Narrative structure | Source and recall events, evidenced participant roles, rooted attributed contexts, typed relations and sparse cycle checks | Full situation/context/ontology vocabulary, recall-local referent identities and full graph inference |
| Recall detail | Occurrence-grounded atoms, explicit inventory completeness, separate address/discourse/fidelity | Independent segmentation and extraction; cross-unit duplicate-content/repetition inference |
| Correspondence | Single/joint/broad/external hypotheses, alternatives, separate narrative and referent interpretations, missing processing states | Candidate generation, local scoring, hierarchical alignment/graph-HSMM inference |
| Analysis | Two versioned counting policies; fixed eligible source-row universe; supported-only definite coverage | Full ADR 0030 estimators and scorecards; no calibrated probabilities or scientific qualification |
| Time | Rational clocks and interval overlap, held-level versus uniform-amount allocation, uncovered bins stay missing | Point events, coordinate transforms and integration with feature tracks |
| Replay | Input/profile-bound recorded replies, bounded dispatch callback, journal before dispatch, atomic raw reply capture, offline replay | Provider adapters, integrated extraction/critic/resolver workflow and operational stale-lock recovery |
| Legacy | Existing Python StudyBundle v2 reader migrated, fields/outcomes retained, required external byte pin | Independent reconstruction from original source inputs and rich-to-legacy writing |
| Delivery | Installed API/CLI, generated JSON Schema, refused exports and overwrites, React/Vite build scaffold | Runtime browser admission, generated TypeScript, actual inspector, persistence, accessibility and export UX |

## Representation choices

Keep one Python package with ordinary modules: `model` owns admission, `analysis` derives
tables, `temporal` allocates measured values, `execution` records text transports, `pipeline`
admits recorded complete-account proposals, and `legacy` handles compatibility. Public
operations revalidate notebook objects, including copies that bypass constructor checks.
Frozen objects are conveniences; they are not Scala-style unforgeability.

An account owns documents, units, contexts, events, details and bindings. References use
IDs with explicit contextual checks. Source records derive only from source inputs; the
paired account can add recall without changing source occurrence IDs. The current entity
subset uses source-local identity and does not yet model unknown recall-only participants.
Relations never silently promote attributed events into the narrated world.

Analysis has recall-detail rows, correspondence rows and source coverage rows, plus an
inventory row for every recall unit. A missing or partial inventory, or recall units that
do not cover the full recall document, makes unproved source omission not-assessable.
This initial completeness rule conservatively includes whitespace gaps. Source rows are
leaves; only scene containers can parent source units. A completed empty inventory is
distinct. Broad and alternative
references cannot earn definite row coverage. Positive supported evidence can still prove
presence when other extraction or reference work is incomplete.

The two counting policies reproduce the admitted worked example's independent three-versus-
two detail expectations. This checks policy application to a supplied account; it is not
evidence that an extractor recovers the correct account. Fidelity never determines target
address or whether a detail is internal. Changing policy does not modify the account.

## Replay from Python

```python
from pathlib import Path
from storymodel.execution import RecordedRun, RunProfile
from storymodel.pipeline import replay_account

# Documents come from independent intake, not from the proposed response.
profile = RunProfile(
    provider="fixture", model="recorded-account/v1", endpoint="local:fixture",
    max_tokens=4096, recipe="admitted-worked-example/v1",
    input_sha256=tuple(document.sha256 for document in expected_documents),
)
run = RecordedRun(Path("data/recording"), profile)
account = replay_account(run, prompt, expected_documents=expected_documents)
```

Here `expected_documents` and `prompt` are caller inputs. Replay has no transport and cannot
dispatch a model request. `RecordedRun.run(..., transport=callback, max_new_calls=N)` is the
explicit recording API. It stores raw responses before parsing. Uncertain prior attempts
without replies refuse retries. A stale exclusive lock after process death requires operator
inspection; this first slice does not automatically delete locks or promise exactly-once
remote execution. Recordings and checkpoints are trusted local files, not signed artifacts.

## Evidence and next computation

The synthetic narrative text is reused from the independently admitted detail-coding v2
example; admission is recorded in `src/storymodel/demo.py`. Adversarial tests use spans and
structural mutations of this admitted account. Legacy tests consume existing committed
synthetic Scala bundles without copying their narrative text into the port.

Next, implement independent source/recall intake and segmentation, then typed extraction
proposals and deterministic acceptance. Follow with candidate generation and the smallest
alignment algorithm that reproduces pinned Scala cases. Keep unsupported interpretations
explicit. Compare matched edit/test cycles only after these algorithms have comparable
behavior; current Python test latency alone does not establish a development speedup.
