# Candidate nomination provenance: the contract, frozen before code

Status: design court. **No implementation and no new public Scala vocabulary in this slice.**
Bead: bd-01M1CBKNA3KTBYTB3BACXJMXZZ. Author: claude-storymodel4s-m1.

This document freezes what a nomination must prove before any code is written. It is a court in the
repository's sense: every section names the mutation it would refuse. A section that cannot name one
is a description, and is marked as such rather than dressed up as a requirement.

## 1. The defect being frozen against

Candidate nomination decides which source nodes can receive posterior mass at all. The published
`HsmmResult` proves only the deduplicated anchor set. Everything about *how* those anchors came to be
available is discarded.

Verified line by line in `align/src/main/scala/storymodel4s/align/candidates.scala`:

| line | fact |
| --- | --- |
| `:10` | `final case class Nomination(ref, channel: String, rank: Int, rawScore: Option[Double], space: Option[String], receipt: Option[String])` — channel, space and receipt are raw strings |
| `:124` | `semantic(unit, n).toOption` — `SemanticDistance` returns `Estimate[Double]`; `toOption` keeps **every** `Observed`, finite or not, and drops only `Missing(reason)`, losing the reason |
| `:127` | `.sortBy { case (r, d) => (d, r.key) }` — orders by the raw score |
| `:128` | `.take(perLevel)` — so the score decides **membership**, not merely order |
| `:131` | `Nomination(r, channel, i, Some(d), space, Some(s"level:$level"))` — the "receipt" is a hierarchy label |
| `:147` | `Nomination(r, Channels.lexical, i, None, None, None)` — lexical nominations carry no receipt |
| `:27`  | `lazy val ranked = nominations.map(_.ref).distinct.sorted` — `distinct` is where provenance is erased |
| `:38-40` | `without(channel)` sets `abstained = abstained \|\| (kept.isEmpty && nominations.nonEmpty)` — an **ablation** that empties the set is relabelled provider abstention |
| `:45-52` | `CandidateSet.of(refs)` mints anchors as `Nomination(r, Channels.unspecified, i, None, None, None)` with `abstained = false` — caller anchors, no authority, no receipt |
| `:59-70` | `fuse` carries `k: Int = 60` as a **caller-overridable default**, orders by `(-rrf, ref.key, channel, rank)`, and asserts `abstained = false` in the non-empty branch |

**Consequence.** Two runs with the same anchor set but different nominating channels, provider or
geometry identities, raw rankings, or receipts publish equal `HsmmResult` values and identical wire
artifacts. The posterior arithmetic is not wrong; the scientific route that made those states
available is unrecoverable. That prevents exact audit, candidate-generation ablation, and any honest
answer to "why was this anchor considered".

## 2. Score admission: finite before sort, not finite before publication

**Contract.** A semantic score is admitted only if it is finite. Validation happens at `:124`,
**before** the sort and take — not as a check on the published record.

The reason is that the score is not a reported field. It is a selector: `sortBy` feeds `take`, so a
non-finite score changes *which nodes exist as candidates*. Validating after publication would leave
the selection already made.

**Measured, not assumed.** The current ordering was executed rather than reasoned about:

```
Ordering[Double] in scope   scala.math.Ordering$DeprecatedDoubleOrdering$
compare(NaN, 1.0)           1        NaN sorts greater than every real number
compare(NaN, NaN)           0        so it is a total order
sortBy on (0.5, NaN, 0.1)   0.1, 0.5, NaN
```

So ordering is deterministic **today**, by inheritance from a deprecated implicit rather than by
declaration. Scala 2.13 separated `TotalOrdering` from `IeeeOrdering` precisely because the choice
is consequential. If the deprecation is enforced, or any file imports `IeeeOrdering` for a good local
reason, nomination order becomes non-total and this code changes behaviour **with no diff at the call
site**.

**Contract.** The ordering is declared at the sort site, not inherited. "Explicit deterministic
ordering" means the ordering is written down; observing that it currently works is a different and
weaker property.

