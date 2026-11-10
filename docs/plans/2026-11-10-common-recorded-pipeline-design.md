# One recorded pipeline for categorical producers: design (2026-11-10)

Delivery 2 of the [scientific preview][plan]. It was designed read-only against `main` at
f31c83e0c and the FilmFestival tools, and the owner approved its four vocabulary decisions on
2026-11-10 ([ADR 0022 amendment](../adr/0022-mapping-producers-and-evidence.md)).

[plan]: 2026-11-09-categorical-mapping-and-transfer-plan.md#revision-2026-11-10-the-scientific-preview

## Shape

One generic `agentmapping` path takes four inputs and writes one bundle per participant,
session, condition and model:

- a timed-annotation source;
- recall units, supplied by the study or produced by our segmenter;
- a prompt set, taken as recorded;
- text-exchange recordings.

Corpus knowledge lives only in an exporter that writes those inputs. The exporter decides which
rows are targets and what text each unit has, so it gets the same independent review as a
scorer.

**Acceptance for the delivery:** for Sherlock, all six FilmFestival runs and one synthetic
study, bundle unit rows equal the recorded predictions for every unit with zero model calls,
and one reader reads them all.

## Source

- Parts stay physical (Sherlock's two parts; FilmFestival's two scanning runs). Films are not
  parts.
- `TimedSegment` gains an optional collection. `TimedSourceView.of` emits level-2 nodes and
  sets each group's parent. With no collections the grain stays single-level and digests do not
  move.
- The timed-annotation CSV accepts a second header: the nine existing columns plus
  `collection`, `collection_label` and `target`. A group is a target if any of its rows is; a
  collection is a target if any of its groups is. This carries title cards and the cartoon
  with no corpus rule. Files with the first header hash exactly as today.
- FilmFestival scenes have no labels; the exporter writes `scene N`.

## Recall

- Supplied units use the existing `RecallGraph.validated`. The transcript is the unit texts
  joined by newlines; unit ids follow the segmenter's form; an empty unit is refused.
- Supplied units carry `DiscourseFunction.NotAssessed`.
- One type, `PreparedRecall`, has two builders (supplied units; our segmenter), so delivery 4
  does not redesign it. The check that a prompt showed a unit's text applies to both.
- The bundle records `segmentation: {kind: supplied | segmenter, artifactSha256, recipe}`.

## Answers

One sealed judgement per interpretation, derived from raw fields that are kept verbatim.

| Judgement | Checked record | Unit row kind |
|---|---|---|
| Scene | choice of the scene | `scene` |
| Collection | choice of the film | `film` |
| No assignment | abstention with reason | `none` |
| Time in a film | choice of the strict scene | `scene`, with time fields |
| Time outside the clip | choice of the film | `time-outside` |
| A scene that is not a target | failed, invalid output | `invalid-scene` |
| Invalid | failed, invalid output | `failed`, with reason |
| Omitted, unparsable | as now | as now |

- No number enters the record. Seconds are an exact decimal string in the unit row.
- Alternatives, rows and confidence stay per-field values. The condition declares which it
  requests, so "not requested" differs from "left out by the model".
- The time rule is a `SceneIntervals` type in `pipeline/agentmapping`, unforgeable, built from
  the checked source and a clip clock with exact arithmetic. It refuses unless each film's
  clock origin is its first target row.

## Recordings and reply protocols

- The FilmFestival caches import as text-exchange recordings unchanged.
- The two harnesses differ in small rules, so replay takes a named reply protocol:

| Rule | Sherlock harness | FilmFestival harness |
|---|---|---|
| Unit key | coerced to an integer | a strict integer |
| Item outside its chunk | accepted; later overwrites | dropped and counted |
| Bad key | refuses the participant | dropped |
| Scene validity | range-checked at parse | checked against targets later |
| Field presence | the field alone | the field and its film field |

- The strict protocol tests the literal: `5.0` is not an integer scene.
- Retries and rejected chunks did not occur in any run and need synthetic tests.

## Prompts

Prompts are opaque recorded inputs in this delivery. Scala appends the retry suffix, looks up
the exchange by the exact text, and checks that each prompt contains its unit's text. A generic
renderer is delivery 3's job, with the exported prompts as its test.

## Bundle v2 and reader

- Provenance adds study, session, condition, CLI version, protocol, segmentation and the prompt
  set's hash; time conditions add the clock, tolerance and interval hashes.
- The source dictionary adds collections; scenes gain their collection and whether they are a
  target.
- Each unit row has, per interpretation, the kind, the scene or collection, the time fields and
  any reason, plus the study's own row key.
- `read_bundle.py` reads both versions and can emit the scorer's input format. The existing
  scorer must reproduce the recorded scores exactly from bundles.

## Slices

1. **Source levels and CSV header.** `align`, `codec`; portable; sibling gate.
   - FilmFestival's CSV gives 216 scenes and 12 collections, with targets equal to the Python
     dictionary's; Sherlock's artifact and representation digest are unchanged.
2. **Generic replay on real recordings.** `pipeline`, `provider-agent`, `recall`.
   - For both annotation runs every unit's judgement equals the reply file, including the 15
     dropped items, with zero model calls.
3. **Bundle v2 and reader.**
   - The scorer over bundle-derived predictions equals the recorded scores; the v1 fixture
     still reads.
4. **Time conditions.**
   - All four automatic and no-record runs reproduce strict scene, near scenes and out-of-clip
     per unit.
5. **Sherlock on the same entry point, and a synthetic third study.**
   - Sherlock record digests equal the existing bundles; no corpus name remains in the generic
     path.

## Carried into slice 2 from the review of slice 1

- **The universe must come from the source.** The view derives its target scenes and films, but
  a caller can still declare a wider universe. The bundle builder takes the universe from the
  view, and the reader checks the record's universe against the regenerated view.
- **Targets and the digest.** Row target marks are in the artifact checksum, and so in every
  node id, when a source comes through the file codec. A source built directly with a
  caller-supplied artifact id does not carry them in its digest. The pipeline builds sources
  only through the codec.
- A collection that spans two parts is reported as unlocated, the rule groups already follow.
- Part extents written by the FilmFestival exporter are the annotation's coverage, not media
  durations.

## Carried into slices 3 and 5 from the review of slice 2b

- **Bundle rows are built from judgements, not parsed back from records.** A scene that is not
  a target becomes a failed outcome in the record, with its detail as free text. The scorer
  keeps that scene's film for film identity, so the row must carry the scene number and its
  film, taken from the judgement and the source.
- **Rows are keyed by participant, session and unit.** A unit id is the content address of the
  session's text plus a position. Two sessions with identical texts would share unit ids.
- **A recall's identity is its units alone** (numbers, the study's row keys, texts), so the same
  units have the same inventory under every condition.
- **Sherlock's digests will change when it moves to this path** (slice 5): the policy id, the
  referent abstention wording, the failure details and the source of the universe all differ
  from `AgentMappingRecord`. Slice 5 either keeps those as a named legacy profile or accepts
  new digests and says so.
- **Not yet neutral in the cost model.** An unassessed unit has no natural external state, so
  every named external state costs the mismatch and none sits at the floor; and the content
  cost's preferred level falls through to the default. No path feeds supplied units to a cost
  model today. Both need a decision before one does.

## Notes

- The interval-table hash of the Python tools is over rounded text. Matching it is not an
  acceptance; per-unit equality is.
- The reference file must be read with quoting off: a default reader merges rows at quote
  marks and yields 3,181 utterances, not 3,226.
