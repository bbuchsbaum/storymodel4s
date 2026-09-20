# D1A S4a typed nodes and fallible text draft — 19 September 2026

Mote: `bd-01M2TAFN5WMXW4NT20YJQAW2FR`. Source/test candidate `94e0a9a2`,
based on landed S3 checkpoint `5ddd9039`. **Qualification and local landing are
pending.** The mutation runner is active; final clean provider, format, docs and
consumer gates have not yet run for this candidate.

All five narrative node classes and `CircumstanceEdge` retain `TypedSupport`.
Their `SpanSet` construction overloads wrap `Text` without changing its spans or
unit references. The optional `textSpans` accessor returns text coordinates only.
Detached anchored components encode with `evidence-support/v1`; the 0.7.0 text
decoder refuses that shape. Text components retain the old `SpanSet` encoding.

`StoryModel.draft` and internal copy return `Either`. Their shared check requires
Text node/circumstance support and refuses anchored ordinary claims and boundary
beliefs. The constructor is companion-private. Every draft/copy derives order
from its resulting graph and text bundle; callers cannot supply an order.
Status promotion alone reuses the existing order with unchanged immutable inputs.
Source extent remains a validator concern, so malformed text drafts can still be
constructed for validation without admitting anchored support.

`PrimaryProjection.on` accepts Text on a TextCharacter primary in this slice.
It retains the complete SpanSet and uses its full hull for ordering by start,
end, then ID. Graph ordering requires an explicit bundle, including for empty
graphs. The unbound helpers are internal; model queries use the checked order.
The text compiler and trajectory derivation use the shared pre-model operation
and propagate refusal. Existing source/atlas and text-only model behavior remain
in place until S4b adds the sealed envelope and checked text witness.

The cold review found a preexisting omission: the former constructor guard
checked hierarchy boundary beliefs but missed beliefs stored only on a
trajectory step. The shared check now includes both locations. A separate text
step control and anchored-only/twin step refusals isolate that repair. The
[source review record](code-review.json) closes the source finding and explicitly
separates review from runtime qualification.

## Evidence currently available

- [Initial core/story compile](initial-story-compile.json) passed at `ed42ded0`.
- [First integration compile](integration-compile-1.json) stopped on an unmigrated
  fixture accessor. [Second integration compile](integration-compile-2.json)
  compiled all production modules, then stopped on test-only migration errors.
- [First focused run](focused-1.json) passed 34 core/story tests and compiled
  codec/document tests, then stopped on remaining fixture call sites. It is an
  overall failure, not a release gate.
- [Second focused run](focused-2.json) compiled downstream tests, passed three
  typed-support codec and eight frozen text parity tests, then formatted sources.
  Its dirty-after state is the declared formatter output, subsequently committed.
- [Formatted focused run](focused-final-1.json) passed all 39 selected tests and
  `scalafmtCheckAll` at `c2af2a93`. The final candidate appends one independent
  start-before-end ordering test. The [candidate control](isolated-control.json)
  passes all 40 focused tests before mutation.

The [settled inventory](guard-witness-inventory.json) names 36 mutations.
[qualify.py](qualify.py) records every exact edit, rejecting test and independent
passing control. Each of the ten construction, copy, Product, Mirror and graph
visibility probes receives its own clean test recompile. Every attempt is
retained; compilation failure or a surviving mutant is not a successful witness.
Final clean restoration archives all focused JUnit records. **Completed mutation
counts and release results remain pending.**

The expected S0 JSON remains unchanged at SHA-256
`cc201d9dd3e3576fabcd45677369f00c023ca5a455ea9d9e7ba694700c759fb3`.
Its four reference-node ordering lists now call the actual projection operation,
and discourse order comes from the model. The text component exemplar uses the
new canonical TypedSupport encoder. New small courts separately exercise hull-end
ties, crossing start/end order, exact ID ties, zero-length spans, reference
retention, deferred extent validation and cache recomputation after graph copy.

## Reproduction and remaining gates

Create a clean standalone clone at `94e0a9a2` and an exact grakern clone at
`0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Set `D1A_S4A_REPO`,
`D1A_S4A_GRAKERN` and a fresh `D1A_S4A_OUTPUT`, then run
`python3 qualify.py mutations` followed by `python3 qualify.py release`.
The latter runs `clean compileAll testAll`, separate format checks and all 13
executable documentation examples. Existing logs are refused instead of replaced.

The consumer migration changes two storyatlas4s test fixtures to unwrap checked
drafts and moves its immutable provider pin. The named branch
`work/d1a-s4a-consumer-20260919` is preserved in the real sibling repository.
Its exact final revision, formatting and full consumer receipt remain pending.
[consumer-gate.py](consumer-gate.py) runs the eight consumer test tasks, compile,
formatting and production `app/fastLinkJS` against clean exact provider/intaglio/
grakern clones. It binds the actual overrides separately from generated Pins.

Current checks provide local implementation evidence. This slice does not
establish a film compiler, film model codec, caption license, scientific
validation, executed CI or remote publication. S4b remains the next slice after
this one qualifies and lands.