**Contract.** Score direction is declared. A raw score must state whether smaller is nearer or larger
is nearer, and must never carry probability semantics. `:127` currently sorts ascending, which reads
as "smaller is nearer", but nothing in the type says so.

### 2.1 Every attempt is typed; refusals never consume budget

**CORRECTED.** Two earlier versions of this section described the wrong mechanism, in opposite
directions. The first required a *finiteness predicate*. The second said `toOption` "discards every
`SemanticDistance` Left". **There is no `Left`.** Verified against source:

```
cost.scala:369          def apply(unit, node): Estimate[Double]
estimate.scala:61-67    enum Estimate[+V]:
                          case Observed(value, credence)   toOption = Some(v)
                          case Missing(reason)             toOption = None
```

So the actual behaviour at `:124` is the reverse of what the second version claimed:

| provider result | today |
| --- | --- |
| `Observed(finite)` | admitted — correct |
| `Observed(non-finite)` | **admitted**, enters the ordering, the take, and `rawScore` |
| `Missing(reason)` | dropped, and **the typed reason is lost** |

A non-finite score is not silently *discarded*; it is silently *accepted*. Only abstention
disappears, and it disappears reason-first. Getting this backwards matters: the repair for an
admitted bad value is refusal at admission, while the repair for a lost reason is retention.

**Contract — the attempt ledger records what the provider actually returned.** Present outcomes,
matching the constructors that exist today:

1. **admitted finite** — `Observed(v, credence)` with `v` finite;
2. **refused non-finite** — `Observed(v, credence)` with `v` not finite, refused at admission and
   recorded as refused, never silently admitted;
3. **provider abstention / missing evidence** — `Missing(reason)`, with `reason` retained.

**The credence coordinate is part of the outcome, not decoration.** The verified constructor is
`Observed(value: V, credence: Option[Credence])`. An earlier version of this section wrote
`Observed(v)` throughout and silently dropped the second field, which would let two attempts with
equal `v` and different credence be indistinguishable in the ledger — the same erasure this court
exists to stop, one coordinate over. The complete outcome retains credence.

`Estimate` has no execution-failure case. A provider or derivation **failure** is therefore a
**future** outcome to be named separately when the vocabulary gains it, not a present one; this court
does not pretend the ledger can distinguish it today.

**Contract — ordering of operations.** Record every attempted outcome; admit only finite `Observed`
scores; apply the declared total ordering; **then** take `perLevel`.

**A refusal does not consume the nomination budget.** A refused outcome has no lawful position in an
ordering over scores, so counting it toward the take silently ranks a failure against valid scores.
The next admitted finite score fills the slot; the budget is unfilled only when fewer than `perLevel`
finite outcomes exist. *(Resolution supplied by codex-storymodel4s-scout.)*

**Measured, not assumed.** The current ordering was executed, and the second measurement corrects an
over-generalisation in the previous version:

```
Ordering[Double] in scope    scala.math.Ordering$DeprecatedDoubleOrdering$   (TotalOrdering,
                                                    via java.lang.Double.compare)
sortBy on (NaN, 0.5, -Inf, +Inf, 0.1) ascending  ->  -Inf, 0.1, 0.5, +Inf, NaN
compare(-Inf, 1.0) = -1        compare(+Inf, 1.0) = 1
```

**Non-finite values are not one case.** The previous version measured NaN and then wrote "non-finite
sorts last". That is true for NaN and `+Infinity` and **false for `-Infinity`, which sorts BEFORE
every finite value.** Since the sort is ascending and smaller means nearer, a `-Infinity` score is not
merely admitted — it is ranked FIRST and is guaranteed to survive `take(perLevel)`, displacing a
genuine candidate. `+Infinity` and NaN fall to the end and are dropped only when more than `perLevel`
nodes score.

**Contract.** State only this: a non-finite value entering the declared ordering **can change take
membership, in either direction**. Do not attribute one position to all non-finite values. The repair
is refusal at admission, before the sort, which makes the position irrelevant.

**Contract.** Score direction is declared — whether smaller or larger is nearer — and a raw score
never carries probability semantics.

## 3. Identity is derived, never asserted

`channel`, `space` and the provider/geometry identities are caller-supplied strings today.

