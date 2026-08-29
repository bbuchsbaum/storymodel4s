# storymodel4s — constitution

**Status: DRAFT, open for dissent** on the mote `constitution` topic. Nothing here is settled.
It is a file rather than only a board thread because a successor should need one document, not
three hundred posts — see Article X.

Every article is derived from a failure this project actually had, not from principle. A
constitution assembled from good intentions is decoration; one assembled from the specific ways a
project has already gone wrong is a tool. Origins are named so an article can be argued against
its evidence.

## What this is, and is not

`AGENTS.md` says **how to work** — gates, reservations, evidence discipline. It changes weekly and
should. ADRs record **what was decided** and why, one decision at a time. This says **what the
project is for and will not do**. It should be hard to change and rarely need to be. If an article
is amended often, that is evidence it was a rule wearing a constitution's clothes; move it to
`AGENTS.md`.

It is not a licence to block work. Article I is about what we **publish**; an honest partial result
beats a refusal. The over-correction is real — a library that abstains whenever support is thin is
not more honest, it is differently dishonest.

## The articles

**I. We do not publish a measurement we could not make.** Not as zero, not as one, not as a neutral
constant, not as a silent omission that renormalizes the rest. *The defect is sign-agnostic — it
flatters on some metrics and penalises on others, which is why it survived so long.*

**II. Our failures are ours, not the participant's.** When the pipeline cannot do something, the
record says the pipeline could not do it. *A name is an inference too.*

**III. A published number names its estimand and its weighting population, carries its support in
the same value, and does not let normalization silently redistribute missing mass.** The choice of
formula belongs in an ADR — ratio-of-sums and mean-of-ratios are different estimands and either can
be the named target.

**IV. A type that asserts something makes it true by construction.** If a name or signature
promises a property, holding the value is proof of it. A promise enforced only by convention is not
a promise.

**V. An explanation is the evidence, or it is labelled a reconstruction** — and a reconstruction
must state what information the computing path discarded, or the label hides arbitrary explanatory
freedom.

**VI. Identity changes when meaning changes** — and does *not* change when meaning does not. A
receipt that distinguishes what is not different is the same lie inverted.

**VII. Every published claim must be falsifiable by evidence that distinguishes it from plausible
wrong answers.** Mutation testing, clean recompiles and positive controls are mechanisms and live
in `AGENTS.md`.

**VIII. The record says who did what, and corrections are append-only and public.** Actor and
session mechanics are `AGENTS.md`.

**IX. Human-source authority.** Participant and story text is not ordinary fixture material.
Redistribution and remote processing are governed by consent, REB approval and licence — never by
convenience or by a technical control that resembles one. **Pseudonymization is not consent.**
Attribution follows the person who told the story, not only the collector who wrote it down.

**X. The chief's authority rests on the record, not the session.** A successor inherits artifacts,
never memory — so keeping the role replaceable is a testable obligation, and transitions are
public.

**Appointment is the owner's.** The human owner appoints the chief, any successor, and any standing
deputy. The record makes a transition *cold-startable*; it does not make it *self-startable* —
mote actor identity is a caller-supplied label, demonstrated three times in one night, and an
unauthenticated label cannot confer landing or ADR authority.

**An unappointed acting coordinator is limited to reversible coordination**: preserve reservations,
surface blockers, assemble and run gates, keep the record current. It must **not** land, push,
close another actor's work, or revise an ADR ruling until the owner appoints. *The distinction is
reversibility, not seniority.*

Timers, objection windows, reading cadence and gate batching are governance mechanics and live in
`AGENTS.md`.

## Amendment

Proposed on the `constitution` topic, argued in the open, ratified by the chief with dissent
recorded. An article is removed only by showing the failure it was derived from is not a failure.

## Handover surface

The record is a **typed hierarchy, in precedence order** — a reading list without a precedence
order is a trap, because a cold agent will mistake a superseded proposal for a ruling:

1. this file · 2. owner directives · 3. ratified ADRs (`docs/adr/`) · 4. chief decisions ·
5. evidence (gates, mutations, measurements) · 6. discussion.

Read, in order: this file · `AGENTS.md` · the pinned state-of-the-board summary on `coordination` ·
`docs/adr/` · `docs/design/unforgeable-types.md` · `docs/design/adr-0003-migration-status.md` ·
`docs/design/story-text-admission-checklist.md` · the open bead list with owners.

**Board posts are level 6 unless they are a chief decision or an owner directive.** This board
carries withdrawn rulings and retracted expectations; several proposals in it were explicitly
killed by later posts.
