# Sherlock recall-to-video: first executable product vertical

**Status:** implementation-driving PRD; no public Scala vocabulary is approved here

**Product bead:** `bd-01M1EAE4X7GPERQHJZYQJQXH11`

**Construction-integrity bead:** `bd-01M1EA8J4KFQRHZ5SF326B6WFZ`

## 1. Product decision

Ship one terminal workflow that turns an admitted Sherlock source/annotation bundle and one recall
transcript into a durable, locally inspectable alignment bundle:

```text
admitted Sherlock artifacts + recall text
  -> witnessed construction family with retained alternatives
  -> explicitly qualified fixed RecallGraph view
  -> sparse nominations + GraphHsmm P, F, Viterbi summary, and RecallSignature
  -> versioned result.json + self-contained preview.html + preview.txt
```

This is a **diagnostic baseline**, not a calibrated memory measure and not evidence that one recall
construction is true. Its purpose is to make the real pipeline runnable, inspectable, and
criticizable before calibration work begins.

Raw video bytes remain external. The command may read the two caller-supplied local movie files to
verify their pinned hashes and stream identities. It never copies, embeds, uploads, redistributes,
or records their filesystem paths. Derived Sherlock artifacts may be stored or shared only under
their established admission/disclosure disposition.

## 2. User and representative journey

The first user is a researcher who has the admitted Sherlock annotation/recall artifacts and local
access to the two pinned video parts.

1. The researcher pipes or names a UTF-8 recall transcript and supplies its admission receipt when
   the result is intended to be shareable.
2. The command verifies the source bundle, annotation repair, recall bytes, and optional local movie
   identities before inference.
3. It constructs a witnessed baseline family, preserving segmentation and interpretation
   alternatives. It either qualifies one fixed view under a declared rule or stops before
   alignment.
4. It runs the existing sparse nomination and `GraphHsmm` path only on the qualified fixed view.
5. It writes a movable output directory containing a machine-readable result, manifest, static HTML
   report, and deterministic text twin. No server or package installation is needed to inspect it.
6. The researcher opens `preview.html`, selects a recall row through ordinary in-document links,
   and sees its exact words, competing source intervals, faithful/distorted/external mass, incoming
   and outgoing transition flow, evidence, coordinate basis, and construction sensitivity.
7. If the media crosswalk is checked, the report displays verified PTS and a copyable local-runtime
   command whose movie path is supplied only when that command is invoked. The shareable report
   never contains or opens a local media path. If the crosswalk is not checked, the same scientific
   result remains inspectable while exact playback is visibly refused.

The successful outcome is not “the recall was mapped.” It is “this identified diagnostic model,
conditioned on this identified construction and source representation, produced these checked
masses and flows.”

## 3. Terminal contract

The intended product seam is the existing E0 script path; option names below are descriptive
placeholders until its CLI vocabulary is approved:

```sh
tools/run-sherlock-vertical.sh \
  --source-bundle /data/sherlock/admitted-source.json \
  --annotations /data/sherlock/admitted-annotations.json \
  --recall-text - \
  --recall-admission /data/sherlock/recall-admission.json \
  --video-part-a /private/media/sherlock-a.mp4 \
  --video-part-b /private/media/sherlock-b.mp4 \
  --construction-family witnessed-baseline-v1 \
  --output /results/sherlock-recall-001
```

Exact playback, when admitted, is a separate local CLI/runtime action outside the shareable bundle.
The static report provides a path-free command template such as:

```sh
tools/open-sherlock-interval.sh \
  --result /results/sherlock-recall-001/result.json \
  --interval-id source-interval-017 \
  --video /private/media/sherlock-a.mp4
```

The caller supplies `--video` at invocation. The runtime re-verifies its bytes and stream identity
against `result.json` before seeking; neither the path nor any playback state is written back into
the shareable artifacts.

### Direct text input

Direct recall text is a required first-class input, by either `--recall-text PATH` or
`--recall-text -` for standard input. Literal recall text is not accepted as an argument because it
would leak into shell history and process listings.

Direct input is an acquisition convenience, not an identity or authority shortcut. The command:

- captures the exact bytes before parsing and records byte length, SHA-256, charset/BOM decision,
  decoded-string identity, canonical-string identity, and transformation receipts;
- derives identity from those bytes and receipts, never from the pathname, terminal, or a caller
  label;
- requires a checked admission/disclosure receipt for a shareable disposition; without one, any
  text-bearing report is local-sensitive and cannot be certified shareable; and
- preserves typed decode, empty-input, admission, and construction refusals rather than silently
  cleaning or guessing.

