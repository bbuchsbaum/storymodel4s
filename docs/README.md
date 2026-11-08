# Documentation map

This is the one index for `docs/`, last reorganized on 2026-11-08. If a document is not listed
here as current, treat it as a historical record.

## Start here

| Document | Role |
|---|---|
| [../README.md](../README.md) | What the library is, smallest example, maturity, build |
| [../VISION.md](../VISION.md) | Vision, mission and standing commitments |
| [ROADMAP.md](ROADMAP.md) | **The one current plan**: milestones, finish line, open decisions |
| [../AGENTS.md](../AGENTS.md) | How work runs, design contract, build and landing |
| [adr/README.md](adr/README.md) | Architecture decisions, one status each |

Mote holds current status (`mote ready`). Documents hold direction and decisions.

## Current reference

- **Design contract and patterns.**
  - [design/design-contract-notes.md](design/design-contract-notes.md)
  - [design/unforgeable-types.md](design/unforgeable-types.md)
  - [design/story-text-admission-checklist.md](design/story-text-admission-checklist.md), which
    is binding under rule 14d
  - [design/story-output-bundle-spec.md](design/story-output-bundle-spec.md), a draft
  - [refactor/ANALYSIS_CONTRACT.md](refactor/ANALYSIS_CONTRACT.md), the mapping semantics paired
    with ADR 0019
- **Evidence.** [EVIDENCE.md](EVIDENCE.md). Its proof-of-fix and gate-running rules bind all
  work; since 2026-11-08 its pre-registration and ledger rules apply to held-out validation only.
- **User guides.**
  - [guide/](guide/): the mapping facade, study inputs, study workspaces and imaging
  - [guides/video-source.md](guides/video-source.md)
  - [workflows/offline-text-source.md](workflows/offline-text-source.md)
  - [../docs-site/](../docs-site/), the public site
- **Component and CLI reference.**
  - [mapping-exchange.md](mapping-exchange.md)
  - [recall-timing.md](recall-timing.md) and [recall-timing-intake.md](recall-timing-intake.md)
  - [recall-volume-export.md](recall-volume-export.md)
  - [scanner-crosswalks.md](scanner-crosswalks.md)
  - [storymodel-export.md](storymodel-export.md)
  - [temporal-queries.md](temporal-queries.md)
  - [workspace-exchange-cli.md](workspace-exchange-cli.md)
  - [organization-analysis.md](organization-analysis.md)
  - [development.md](development.md)
- **Datasets.** [data/](data/) holds admission records for Sherlock, FilmFestival, Friends and
  Memento. Data bytes live under the ignored `data/` root, never in Git.
- **Release lane.**
  - [api-stability.md](api-stability.md)
  - [release-preparation.md](release-preparation.md)
  - [refactor/RELEASE-QUEUE.md](refactor/RELEASE-QUEUE.md), kept for its acceptance records

## Plans: what is still live

| Plan | Status |
|---|---|
| [2026-08-29 movie narrative architecture](plans/2026-08-29-movie-narrative-architecture.md) | Foundation for the film event layer; read with ADR 0007 |
| [2026-09-17 D1A source-to-story](plans/2026-09-17-d1a-source-to-story-plan.md) | Text slices largely landed; the film half continues in the plan below |
| [2026-09-20 film compiler phase plan](plans/2026-09-20-film-compiler-phase-plan.md) | Re-activated 2026-11-08 as part of milestone E1 in the ROADMAP |
| [2026-10-24 NMP integration](plans/2026-10-24-nmp-integration.md) | The proposal wire format for LLM-produced events (E1/E2) |
| [2026-10-25 identity feasibility inputs](plans/2026-10-25-identity-feasibility-inputs.md) | Input to cross-modal identity (E1) |
| [2026-10-25 recall review workbench](plans/2026-10-25-recall-review-workbench.md) | Paused; its clarity revision was lost and must be redone before use |
| [2026-09-22 recall encoding workflow](plans/2026-09-22-recall-encoding-workflow.md) | Downstream imaging/time contract; consumes mapping outputs |
| [2026-09-02 recall-to-video study log](plans/2026-09-02-recall-to-video-study-log.md) | The running measurement record; append, don't rewrite |
| [2026-09-02 gold-scene preregistration](plans/2026-09-02-gold-scene-preregistration.md) | Governs held-out Sherlock reads only |
| [2026-09-03 within-scene precision preregistration](plans/2026-09-03-within-scene-precision-preregistration.md) | Human lane still owed by the owner |

Every other file in [plans/](plans/) is a dated historical record:

- the August M1 plans and proposals;
- the September 1-5 Sherlock PRDs, handoffs, FilmFestival runs and readouts;
- the 1.0 handoff and session notes;
- the best-mapper plan (2026-09-18), whose research direction is superseded by the ROADMAP;
- the G1 mapping-records plan.

## Historical records

These carry a banner, stay in place so their links keep working, and are not guidance:

- [refactor/PLAN.md](refactor/PLAN.md)
- refactor/BACKLOG, DELIVERY_BACKLOG and TURNAROUND_PLAN
- refactor/vision.md and refactor/mission.md
- [refactor/STATE-20261006.md](refactor/STATE-20261006.md)
- refactor/goals/
- refactor/evidence/, the receipts of past gates and readouts
- [governance/fleet-mode.md](governance/fleet-mode.md) and `../fray_notes.md`
- [CONSTITUTION.md](CONSTITUTION.md)
- `../NARRATIVE_PROCESS_ALIGNMENT_NOTES.md`
- calibration/, apart from `participant-roles.md`
- reviews/
- the courts, inventories, admission ledgers and salvage records in design/
- design/gates.md
- `../handoffs/`