**Contract.** Channel kind, provider identity and geometry identity are **derived from admitted
authority**, not from a caller label. The question the design must answer for each identity field is
the one the repository already uses: *if the caller lied here, what would catch it?* If the answer is
nothing, the field is decoration carrying the authority of a receipt.

## 4. Hierarchy stratum and derivation receipt are different types

`:131` writes `Some(s"level:$level")` into the receipt field. That is a hierarchy label, not a record
of a provider call.

**Why this happened, and why it argues for the split on evidence rather than principle.** There was a
field and no rule about what belonged in it. A typed `stratum` and a typed `receipt` cannot be
confused that way, because neither will accept the other's value.

**Contract.** Hierarchy stratum is its own typed coordinate. The derivation receipt records the
execution that produced the score. Lexical nominations, which carry no receipt today, receive a **deterministic local derivation
receipt** describing the rule and its inputs. A typed "no provider execution" value is NOT permitted
here: lexical nomination is a derivation, and recording that no provider ran would describe the
absence of a thing that was never the source of the anchor. `None` today stands for both "no provider
ran" and "not applicable", which is the same conflation section 2.1 removes for scores.

## 5. The checked nomination bundle

**Contract.** A nomination bundle is a **checked, unforgeable** record binding, together:

- exact source-view identity;
- recall unit and transcript identity;
- explicit per-level and per-channel budgets in force;
- the **complete pre-take evaluated universe** with one typed attempt outcome per evaluated pair
  (section 2.1), or a content-addressed complete trace of it;
- the declared ordering and score direction;
- the fusion policy actually used — the RRF form, its `k`, and the exact input channel set;
- the deterministic local derivation receipt for lexical nominations;
- the **derived** admitted nominations, anchor set, and channel-local ranks.

**AMENDED — winners alone cannot audit loss.** The first version bound "ordered nominations plus the
resulting anchor set". That records who won and cannot answer why a node lost, which is half the
question an audit asks. The pre-take universe is what makes "never nominated" explicable.

### 5.2 Rank scope is not uniform today, and the court must say so

**CORRECTED.** The previous version froze rank as "channel-local". That is true for lexical and
**false for semantic**, verified in source:

```
:123  view.byLevel.toVector.sortBy(_._1).flatMap { case (level, nodes) =>
:130      .zipWithIndex                     <- INSIDE the per-stratum flatMap
:131      Nomination(r, channel, i, ...)       so semantic rank RESETS every hierarchy stratum
:145  .sorted.zipWithIndex                  <- lexical, outside any per-level loop
:146      Nomination(r, Channels.lexical, i, ...)   channel-local
:63-68 fuse: rrf = sum 1/(k + 1 + n.rank)   consumes both WITHOUT a stratum coordinate
```

So two channels emit ranks **on different scales** and the fusion sums them as though they were one.
With S strata the semantic channel contributes S rank-0 nominations while lexical contributes one, so
reciprocal-rank fusion systematically favours semantic breadth — a scoring consequence, not a
record-keeping one.

**Contract.** The ranking scope is named explicitly rather than assumed uniform: semantic rank is
local to **channel and stratum**, lexical rank is local to **channel**, and each nomination carries
its scope. Fusion identity binds the scope it consumed. A design that prefers a single global rerank
must say so openly and state the changed fusion semantics; it may not be introduced as a
record-only change.

**AMENDED — the anchor set and ranks are DERIVED, never parallel caller fields.** The factory computes
them from the admitted ledger. A bundle that *carries* an anchor set alongside its attempts can state
an anchor no attempt supports.

**Feasibility is established, not hoped for.** `CandidateSet` already demonstrates the separation:
`nominations` retains the per-channel records while `ranked` derives the unique sorted anchor set that
`GraphHsmm` consumes. So the retained bundle costs inference nothing — `HsmmResult`, the wire, the
output bundle and calibration carry the checked bundle or a content-addressed companion identity while
posterior arithmetic still consumes only the derived anchors. *(Established by
codex-storymodel4s-scout against current source, answering a question the first version left open.)*

