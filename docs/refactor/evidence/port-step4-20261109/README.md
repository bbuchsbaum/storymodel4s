# Port step 4: categorical mapping artifact (2026-11-09)

Plan:
[the step-4 plan](../../../plans/2026-11-09-categorical-mapping-and-transfer-plan.md).
Everything here is development data. Bundles hold film annotation text, so they stay under the
ignored `data/` root; this directory holds counts and commands only.

## 4a: categorical decision support

Commits faede5459 (implementation) through 5ec21c243 (review fixes).

- `UnitOutcome.Decision` carries a sealed support: numerical (basis, raw argmax, decoded mass, as
  before) or categorical (the producer's policy, nothing numeric). `UnitOutcome.categorical`
  builds a score-free choice or explicit no-assignment. Its links must be supplied, never derived
  from an inference result.
- Numerical decisions encode exactly as before. A record holding a categorical decision is written
  as `mapping-record/v0.2` (exchange v0.2); a categorical decision inside a v0.1 document refuses.
- Existing tests changed only in how they reach the numerical fields
  (`decision.x` to `decision.numerical.get.x`). One refusal test moved its unknown version from
  v0.2 to v0.3.

Gate at 5ec21c243, clean clone, `tlFatalWarnings := true`:

| Run | Module runs | Passed | Failed | Skipped |
|---|---|---|---|---|
| JVM, the 21 reference-scope modules | 21 | 4,442 | 0 | 1 |
| JS: align, codec, fixtures, laws, view, interview | 6 | 2,054 | 0 | 0 |
| Native: the same six | 6 | 2,054 | 0 | 0 |
| `scalafmtCheckAll; scalafmtSbtCheck` | | clean | | |

JS and Native ran on the modules that depend on `align`; no other portable module can see the
change.

Mutations, each compiled and failed one named test while its siblings passed:

- **Source-membership check removed.**
  - Fails "a destination outside the source refuses".
- **Candidate-set check removed.**
  - Fails "a link minted for a foreign unit refuses by candidate set".
- **Supplied-links check removed.**
  - Fails "a result-bound link refuses: a categorical outcome carries only supplied links".
- **Selection export version hard-coded.**
  - Fails "selection export names the mapping version of the record it copies from".

An independent review of faede5459 found one blocking defect (the workspace selection export
labelled categorical records v0.1) and one design question (result-bound links on a categorical
outcome). Both are fixed in 5ec21c243. Its remaining findings are bead
`bd-01M711XR56SR3Y4739NJH7K2TD`.

### storyatlas4s

storyatlas4s does not compile against this change unmodified: two sites read a numerical field
directly from a decision. With [storyatlas4s-fix.patch](storyatlas4s-fix.patch) applied to a
scratch clone at 50a1601, `compileAll testAll scalafmtCheckAll` passes (10 test runs, 0 failed).
storyatlas4s pins storymodel4s at eb48a5622, so it is unaffected until that pin moves; the patch
goes in with the pin bump.

## 4b: recorded responses to a bundle

Commits ac4b42a96 (implementation) through 173343bdc (review fixes).

- `AgentAnswers` reads each reply item into narrative, referent, alternatives, rows and
  confidence, each as absent, explicit null, valid or invalid. Each unit is answered, omitted, or
  in an unparsable chunk.
- `AgentMappingRecord` builds two checked categorical records, narrative and referent, each under
  its own decision policy.
- `AgentBundle` saves both records, every unit's full answer, provenance and a source dictionary.
  Reading it back re-checks the records through the mapping codec.
- The replay's arm projection no longer writes null for an omitted unit or a missing field.
- `tools/agentmapping/read_bundle.py` reads a bundle with the standard library only.

Gate at 173343bdc, clean clone, `tlFatalWarnings := true`: `pipeline/test` passed 494, failed 0;
`providerAgent/test` passed 82, failed 0, skipped 1; format check clean. Python reader tests: 9
passed.

An independent review of ac4b42a96 found that the command bound answers to units by id alone. A
unit id encodes the transcript and the unit's position, not its text, so the command now also
requires each table text to equal the rebuilt unit's text. Its other findings fixed in
173343bdc:

- the Scala reader refuses swapped records and unit rows that disagree with a record;
- the Python reader refuses missing or duplicated units;
- the command writes per-field status counts ([bundle-receipt.json](bundle-receipt.json));
- the provider identity is a digest of the named model, not of the prompt.

The rest are bead `bd-01M714FSQMKRJMRGAAKMHS41KP`.

Mutations, run at ac4b42a96:

- **An absent field treated as a no-assignment.**
  - Fails "an explicit null is a no-assignment; an absent or invalid field is invalid output".
- **Narrative reads the referent field.**
  - Fails "narrative and referent are separate records and can differ for one unit".
- **An omitted unit reported as unparsable.**
  - Fails "every unit gets an outcome: answered, omitted, or its chunk unparsable".
- **Projection writes null for a missing field.**
  - Fails "an omitted unit is left out and listed missing; a missing field stays missing".

The first two also fail the golden-bundle test, as they should.

### Development run

`agentMappingBundle` over the slice-3 recordings, model `opus`, chunk 60:

- 11 participants, 1,744 units, all answered; 34 exchanges; 0 model calls.
- Each bundle was reloaded and compared before the command reported.
- Every unit table's text equals its rebuilt unit's text, for all 11 participants.
- Unit-by-unit against the frozen slice-1 arm ([parity.py](parity.py),
  [parity-result.txt](parity-result.txt)): **0 narrative mismatches in 1,744 units**, in both
  the unit rows and the checked records. (The 1,499 figure elsewhere is
  the 10 participants with gold.)
- Replay reply files are byte-identical to slice 3's, and the arm built from them is byte-identical
  to slice 1's.

What the bundles hold that the narrative-only replay discarded:

| Field | Count over 1,744 units |
|---|---|
| Referent scene given | 1,683 |
| Referent explicitly null | 61 |
| Narrative and referent differ | 72 |
| Alternatives present | 1,744 |
| Confidence present | 1,744 |
| Rows field valid | 1,744 |
| Rows cited | 3,225, all resolving in the source dictionary |

None of these is scored. Alternatives and confidence are self-reports; a resolving citation is a
valid reference, not evidence that the row supports the claim.

The synthetic fixture (`pipeline/src/test/resources/agentmapping/synthetic.bundle.json`, invented
text) covers what this all-successful run cannot: differing narrative and referent, a citation
outside the narrative scene, a citation to no row, and omitted, unparsable, invalid, absent and
explicit-null answers.
