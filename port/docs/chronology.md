# Story-world chronology

Chronology records assertions about when situations occur relative to one another. It is
separate from the order of observations, transcript spans and measured playback support.
An interval claim does not invent an onset or duration. Existing `temporal.py` continues to
own exact measured-time allocation; `chronology.py` owns symbolic interval constraints.

## Construct, inspect and consume

Use `TemporalProposals` with the actual account digest, origin and procedure, then call
`admit_temporal_relations(account, raw)`. Each proposal names two situations, an explicit
context, its epistemic status, exact text quotes or native playback evidence, and any upstream
premise IDs. Admission is atomic: one invalid claim rejects the batch without changing the
input account. Failed/unattempted processing preserves the input; search-limited processing
can retain a valid partial batch. `verify_temporal_relations` recomputes from independent inputs.

Successful admission emits a v7 rich account. `upgrade_temporal_account` also explicitly
upgrades an existing rich account while preserving occurrence IDs and graph collections.
V4–v6 wires reject the new field even when empty. Old `Relation` recipes and identities remain
unchanged; legacy `before` records retain unspecified epistemic metadata.

`temporal_table(account, context, policy=...)` exposes exact evidence and provenance.
`query_temporal_relation(account, source, target, context, policy=...)` returns the feasible
atomic relations conditional on the selected assertions. Thirteen possibilities means the
pair is unconstrained, not that a provider explicitly asserted `unclear`. The query separately
retains direct and constraint claim IDs. `verify_temporal_query` binds the result to the
account, endpoints, context and policy.

Run the installed API example from `port/`:

```sh
python examples/chronology_workflow.py data/my-chronology-run
```

Choose a new destination. The example preserves one source across two recalls, composes legacy
`A before B` with new `B meets C`, and consumes the new edge in account-derived inference.
It also constructs and queries document-free native-media chronology. These are declared
abstract controls, not claims about extraction accuracy.

CLI equivalents are `admit-temporal`, `verify-temporal`, `temporal-table`, `query-temporal`
and `verify-temporal-query`; use `--help` for file arguments. `--adjudicator` is independent
caller attestation, not authenticated proof that a human reviewed the claim. A provider reply
cannot supply that metadata itself. Export permissions apply before supplementary file reads.

## Semantics and correctness

The thirteen atomic Allen relations are accepted, with inverse-only directions canonicalized
by swapping endpoints. Equal and unclear pairs use stable endpoint order. Stored canonical
relations are before, meets, overlaps, during, contains, starts, finishes, equal and unclear.
New semantic duplicates are refused, including equivalent during/contains directions.

Each proper interval has `start < end`. Equality contraction and a sparse strict-order DAG
decide consistency of the complete union of legacy and new constraints in each exact context.
No dense all-pairs table or transitive closure is stored. Queries test thirteen possible
relations on demand. Contradictory endpoint equalities and strict cycles are refused.

An edge context must be at or below both endpoint contexts. A belief may order root situations
differently from root chronology; queries never inherit parent or child assertions implicitly.

All six statuses are retained: surface-explicit, linguistically-entailed,
world-knowledge-inferred, structurally-derived, hypothesized and human-adjudicated.
Surface-explicit claims require direct evidence. Inferences require existing scoped premises;
their premise graph is acyclic. Structural derivations additionally must be uniquely entailed
by their explicitly cited temporal premises in the exact conclusion context. Situation
provenance, uncited facts and cross-world constraints cannot fill a missing proof. Linguistic
and world-knowledge inference remain attributed declarations rather than solver-certified truth.

`TemporalPolicy` selects statuses and whether legacy assertions are eligible. Excluding a
temporal premise also excludes its dependents transitively. A derivation from a hypothesis is
conditional on that hypothesis; the derivation does not upgrade its truth or probability.
Non-temporal provenance is retained without being treated as an interval constraint.

## Computational consumers and limits

Opt into `SourceProjectionPolicy(recipe="account-source-layers/v3")` to consume new chronology.
Explicit before/meets assertions in the narrated context become structural WorldTime edges;
the complete account still retains other relations and their evidence. All admitted statuses
are included by this projection recipe, conditionally; edge weights are structural, not
confidence estimates. V1/v2 refuse accounts with new temporal claims rather than ignoring them.

Native construction, tables and temporal queries work. Native numerical mapping and assessment
remain owed. This slice does not add measured clock mappings, trajectory classifiers, causal
families, calibrated credence or automatic linguistic entailment. Scala relation/validator
sources guide preservation; executed independent interval tests establish local algorithmic
evidence, not full Scala parity or scientific validity.