Deduplication may continue to feed inference — the aligner consumes a set and that is correct — but
**dedup must not erase the receipted bundle** from result, wire, output, or calibration. `:27` is where
that erasure happens today.

### 5.0 Identity is derived from the actual view and recall, and the effective set is one set

**AMENDED — the previous version NAMED these identities and never required them to be DERIVED.**
Section 5 bound "exact source-view identity" and "recall unit and transcript identity", and nothing
required the factory to compute them from the actual `SourceView` and `RecallGraph`. That is this
court's own rule — derived, never asserted — failing inside the court.

**Contract.** The bundle is built by **smart construction from the checked recall and the checked
view**, and both identities are derived from those objects. A caller may not supply an identity
alongside the objects it claims to describe.

**Contract.** Evaluated and manually-authorised anchors outside the bound view are **refused at
construction**, not filtered later.

**The live seam this closes.** `GraphHsmm.infer` silently drops anchors absent from the view before
`HsmmResult.validated`:

```
hsmm.scala:520-528   .map((u, refs) => u -> refs.filter(ref => view.node(ref).nonEmpty))
hsmm.scala:571-590   run repeats the same filtering
```

So a retained bundle can hold one anchor set while inference and `HsmmResult` publish a smaller one,
and **every court M1-M13 still passes** — the bundle is internally consistent and the published
result is quietly different. There must be ONE effective set, derived, with no silent narrowing
between the bundle and the result.

**Mutations.** M14 substitutes a foreign `SourceView` while holding the bundle fixed; M15 substitutes
a foreign recall or alters transcript content while retaining the claimed identity; M16 retains an
anchor absent from the bound view and requires refusal at construction rather than silent filtering.

### 5.1 No-admitted-ranking is an aggregate, not an abstention

**CORRECTED.** The previous version derived a single `abstained` from "every attempt is a
non-admitting outcome". That is a union of unlike things, and it reproduces the defect one level up:
it lets a set of refusals or ablations claim that providers abstained.

**Contract.** The bundle publishes a **no-admitted-ranking** aggregate — the fact that no anchor was
admitted — and, separately, the typed composition that produced it. The aggregate is derived; it is
never a constructor argument. Specifically:

- `ProviderAbstained` remains **one typed reason** among the attempt outcomes of section 2.1, never a
  synonym for the aggregate;
- a unit whose every attempt was **refused non-finite** has no admitted ranking and **must not** be
  reported as provider abstention — nothing abstained, a value was rejected;
- **ablation-empty stays separate.** `:38-40` `without(channel)` sets `abstained` when an ablation
  empties the set. That is a counterfactual "empty after channel removal", not a provider outcome.
  Original availability is preserved across ablation and the ablation policy and outcome are recorded
  as their own fields;
- `:59-70` `fuse` asserts `abstained = false` outright in its non-empty branch; assertion is replaced
  by derivation.

**Why the distinction is load-bearing rather than tidy.** `ExternalState.Unranked` consumes this
signal, and the external-floor calibration court freezes "`Unranked` … never means that a participant
intruded, associated, inferred, or produced uninterpretable content." If refusals or ablations can
enter the aggregate as abstention, `Unranked` reports a model support failure for something that was
an experimental manipulation or a rejected value. That statement is falsifiable by `without()` as the
code stands.

### 5.3 Ablation provenance is retained on the PUBLISHED result, not only in the bundle

**AMENDED — the previous version described this gap and did not require anything, which by section
7's own rule makes it a description rather than a court.**

`AblationResult` at `:483-487` carries `rows`, `viterbi` and `logLikelihood`. Nothing on the
published object records which channel was removed or under what policy, so M9 can pass on the
pre-inference bundle while the published result still cannot say what produced it.

**Contract — carrier relation.** The published `AblationResult`, or a content-addressed companion
bound to it, retains:

- the **checked original bundle identity** the ablation was taken from;
- the **typed removed channel and ablation policy** — not a label, derived from the ablation that ran;
- the **derived ablated bundle and output identity**, so the counterfactual is identifiable as its
  own object rather than inferred by subtraction.

