# Refactor review and delivery index

Start with [PLAN.md](PLAN.md): the assessment, decisions, execution order and stopping gates.
[BACKLOG.md](BACKLOG.md) maps that plan to live Mote work; [RECONCILIATION.json](RECONCILIATION.json)
accounts for the 119 initially unfinished tickets and 17 intake issues filed concurrently on
19 September 2026.

Authority, in order:

1. Owner rulings and the repository's single-developer/design/evidence rules.
2. PLAN.md for delivery order, with [ADR 0019](../adr/0019-mapping-measurement-policy.md) and the
   amended [analysis contract](ANALYSIS_CONTRACT.md) for mapping semantics.
3. The approved [D1A technical plan](../plans/2026-09-17-d1a-source-to-story-plan.md) for S0–S4c
   and ruling E. Its detailed migration contracts remain binding.
4. The September 18 mapper plan for research protocols, exposure rules and comparisons where
   PLAN.md has not changed sequencing. Its proposed P1 scope reduction remains unadopted.

`TURNAROUND_PLAN.md`, `DELIVERY_BACKLOG.md`, `DELIVERY_BACKLOG.json`, `vision.md` and `mission.md`
are the supplied consultant package, preserved as review input. Do not import its 30 TA keys as
another backlog. BACKLOG maps them to the reused/new tickets. Its referenced `AUDIT_EVIDENCE.md`
was not supplied. Root vision/mission are the adopted charter; capability claims live in PLAN's
evidence table and future gate receipts, not in the charter.

The [execution receipt](EXECUTION_RECEIPT.json) records the applied tracker counts, ready queue, checks, and qualification limits.

Implementation evidence: [frozen Sherlock mapping baseline, 19 September 2026](evidence/sherlock-baseline-20260919/README.md).
Tracker snapshots above describe the planning checkpoint; use live Mote for current status.