This mechanism is sound because it separates easy input from scientific construction. It becomes
unsound only if stdin is treated as already admitted, one segmentation is silently selected, or a
caller-provided status is allowed to confer authority.

## 4. Input and identity boundary

The invocation binds all of the following at full length in `result.json`; display labels may be
shortened but never substitute for these identities:

| Boundary | Required identity and receipt |
|---|---|
| Sherlock source | source-bundle, edition, artifact, stream, alias, annotation-repair, segmentation-population, and presentation-axis identities |
| Local movie | expected and observed part hashes, byte lengths, stream metadata, and verification outcome; never bytes or paths |
| Recall | original bytes, decoding, canonical text, transcript atlas, disclosure disposition, and exact recall evidence spans |
| Construction family | family/version, source-use mode, recall-feature lineage ledger, configuration, seed, provider/attempt receipts, competing contiguous cell plans, semantic artifacts, cell/artifact incidence and compatibility receipts, search space, pruning and tie rules, every retained alternative, and failure inventory |
| Fixed view | qualification rule, selected cell plan, selected semantic artifacts, selected incidence/compatibility witness, unresolved choices, excluded alternatives, projection loss, source-use/lineage closure, and projection receipt |
| Alignment | checked source view, nomination bundle, complete model/cost configuration, `P`, `F`, Viterbi, signature, trace, and software revision |
| View/report | packet/compiler version, thresholds, ordering, coordinate choice, filters, textual-twin identity, renderer identity, and output checksums |

Every downstream alignment and report identity is bound to the complete source, recall,
construction-family, search, and fixed-view projection identities. Changing any one invalidates the
downstream cache/result key. A family name, “best” label, short hash, or selected candidate ID is
not sufficient provenance.

### Coordinate rule

Annotation time, repaired annotation time, run-local time, scanner/TR coordinates, and pinned-media
PTS/DTS are separate coordinates. Every displayed interval names its basis and axis identity.

The first vertical may align on checked annotation/repaired coordinates. It may claim exact
playback only when a checked crosswalk proves the time mapping to the verified edition's media PTS
and the separate local runtime verifies caller-supplied media. The crosswalk certifies coordinate
mapping, never reachability of a media path. An absent, partial, inexact, overflowed, extrapolated,
or foreign crosswalk produces an explicit playback refusal. It never turns annotation seconds into
media seconds by label or arithmetic convenience. The known part-B tail remains visible as
uncovered media extent rather than being silently stretched into the annotation range.

## 5. Construction and alignment contract

The baseline recall construction and fixed-view qualification are **source-blind**. They consume
only the recall observation and declared recall-side derivation recipes. They never consume source
nominations, `P`, `F`, Viterbi, `RecallSignature`, alignment endpoints, or any source-derived score
when proposing, pruning, ranking, or qualifying a construction. The construction family,
projection, result, and report identities bind this source-use mode and a feature-use/lineage ledger
that makes the exclusion auditable.

The family must expose, rather than erase, three distinct layers:

- **segmentation:** exact contiguous cells and boundaries on a declared transcript coordinate,
  including the eligible mask, exclusions, and competing split/merge cell plans;
- **interpretation/evidence:** separately identified semantic artifacts with evidence-backed
  proposition/role alternatives, unresolved fields, and supports that may overlap or be
  discontinuous, without promotion from proposal to accepted meaning; and
- **incidence:** an explicit many-to-many mapping between cells and semantic artifacts, with a
  compatibility outcome and receipt for each admitted or refused link.

Its search receipt records the complete proposed space, executed attempts, failures, scores with
declared direction, deterministic tie handling, pruning rule, and retained alternatives. “No
alternative generated” and “alternative generated then pruned” are distinguishable.

Before alignment, a fixed-view projection must pass the adopted artifact under construction-
integrity bead `bd-01M1EA8J4KFQRHZ5SF326B6WFZ`. Its selected witness identifies the contiguous
cell plan, semantic artifacts, incidence mapping, and compatibility outcomes/receipts. Its receipt
states exactly what was selected, what was lost, and why the view is qualified for this diagnostic.
The full construction family remains in the result bundle. If no fixed view is qualified,
construction may be `Partial` with useful alternatives, but nomination and alignment do not run.

A later source-informed diagnostic child may be added only as a separately labelled result whose
identity binds the informing source and lineage. It cannot replace, compare as, or masquerade as
the primary source-frozen alignment produced from the source-blind baseline.

For a qualified view, the alignment stage consumes the checked source view and existing sparse
nomination path. It retains:

- every lawful nomination and attempt/refusal, including channel-local rank and provider/geometry
  receipts where applicable;
