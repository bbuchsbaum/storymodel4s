# Replacement intake cold review — 23 September 2026

Ticket: `bd-01M2WTPDBB4MQBXEJ181Q85ZG2`.
Reviewed revision: `b737b14f9efcd5a055e44355736d8a442388326a`.
Reviewer: independent `p1_cleanup_review`; read-only, with no implementation role.

The original truncated review cannot be recovered. This is the fresh review of
A–C requested by the ticket's 22 September disposition. Its findings are current
observations, not a reconstruction of the missing historical “A3”. No build,
participant-file read or new corpus experiment was performed.

## Boundaries reviewed

- `CorpusProfile` derives content identity through nested, framed rendering. No
  caller-supplied identity construction path was found.
- `CorpusReader.open` consumes the verified snapshot, distinguishes unreadable
  cells from blank cells, and refuses structural mismatches.
- `SegmentLink.of` checks work, totality, range and declared mapping shape.
  `compose` checks intermediate content identity after the more specific
  coverage refusal, preserving the existing diagnostic contract.
- The Sherlock header's `annotation-raw-to-part-playback-v1` replay claim is now
  supported: `SherlockRecallMapping.scala:331` loads the repair record;
  `TimebaseRepair.scala:636` binds actual bundles and the record digest;
  `SherlockAnnotations.scala:219` executes checked per-row `ClockRepair`.
  Synthetic tests cover missing records, crossed axes, fractional ticks and
  retained row receipts. This proves the declared replay path by source review,
  not media correspondence or independent notebook authenticity.

## Findings and disposition

**Endpoint identity omitted by mapping comparison — `bd-01M37D5S1F94RT8MVWK58B5575`.**
`corpus/link.scala:233`, `sameMappingAs`, compares segmentation names and ordinal
maps but omits `fromIdentity` and `toIdentity`. Two links with the same names and
ordinal map but different endpoint onsets therefore compare equal by inspection.
No production caller was found. This is advisory for this review closeout, but
blocks using that helper as content-qualified equivalence.

Proposed discriminating witness, using the existing composition suite helpers:

```scala
val a = seg("a", Vector(0, 10, 20))
val b1 = seg("b", Vector(0, 10, 20))
val b2 = seg("b", Vector(5000, 6000, 7000))
assert(!bijection(a, b1).sameMappingAs(bijection(a, b2)))
```

The witness was not executed in this review. A repair should compare endpoint
identities while retaining the positive control that identical endpoints with
separate declared/composed link evidence compare equal.

**Diagnostic cap bounds output, not collection — `bd-01M37D6APTEQTVVSMSC195Z184`.**
`corpus/intake/CorpusReader.scala:164–172` retains every per-row refusal and flattens
them before applying `take(RefusalCap)`. Totals and missingness are correct; the
bounded-collection rationale is not implemented. This is an advisory resource
and documentation finding, with no measured exhaustion claim. Clarify the limit
or accumulate the total while retaining only the first 100 diagnostics.

## Refuse-to-merge verdict

Refuse a change claiming content-qualified equivalence through the current
`sameMappingAs`, or bounded diagnostic collection through the current reader.
Do not block this review-delivery ticket on implementing those separate findings:
the requested fresh review is complete once this record and the two follow-ups
are committed. The absent historical reviewer message is not an open dependency.
