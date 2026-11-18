# Can the reader distinguish the actor once the event is located?

A fresh Codex machine reader assessed 24 synthetic detail claims through the port's
unit-only conditional fidelity prompt. The source row was supplied in every case. The reader
saw each question and its source evidence, with no scoring key, condition label, or prior
assessment. It received the shuffled questions together and returned one judgment per packet.

| Source and recall condition | Returned grade | Count |
|---|---|---:|
| Recall names the stated actor | Supported | 8/8 |
| Recall names the other actor in the same scene | Gist with a detail error | 8/8 |
| Source gives the action but withholds the actor | Unverifiable | 8/8 |

The library admitted all 24 assessments with source spans and preserved every supplied
reference. Scoring an event match as evidence for every recalled detail would label all
24 supported and recover 8. All eight events met all three conditions.

## Reproduction

From a checkout with the port installed in the active Python environment, run
`python port/evidence/actor-fidelity/check.py score port/evidence/actor-fidelity/reading.json`.
The replay consumes the recorded judgments. To regenerate the shuffled questions, use
`check.py prepare OUTPUT.json` with the same interpreter. The exact packet file SHA-256
was `7edc2a76c033e0ad5c201dfc9311e1b44bee4986a36351c1c755372eab1c9a62`.
Commit `eeb2f243f` fixes the construction and scoring before the reading. For a changed
actor the scoring key accepts either contradiction or gist with a detail error. For an
unknown actor it accepts unverifiable or unavailable. An invalid or rejected assessment
earns no credit. The transport expands the reader's chosen source offsets to exact quotes.

## Material and scope

The source is the project's admitted, fictional
[baseline miniatures](../../../tools/recall-study/fixtures/baseline-miniatures.json).
We pair successive events in one scene. The recall uses a definite article. In one version
the actor comes from the paired event. In another we anonymize the actor in the source.
All other action wording is held fixed. No participant or film recording was used.

This exercises grade selection and evidence admission with a known event. The eight target
events share four two-event scenes, and all use simple, closely matched wording. Accuracy on
spontaneous recall, automatic event selection, pronoun resolution, and the relative performance
of other assessors remain open. The reader is a Codex agent; the FilmFestival subscription
mapper was not evaluated in this exercise.
