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

A post hoc word lookup also scored 24/24. It compares the subject noun in the supplied
source row with the subject noun in recall, and abstains on an anonymous subject.
This makes the exercise a check on how grades travel through the library. The sentences
are too regular to distinguish semantic understanding from subject-word matching.

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

## Reciprocal transfers

The second control uses the same eight synthetic occupations in four two-person scenes.
Each scene depicts two transfers of the same object. The giver in one transfer is the
recipient in the other. The assessor receives a specific transfer as the proposed reference.
The identical recall can therefore be faithful under one reference and reverse the roles
under the other. A changed-actor verdict describes the claim under the supplied reference.

For each transfer we render active and passive recall, and preserve or reverse the giver.
This gives 32 packets. Within each of 16 pairs, the source, reference, and recall word
counts are identical, while word order changes the answer. A deterministic assessor with
only those inputs and no recall word order can recover at most 16/32. The named-first
subject lookup is evaluated on the same packets.

The source occupations and objects come from the admitted miniatures. All transfers and
recall wordings in this control are original synthetic template transformations to exercise
grammatical roles. They use no participant material. Commit `41d973117` fixes the generator,
wordings, comparison rules and score before the second machine reading. The full shuffled
packet file hashes to `33e546c982403fa35fec321c8bdc5fd51bd1ae5b4dfd14376178e542f9db568b`.

A second fresh machine reader recovered 8/8 active and 8/8 passive role-preserving claims
as supported. It marked all 16 reversals as major gist-detail errors. All 32 citations were
admitted. The first-subject lookup recovered 16/32. Detection of the role change was the
declared endpoint; the severity or preferred wording of a grade needs separate evidence.
The four constructed scenes do not supply 32 independent observations about people.

Replay with `python port/evidence/actor-fidelity/role_order.py score port/evidence/actor-fidelity/transfer-reading.json`.