- every posterior `P` row entry, including present zeroes, and every cost-side exclusion;
- every sparse transition-flow `F` entry and its exact adjacent row memberships;
- named external states, faithful/distorted modes and all distortion facets;
- Viterbi only as one maximum-a-posteriori summary beside, never instead of, `P` and `F`; and
- `RecallSignature`, evidence trace, complete configuration, source/recall checksums, and audit
  refusals.

Visible top-k branches are not renormalized. The undisplayed remainder is explicit. Absent state
keys remain unavailable rather than becoming measured zero. External mass is not intrusion by
default, and low source mass is not called omission unless the separate universe/eligibility
conditions establish that estimand.

## 6. Result states and durable artifacts

Stage outcomes are independent. A later failure does not erase an earlier valid artifact.

| Stage | Successful state | Partial state | Refusal examples |
|---|---|---|---|
| Intake | identities and receipts checked | admitted non-video data usable but playback unbound | foreign bundle, bad hash, decode failure, missing disclosure for requested shareable output |
| Construction | witnessed family, source-use ledger and incidence closure complete | nonempty alternatives/gaps, no qualified fixed view | fabricated authority, source leakage, unreceipted search, lost cell/artifact/incidence alternatives |
| Fixed view | qualified projection with declared loss | not applicable | unresolved choice presented as fixed, foreign construction member, missing projection receipt |
| Alignment | checked `P`, `F`, signature and trace | valid semantic values retained with a typed audit refusal | unqualified view, foreign source/receipt, non-finite value, invalid marginals or state membership |
| Report | `preview.html` and `preview.txt` produced independently | one report succeeds while the other fails | inferred coordinate, omitted remainder, missing textual twin, external resource |
| Delivery | manifest-bound directory published atomically | prepared artifacts retained after publication refusal | overwrite target, checksum mismatch, partial final directory |

The base output directory contains, at minimum:

- `manifest.json`: final artifact inventory, byte lengths, SHA-256 values, and produced/failed/not
  attempted dispositions;
- `result.json`: versioned invocation account containing the construction family, source-use and
  recall-feature lineage ledger, fixed-view projection, alignment payload, all stage outcomes, and
  full identity closure;
- `preview.html`: self-contained static HTML/CSS with its complete in-document textual twin; and
- `preview.txt`: independent deterministic text report.

V1 may split large renderer-neutral packets into checksummed `projection_packet` artifacts, but the
manifest and result root must close over them. A dedicated recall-input role, if needed, requires
the output-vocabulary decision; recall bytes must not be mislabeled as the movie's original source.
No artifact contains video bytes, local movie paths, secrets, network locators, or automatically
loaded external resources.

## 7. Minimum report for visual QA

The first screen visibly says **diagnostic baseline — not calibrated; source-blind recall
construction** and separately reports semantic, audit, report, playback, and disclosure states. It
prints the construction source-use mode and lineage-ledger identity beside that warning.

The report then presents:

1. a source timeline/hierarchy using annotation or repaired coordinates, with the exact coordinate
   basis printed beside every interval and verified media PTS only when the crosswalk is checked;
2. a segmentation layer showing exact contiguous transcript cells, boundaries, eligible mask,
   exclusions, and competing split/merge plans on their declared coordinate;
3. a semantic-support layer showing separately identified interpretation/evidence artifacts and
   exact overlapping or discontinuous spans—overlap uses lanes or repeated references, never
   flattened hulls;
4. an incidence layer showing the many-to-many cell/artifact links, compatibility outcomes, and
   receipts; a fixed-view semantic row is never silently renamed a cell;
5. a complete `P` matrix/tabled twin showing faithful, each distorted alternative, all six external
   provinces, present zeroes, exclusions, visible mass, and retained remainder;
6. `F` routes derived from transition flow itself, including stays, backward returns, long jumps,
   mode changes, and external excursions; adjacent row winners are never drawn as a substitute;
7. construction alternatives and fixed-view loss, with sensitivity panels that compare alignment
   summaries across retained qualified views or state that sensitivity was not run;
8. evidence and authority drill-down that separately displays construction route, conformance,
   authenticity, use admission, and calibration; recall/source supports and cost/missing/imputed/
   excluded status remain distinct, and provider identity appears only where a provider is present;
   and
9. Viterbi in a separate summary lane that can be ignored without losing any posterior or flow
   fact.

All graphical facts have deterministic IDs and matching text rows. Differences are never
colour-only. The document opens under `file://`, contains no JavaScript or network dependency, and
remains complete with CSS/SVG disabled. It displays verified PTS plus a path-free, copyable command
template; exact playback is performed only by the separate local CLI/runtime after the caller
supplies media. Playback absence does not blank or downgrade the scientific packet.

## 8. Dependency and proof sequence

