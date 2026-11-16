# F1 checked film-proposal inputs

F1 assembles portable, checked inputs for later film compilation. It does not acquire media,
execute a parser, accept a film claim, compile a film model, or establish scientific accuracy.
The reviewed source candidate is `e02529acbb3f8060aa920cd78bf1c042f350b493`.

`FilmProposalSurface` checks the ordered source inventory, original units, full source-bundle
binding, exact descriptions, deterministic recipe and receipt, and sentence-to-record origins.
Each generated sentence inherits its record's complete physical envelope. Original row/scene
units remain independently addressable, with no proposal-text sentence substituted for physical
support. Unsupported source evidence is refused even if no compiler attempt uses it.

`FilmCompilerInput` then checks an independently supplied parse inventory against actual charts,
parser origins and a designated producing receipt. Recipe identity remains a declared expected
pin, not an execution attestation. Attempt support must lie inside the exact permitted union on
each stream and axis, retaining gaps, isolated points and half-open interval ends. A direct
primary-axis member is required. Actual chart references select parse and source provenance.

## Evidence

- Independent code review passed the exact candidate after two blocking findings were repaired:
  parser/producing-request joins, and portable calibrated-credence encoding.
- All **39 focused JVM checks** passed with fatal warnings enabled. They cover independent source
  pins, original-unit preservation, full chart payloads, real alignment containment, exact
  provenance selection, intervals/points, native support, same-axis wrong streams, stale full
  bundle identities, external construction refusals and the executable guide example.
- Three compiled mutations each fail one named assertion while all twelve sibling tests pass:
  ignoring parser origin, ignoring the sentence end boundary, and omitting the producing-call
  selection from input identity. An earlier mutation that left an unused parameter did not
  compile; it is excluded from qualification. Earlier exception-based refusal failures were
  replaced by explicit assertions, and their interrupted full gate is also excluded. V3 receipts
  retain the exact candidate, driver and source hashes, substitutions, and log hashes.
- The calibrated-chart golden is independently encoded with Python `hashlib` and IEEE-754 bits
  in [calibrated-golden.py](calibrated-golden.py). Its checksum is
  `399403c26574ed94f568145af09f620ce09615fa599b4932282b29e7efd933d4`.
- The [full qualification receipt](qualification.json) records **12,234 passed, zero failed/errors,
  five skipped** across 56 producer test tasks on JVM, JavaScript and Native. The five skips
  belong to optional live/model-dependent provider, benchmark and media checks; every F1 test ran.
  S0 text parity passes nine tests; the WOG codec golden passes six per platform.
- StoryAtlas `ef0844bb229a0406d4f2a4e65ec89532c18d99a3`, bound to this exact producer, passes
  **520 tests, zero failed/errors/skipped** across ten test tasks. Compilation, JavaScript app
  linking and final formatting checks pass. No browser or hosted-CI qualification is claimed.
- [Mutation receipts](mutations.json) retain exact source/driver hashes and substitutions;
  [independent reviews](reviews.json) distinguish code, mutation and gate evidence.

Raw logs and execution metadata remain under private ignored `data/.f1/20261116`. No participant
text or protected evaluation data is part of this input-boundary qualification.

## Identity framing

The independent framing review found no ambiguous lawful encoding. Complete chart checksums bind
receipt inventories; scope sentence lists are unique and end when a contributor repeats a declared
sentence; generated sentence IDs use a `story:...:sN` shape while support identities are bare hashes.
Upstream IDs reject control separators. These checked constraints must be retained if the encoding
is extended. Support identity intentionally canonicalizes anchor order and duplicates.

## Reproduction

Use a standalone checkout of the exact candidate, not a linked worktree or the shared primary
checkout. The complete gate, with formatting last, is:

```sh
sbt 'set ThisBuild / tlFatalWarnings := true' compileAll testAll scalafmtCheckAll scalafmtSbtCheck
```

`testAll` includes S0 text parity and the applicable WOG backend courts. The public example in
[the guide](../../../guide/film-proposal-inputs.md) matches the marked region of `FilmInputSuite`.
The example runs both expected and substituted records. The mutation runner operates only on an
expendable standalone clone and restores the source in a `finally` block:

```sh
python3 docs/refactor/evidence/f1-20261116/calibrated-golden.py
python3 docs/refactor/evidence/f1-20261116/mutate.py --clone /path/to/expendable/clone --output /path/to/new/logs
```

Mutation builds are not a clean construction-probe gate. Run the full gate from a fresh checkout
so both document production code and outside-package compile-refusal probes compile afresh.
