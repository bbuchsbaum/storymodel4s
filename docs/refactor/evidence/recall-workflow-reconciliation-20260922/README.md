# Recall-to-encoding planning reconciliation

22 September 2026. Governing design:
[reusable workflow](../../../plans/2026-09-22-recall-encoding-workflow.md).
The [active backlog](../../BACKLOG.md) is the human-readable crosswalk;
[receipt.json](receipt.json) records the applied changes and live readback checks.

The reconciliation amends ten existing StoryModel and three existing StoryAtlas
tickets, creates three bounded StoryModel follow-ons and one StoryAtlas consumer,
and supersedes one duplicate pin-qualification issue after transferring its
obligation. The new tasks belong to existing delivery epics. Active G1 and M1
acceptance remain bounded.

The sole removed dependency is organization readouts -> basic mapping exchange.
Transition-table implementation, independent reader integration and tiny-answer
validation move together to full G2 preview. Its compatibility, organization and
synthetic-recovery requirements remain intact.

StoryAtlas owns dynamic temporal inspection and portable HTML reporting. Its new
temporal consumer remains blocked until an exact producer revision, versioned
query/schema contract, generated fixtures, independent expected answers and gate
receipts are available. Its local dependency is M1 final acceptance. Foreign
ticket IDs are provenance, not local dependency edges.

Independent review corrected the full-part/episode-only scanner distinction,
made codec/reader ownership explicit in the preview ticket, and removed obsolete
first-product milestone wording. Optional AV acquisition and empirical
calibration do not gate the basic engineering workflow.

Verification reads full ticket bodies, dependencies, parent relations and readiness
through `mote --json show`, checks local ID resolution against `mote --json ls --all`,
and runs `mote --json doctor` in each repository. The receipt preserves exact body
digests, before/after dispositions and store warnings. It is a dated planning
checkpoint; future legitimate ticket edits can change those digests. The original
19 September reconciliation and execution receipt remain historical records.

No Scala build, browser run, participant inference, new empirical evaluation or
publication is claimed by this planning reconciliation.
