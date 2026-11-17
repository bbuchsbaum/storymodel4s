# Conditional organization readouts

Organization describes transitions among recalled targets under an explicit numerical model.
It does not establish the correctness of the reference mapping. An order-sensitive inference
model cannot serve as independent evidence of recall organization merely because its posterior
is normalized. Python preserves that boundary with distinct conditional-result labels.

`organize_accounts(source, recall, evidence_spec, order, inference=...)` consumes the existing
account inference's adjacent **joint flow**, combines faithful and distorted modes by target,
and retains the entire inference artifact. Supplying a saved inference recomputes it against
the accounts and evidence specification. Omitting it runs the existing producer. Use
`verify_account_organization` to check a saved result.

```python
from storymodel import OrganizationOrder, organize_accounts

order = OrganizationOrder(axis="discourse", positions={first.id: 0.0, second.id: 1.0})
result = organize_accounts(source, recall, evidence_spec, order, inference=inference)
print(result.organization.counts)
print(result.organization.backward_rate)
```

The [account example](../examples/account_inference.py) writes an order declaration and readout
alongside the original inference and separate reconstruction. CLI equivalents:

```sh
storymodel organize-accounts source.json recall.json spec.json organization-order.json \
  --inference inference.json --output organization.json
storymodel verify-account-organization source.json recall.json spec.json \
  organization-order.json organization.json
```

The ordered population is exactly the inference specification's selected units, retained in
the output. Units outside that declared population are not implicitly measured. Within it,
unranked rows stay present. This is a conditional readout of that population, not a claim about
all units in a larger recall account. Explicit failed/unresolved row inventories can be supplied
through the numerical path below; no missing row is dropped during adjacency construction.

## Numerical rows and dependence

`organization_readout(source, recall, spec)` accepts an `OrganizationSpec` with:

- ordered `OrganizationRow` records, each available, unresolved or failed;
- an `OrganizationOrder` of explicit source ranks;
- `dependence="independent-rows"` or `dependence="adjacent-joints"`;
- optional sparse `OrganizationJoint` records and `product_bounds`.

An available row contains `DestinationMass(destination=OrganizationDestination(target=...),
weight=...)` or an explicit external destination. Weights must be finite, nonnegative and sum
to one within the recorded method tolerance of `1e-9`. Unavailable rows contain no weights or
candidates. `admissible=None` on an available row uses its destination population; a supplied
admissible tuple must be nonempty, unique and contained in that row. Zero-weight candidates
can remain admissible, so ambiguity bounds describe declared possibilities rather than a
probability interval.

Independent rows explicitly assume factorization and use products of their weights. Coupled
rows require exactly one joint for every original adjacency whose two rows are available,
including unranked destinations. Joints for skipped, reversed, foreign or unavailable-row
pairs refuse. Each joint must match both actual row marginals within absolute tolerance `1e-9` per
destination and have total mass one within `1e-9`. The global check constrains total-mass drift;
it does not bound aggregate marginal redistribution beyond the per-destination tolerance. Duplicate destinations, units and joint edges refuse. Coupled inputs must
set `product_bounds=False`; independent inputs cannot carry unused joints.

```sh
storymodel organization-readout source.json recall.json organization-spec.json --output readout.json
storymodel verify-organization-readout source.json recall.json organization-spec.json readout.json
```

Both producer commands check source and recall export grants before reading specifications or
saved inference. API output grants are their conjunction. Verification permits denied inputs
but does not grant export permission. Verifiers recompute against actual inputs, including
strict revalidation of copied Python objects and saved JSON.

## Categories and denominators

Every consecutive pair of original rows contributes one conditional count distribution:

| Condition, in priority order | Category |
|---|---|
| Either processing row failed | `failed` |
| Either processing row is unresolved | `unresolved` |
| Either available destination is unranked | `unresolved` |
| Either destination is otherwise external | `external` |
| Same exact source target | `same-target` |
| Distinct targets with unequal, declared ranks | `backward` or `forward` |
| Missing ranks or equal ranks on distinct targets | `incomparable` |

A missing middle row creates two unresolved adjacencies. It never creates an observation
between the preceding and following available rows. `adjacency_count` retains the original
count. `comparable_mass` sums backward, forward and same-target mass; `backward_rate` divides
backward mass by this denominator, or is absent when there is no comparable mass.

`available_units` counts supplied numerical rows, including unranked-only rows; it does not
certify successful reference measurement. `resolved_adjacencies` excludes any positive failed
or unresolved mass. `information` describes row shape only: unavailable, exactly uniform
multi-destination rows, or distinguished rows. It is not a measure of scientific information.

## Order and coherent bounds

Ranks are caller declarations over checked source targets. They are never inferred from IDs,
embedding similarity, text offsets, inference scoring coordinates or reconstruction choices.
Negative ranks are valid. The axis label records the declaration's intended meaning; it does
not independently verify story-world chronology. Current accounts are text-backed. Native
media part-order/composition checks remain owed with the media/source representation port.

Independent product-space bounds use dynamic programming over whole paths. A middle row has
one destination shared by both neighboring transitions. For `[2], [1 or 3], [2]`, every path
has exactly one backward and one forward transition; pairwise extrema cannot claim two of
either. Bounds are unavailable when any processing row is unavailable. Empty rows-of-input
produce zero counts and zero bounds. Coupled bounds are unsupported and explicitly refused,
not approximated by independent product bounds.

The numerical kernel streams destination pairs into seven exact rational accumulators and
converts only final category/count/rate values to binary64. This is exact arithmetic on the
supplied binary64 weights, not additional empirical precision. It retains representable tiny
category totals when individual products would round to zero. A positive final value that
cannot be represented refuses rather than silently becoming zero. Caller Decimal settings
have no effect. Independent expectation and bound computation take O(sum of adjacent population
products); bounds keep only neighboring state vectors. Joint expectations traverse supplied
edges. No dense all-pairs probability table is allocated.

Reference: `align/organizationreadout.scala`, `OrganizationReadoutSuite`, and the organization
consumers in `MappingPipeline`. The latter's registered independent-measurement eligibility
contracts remain distinct from this numerical producer. No measured reference accuracy,
calibrated uncertainty, executed Scala equivalence or full P4 parity follows from this slice.