Implementation proceeds in the smallest landable slices already assigned. A later row cannot
borrow proof from an earlier row's prose checkpoint.

| Gate | Bead | Proof required before successor starts |
|---|---|---|
| B0R exact pair | `bd-01M1CQ5YS5M25F7HD9NN5YVAYP` | admitted artifact identities, transforms, aliases, timebase/repair and independent checker; no video bytes committed |
| Construction integrity | `bd-01M1EA8J4KFQRHZ5SF326B6WFZ` | adopted family/search/fixed-view artifact and refusal courts; witnessed baseline may then satisfy them |
| C1 portable source | `bd-01M1CQEWA4GWY5NW0QAGWYP31H` | checked source bundle, typed axes/coordinates, nonempty heterogeneous evidence support, cross-platform laws |
| D0 Sherlock adapter | `bd-01M1CQH3QNRMHZYR593DQWEBY7` | deterministic admitted-artifact replay, population separation, repair/foreign-identity refusals, no movie decoding |
| D1A source-to-story | `bd-01M1CQKRG1A4J4BEWCC78F4TEZ` | heterogeneous evidence survives compiler/model while text behavior stays unchanged; no fabricated canonical text |
| P/F prerequisite courts | `bd-01M1C8WSZ49M7SHG2HMQCWRK6E`, `bd-01M1CBKNA3KTBYTB3BACXJMXZZ` | frozen publication and complete nomination-provenance laws capable of failing |
| D1B alignment seam | `bd-01M1CQNM5DZZNWD8BN4WRKVZRQ` | exact movie intervals, checked nomination ledger, deterministic `P`/`F`, external states, refusal mutations, independent numeric oracle |
| Output authority repair | `bd-01M1CHWCRKMPNK6RARGT7JXVXX` | provenance-derived authority and immutable output root; caller labels cannot promote a result |
| V1 wire/view | `bd-01M1CQQDCWN2RTT1Z8XNNB7VMY` | versioned round-trip, full identity closure, renderer-neutral packet, deterministic textual twin, missing/zero/refusal preservation |
| E0 executable product | `bd-01M1CQSWJ538PAEME49200YARR` | actual admitted Sherlock pair, repeatable terminal run, byte-identical outputs, no movie bytes, local-open/accessibility/visual courts and second scientific review |

The diagnostic vertical ends at E0. Calibration, outcome-tuned model selection, omission or
coverage estimands, cross-subject claims, and comparative user-study claims are later work with
separately frozen data, denominators, estimands, and evaluation protocols.

## 9. Definition of done

The vertical is done only when all of the following are true:

- one command accepts direct recall text plus the actual admitted Sherlock pair and exits with
  typed stage outcomes;
- the same exact inputs run twice produce byte-identical deterministic artifacts;
- `result.json` binds the full source, recall, construction, projection, nomination, alignment,
  source-use/lineage, view, and software identities, and a mutation to any one breaks validation;
- contiguous cell plans, semantic-support alternatives, and their many-to-many incidence remain
  separately inspectable, and an unqualified fixed view cannot reach alignment;
- baseline construction and qualification are source-blind by court, and a source-informed child
  cannot validate as or masquerade as the primary source-frozen alignment;
- `P`, `F`, source/external mass, Viterbi, evidence, authority, coordinate basis, playback refusal,
  retained display remainder, and construction sensitivity are visible in both markup and text;
- exact playback is enabled only in the separate local runtime for a verified edition, checked
  annotation-to-PTS crosswalk, and caller-supplied media path; the shareable bundle contains only
  verified PTS and a path-free command template;
- the output contains no movie bytes or paths and cannot become shareable without the derived
  admission/disclosure outcome;
- partial/refused construction, failed renderer, and refused publication each preserve every prior
  valid artifact and receipt;
- direct `file://` opening, keyboard order, textual equivalence, non-colour distinctions, 200-percent
  zoom/reflow, deterministic screenshot review, and resource isolation pass; and
- the exact implementation candidate receives independent product, numerical/type, and scientific
  review against this PRD and the referenced courts.

## 10. Governing references

- Sherlock local-pair admission court: `bd-01M1CH1SW9K3ZW8D0HBTH9JVRP`
- [Sherlock source-representation audit](../design/sherlock-source-representation-audit.md)
- [Movie narrative architecture](2026-08-29-movie-narrative-architecture.md)
- [ADR 0007: film source representation](../adr/0007-film-source-representation.md)
- Recall-alignment visualization court: `bd-01M1C8WSZ49M7SHG2HMQCWRK6E`
- Story output bundle specification: `bd-01M199WBM2YVZ3GVC2GA61TYCJ`
- [ADR 0002: visualization contract](../adr/0002-visualization-contract.md)