A numerically identical ablation result produced by removing a *different* channel must not be
indistinguishable from this one.

**Mutation M18.** Change or remove the ablation provenance on the published `AblationResult` or its
companion while `rows`, `viterbi` and `logLikelihood` remain byte-fixed. The court must turn red.
This is deliberately the M4 shape one surface further out: the numbers a careless suite asserts on
are exactly what the mutation leaves alone.

## 6. The Cartesian-product test and the construction boundary

This bead converts `Nomination` from a hint into an audit record. The moment its fields *jointly
attest a derivation*, product construction becomes forgery.

**Contract.** Any audit record whose fields jointly attest a derivation is an **unforgeable final
non-case class with smart construction**. Future courts must compile-refuse, from outside the
defining package:

- public `apply`
- `copy`
- companion `fromProduct`
- `Mirror.ProductOf[...]` and its `fromProduct`

each with a **same-mechanism positive control** — a control in the *same syntax* as the negative it
guards. A `summon[Mirror.ProductOf[X]]` positive does not guard a direct `X.fromProduct(???)`
negative; that specific gap was found and repaired in this module already.

This is not a follow-up to the design. `Nomination` is `final case class` at `:10`, so
`Mirror.fromProduct` mints one from any package with any provenance the caller likes — an audit
record attesting a derivation that never ran. The same defect was sealed four times here already
(`StructuralReductionReceipt` and siblings, bd-01M17ZNXY6).

## 7. Named mutations

Each must turn a court red. A court that cannot name its mutation is a description.

| # | mutation | must be refused by |
| --- | --- | --- |
| M1 | admit a non-finite score at `:124` and let it reach sort/take | the finite-admission court |
| M2 | write the hierarchy stratum into the derivation receipt field | the stratum/receipt separation court |
| M3 | substitute a different provider or geometry identity while leaving scores unchanged | the derived-identity court |
| M4 | drop the receipted bundle after dedup, keeping the anchor set | the dedup-provenance court |
| M5 | construct an audit record through `apply`, `copy`, `fromProduct` or a derived `Mirror` | the construction-boundary court |
| **M6** | collapse two distinct attempt outcomes — abstention and derivation failure — into one | the typed-attempt-ledger court |
| **M7** | let a refused non-finite outcome consume a `perLevel` slot, so the next admitted finite score is excluded | the budget court; the refusal must remain visible in the ledger while the next finite score enters |
| **M8** | alter a published anchor or a channel-local rank while holding every attempt and score fixed | the derived-anchor court |
| **M9** | empty a candidate set by ablation and let the bundle report provider abstention | the ablation-versus-abstention court |
| **M14** | substitute a foreign SourceView while the bundle keeps its claimed view identity | the derived-view-identity court |
| **M15** | substitute a foreign recall, or change transcript content, while keeping the claimed recall identity | the derived-recall-identity court |
| **M16** | retain an anchor absent from the bound view and let inference filter it silently | the one-effective-set court; construction must refuse it |
| **M18** | change or remove ablation provenance on the published AblationResult while rows, viterbi and logLikelihood stay fixed | the ablation-publication court |
| **M17** | drop or substitute the credence on an admitted outcome, leaving the value fixed | the complete-outcome court |
| **M13** | collapse two stratum-local semantic ranks into one asserted channel-global coordinate, leaving scores fixed | the rank-scope court; fusion identity must reject a scope it did not consume |
| **M12** | make every attempt for a unit a refused non-finite, and let the bundle report provider abstention | the no-admitted-ranking court; the aggregate holds, `ProviderAbstained` must not |
| **M10** | mint anchors through `CandidateSet.of` and have them admitted without an authority receipt | the construction-root court |
| **M11** | change the fusion `k` or the input channel set without changing the recorded fusion policy | the fusion-policy court |

M4 is the one most likely to pass a careless suite, because the anchor set — the thing inference
consumes — is unchanged by it. Its court must assert on the retained bundle, not on the posterior.
M8 has the same shape one level down: attempts and scores are untouched, so only a court that
recomputes anchors from the ledger can see it.

## 8. Migration surfaces to reconcile

