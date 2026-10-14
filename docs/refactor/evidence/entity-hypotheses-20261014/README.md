# Identity alternatives: completed development experiment

The experiment improves conditional destination scores when a fixture identity is
established, while preserving unresolved interpretations for nonexact names. It does
not establish a generally better automatic mapper. All 84 native variants completed;
all 336 rows were available and independently read back. No production default changes.

The producer is `2a34550acfc5d71dbe5b59b81581d8e287078fd0`. The plan amendment at
`cb5fd11ba538c93c20f824b8f11d7218141e7857` preceded every new native run. The experiment
uses the four already-admitted development groups, seven conditions and four rows per
case. The previously opened holdout is excluded. Temperatures `.5` and `.15` were fixed
in advance; no parameter was selected here.

## What changed

The native test witness records the original recall and each eligible single-row name
rendering through real LocalOnly preparation and pinned MiniLM registration. Original
and rendered hashes, clause-to-unit bindings, costs, registration and native organization
certificates remain distinct. The 28 original endpoints exactly reproduce their prior
MiniLM development results, certificates, registrations and costs.

The diagnostic reader retains a distinct-identity alternative and an unresolved-original
scoring alternative alongside every eligible known identity. It never chooses the first
match, invents weights or emits an averaged point distribution. Exact fixture tokens have
one declared identity; this is a controlled fixture assumption, not general coreference.
Candidate retrieval is incomplete: the unresolved-original alternative does not recover
identities that retrieval missed.

Conditional scoring retains all external states and source events matching the assumed
identity. The distinct-identity assumption excludes source events. This closed-world
condition excludes cross-identity distortions by assumption, preserves state multiplicity,
and is separate from the original native certificate. The unresolved-original branch is
a scoring-model sensitivity alternative, not evidence that the entity is new.

## Measured result

At the predeclared `.5` temperature, each row below covers 16 development rows:

| Condition with one declared identity | Baseline NLL | Conditional NLL | Baseline Brier | Conditional Brier | Unique correct, before / after |
|---|---:|---:|---:|---:|---:|
| Exact | 1.347569 | 0.933517 | 0.687408 | 0.555267 | 16 / 16 |
| Paraphrase | 1.393276 | 0.981480 | 0.709171 | 0.588920 | 15 / 15 |

Lower NLL and Brier are better. These are conditional scores on reused development
specimens, not calibration or generalization estimates. Rank accuracy did not improve.
One paraphrase still chooses the wrong event despite its established identity.

The known-identity alternatives for truncation and typo each yield 16/16 correct
conditional choices, versus original 4/16 and 5/16. **That is not achieved identity
selection:** all 16 rows in each condition disagree across retained alternatives. All
16 plausible-new-name rows also lack a unanimous choice. No preferred branch or mean
across unknown branch weights is presented as achieved performance.

At `.5`, two of the 16 collision rows unanimously choose External even though their
labels admit source identities; the other 14 have no unanimous choice. The report field
`robust_winner` means unanimous **only among enumerated scoring-model alternatives**.
It offers no guarantee against omitted identities or a shared model error. These two
wrong decisions remain adverse evidence. At `.15`, all 16 collision rows lack a unanimous
choice; this sensitivity was predeclared, and neither temperature is promoted.

## Organization consequences

Organization ranges enumerate complete four-row interpretation assignments and their
conditional expected transition counts. Weights over assignments are unknown; multiplying
averaged row distributions would silently impose unsupported dependence assumptions.
Ranges are category-wise extrema under the enumerated model alternatives. They are not
confidence intervals or bounds on realized truth. All original adjacencies are retained;
unavailable rows leave gaps rather than connecting their neighbors.

For exact-name cases at `.5`, mean expected Forward increases from `0.811192` to
`0.964210`; for paraphrases it increases from `0.789505` to `0.922727`. Each case's truth
is three Forward adjacencies. Mean four-category L1 discrepancy falls from `4.377616`
to `4.071580` and from `4.420991` to `4.154546`, respectively. The remaining discrepancy
is substantial despite better row scores.

For truncation, typo and plausible-new-name cases, mean Forward range width is about
`0.964210` at `.5` and `1.897716` at `.15`. Point organization counts are withheld for
unknown interpretation weights. `summary.json` retains both temperatures and all seven
conditions; `report.json.gz` retains every hypothesis and case.

## Verification and retained adverse evidence

- Pipeline gate on the exact producer: **354 passed, 0 failed, 0 skipped**, fatal warnings.
  Pipeline is the complete reference scope; portable and sibling-consumed contracts are unchanged.
- Python suite: **228 passed, 0 failed, 0 skipped**, including 15 new focused tests.
- Four compiled Python mutants fail their named assertions with siblings passing:
  removed identity filter, invented weights, bridged missing row, and absent measurement
  reported as zero. The Scala exact-identity mutant compiles and yields one named failure
  plus one sibling pass. The restored source passes the full pipeline gate.
- Actual-output controls refuse a removed inventory entry, a false clause binding and a
  false cost even after their payload hashes are updated. The unchanged output passes.
- All 336 native rows reproduce to maximum mass error `1.1102230246251565e-16`;
  168 unchanged-row cost comparisons are exact. This licenses local row recombination for
  these specimens only, not arbitrary multiple edits or a new native joint certificate.
- Independent source/numerical review on exact `2a34550a` reproduces all 28 original
  endpoints and the whole report. Separate 50-digit cost arithmetic agrees on 496
  hypothesis rows (mass error at most `1.11e-16`, score error at most `8.88e-16`).
  Enumerating 625 destination paths for each of 2,696 interpretation assignments matches
  every count extremum within `8.88e-16`.
- All-failed and all-unavailable controls retain `maximum_mass_error: null`; partial
  controls measure only available rows and still refuse inconsistent costs.
- Formatting passes after the gate. The initial sandbox refusal to open sbt's existing
  cache lock is archived separately; it is not a compiled mutation kill or test failure.

The source/numerical review and final candidate review are separate records. Gates are
locally observed, with no hosted-CI claim. The preceding integration's JVM/JS/Native gates
and exact recall/challenge replays are in [the integration receipt](../integration-20261014/README.md).

## Reproduce and disposition

`native-results.tar.gz` contains only the 84 numerical JSON payloads and original pinned
manifest; private generated prose files are excluded. Extract it into a new directory,
then, from a checkout containing the producer and plan history, run:

```sh
python3 tools/recall-study/entity_hypotheses.py evaluate --results NEW_NATIVE_DIRECTORY --output NEW_REPORT.json
python3 docs/refactor/evidence/entity-hypotheses-20261014/summarize.py NEW_REPORT.json NEW_SUMMARY.json
python3 docs/refactor/evidence/entity-hypotheses-20261014/tamper_probe.py NEW_NATIVE_DIRECTORY NEW_CONTROLS.json
```

The checked producer launcher pins the plan, admitted fixture manifest, model and tokenizer
and refuses a dirty producer checkout or overwritten output. `native-preflight.json`
records this run. `checks.json`, mutation receipts, archived logs and `evidence-index.json`
bind the remaining evidence.

This tranche is an accepted engineering experiment, with production promotion rejected.
It isolates two remaining problems: evidence sufficient to choose an identity, and event
discrimination among events involving the same identity. More aggressive spelling repair
or a temperature change is not established as a solution. Any future efficacy comparison
needs a separately frozen evaluation that has not already been opened.