`HsmmResult`, `AlignWire` and the codec schema all publish the deduplicated anchor set today.
Carrying the bundle changes each. Schema version impact is a wire-visible change and must be treated
as one.

**AMENDED — the construction roots are migration surfaces too, and the first version omitted them.**
Naming only the publication surfaces left the doors through which unprovenanced anchors enter:

- `CandidateSet.of` at `:45-52` — a public convenience that mints anchors under
  `Channels.unspecified` with no score, no space, no receipt and `abstained = false`. The migration
  must either remove this plain-anchor bypass or require an explicit **admitted external/manual
  authority receipt** for anchors that have no provider derivation.
- `CandidateSet` and `Candidates` are public case classes, so the bundle is forgeable by
  `Mirror.fromProduct` regardless of what the factories check. Section 6 applies to them.
- `fuse` at `:59-70` — `k: Int = 60` is a caller-overridable default and the fusion policy is not
  recorded anywhere. The policy travels in the bundle or the run is not reproducible.
- **`AblationResult` at `:483-487` and `:541-553`** — carries only `rows`, `viterbi` and
  `logLikelihood`. **Contract in section 5.3.** The ablation publication surface is part of this
  migration, not a follow-on.

## 9. Explicitly out of scope

- No implementation.
- No new public Scala vocabulary. Any later API requires the explicit ADR 0002 line and a separately
  scoped candidate.
- The estimand question of whether nomination provenance should influence posterior mass is **not**
  opened here. This contract is about what is *recorded*, not about what is *scored*.

## 10. Gate

- `git diff --check`
- exact source-line checklist, re-verified at review time against the reviewed SHA, covering **every
  cited site** rather than `candidates.scala` alone:
  `candidates.scala` :10, :27, :38-40, :45-52, :59-70, :123-133, :139-147;
  `estimate.scala` :61-67 (the `Estimate` constructors and `toOption`);
  `hsmm.scala` :483-487 and :541-553 (`AblationResult`), :520-528 and :571-590 (the anchor filter);
  `matrix.scala` :64-68 (`externals` / `unranked`).
  Freezing a court against one file leaves it source-false the moment another cited file drifts.
- independent review

## 11. Settled since the first version, and what remains open

**SETTLED — budget on refusal.** A refused non-finite score does **not** consume the per-level budget;
the next admitted finite score fills the slot, and the budget goes unfilled only when fewer than
`perLevel` finite outcomes exist. The first version recorded this as an open choice between two
defensible options. That was wrong: the question was mis-posed, because a refusal has no lawful
position in an ordering over scores. Settled by codex-storymodel4s-scout; contract in section 2.1;
mutation M7.

**SETTLED — feasibility of the retained bundle.** Achievable without changing what inference consumes;
`CandidateSet` already separates retained records from derived anchors. Settled by
codex-storymodel4s-scout against current source; contract in section 5.

**CORRECTED — rank scope.** The previous version settled this as "channel-local". Source shows two
different scopes: semantic rank resets per hierarchy stratum (`zipWithIndex` inside the per-level
`flatMap` at `:123-133`), lexical rank is channel-local (`:139-147`), and `fuse` at `:63-68` consumes
both without a stratum coordinate. Contract in section 5.2; mutation M13. Found by
codex-storymodel-collab.

**SETTLED — lexical derivation receipt.** Deterministic lexical nomination receives a **local
derivation receipt** describing the rule and its inputs, not `None` and not a borrowed provider
receipt. `None` today stands for both "no provider ran" and "not applicable", which is the same
conflation section 2.1 removes for scores.

### Still open

- Whether the pre-take evaluated universe is retained in full or as a content-addressed complete
  trace. Both satisfy the audit requirement; they differ in wire cost and in what an offline reader
  can reconstruct without the corpus. This is a wire-schema decision and belongs with the migration.
- Whether anchors admitted through an explicit external/manual authority receipt (section 8) are
  distinguishable **in the estimand**, or only in the record. Recording them is settled; whether a
  calibration may treat them as equivalent to provider-derived anchors is not, and it is a scientific
  question rather than a typing one.
