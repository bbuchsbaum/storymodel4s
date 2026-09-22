# Recall word-clock join qualification

22 September 2026. Implementation `9d366e267b48b174769c8f7b889b576917ce2321`
on `solo/recall-word-clock-join`, stacked on clock-intake evidence `6a6f22f4`.
This is independent consumer groundwork for timing ticket
`bd-01M2WVH1DC8ZPDC992G4MX3TXG`, with a handoff to scanner ticket
`bd-01M354E83Z4Z7DR4KPP6Q45MAJ`. Both tickets remain open.

[Usage and contract](../../../../tools/recall-study/WORD_CLOCK_JOIN.md) describe the
reader. [receipt.json](receipt.json) binds all changed source/fixture files, test
commands, control totals, compiled mutants, CLI outputs, producer provenance and
the independent Java character comparison. The scope checker found no changed
Scala types. No Scala build or full `checkAll` qualification is claimed.

## Executed checks

- The join suite passes 25 tests and the shared intake suite passes 23: **48
  tests** across the two suites, with accepting controls before and after each
  mutation run.
- **28 compiled mutants are killed**: 16 join mutations and 12 intake mutations.
  Each fails one named test assertion, rather than a syntax error or runtime
  exception. Some refusal checks also constrain which invariant reports the
  error; their failure does not imply that every guard bypass admitted input.
- The actual G1 `historical.json` producer artifact is consumed at its independently
  recorded byte pin. Its inventory, segmentation, original word identities and
  transcript coordinates are checked by the Python reader.
- Both CLI clock selections match independently authored positions and onsets.
  Inspect the full synthetic [OpenNeuro](outputs/openneuro.json) and
  [Princeton](outputs/princeton.json) output examples.
- A lightweight Java 22 probe compares all **65,536 UTF-16 code units**, finding
  the same 81 prohibited identifier characters as the reader's explicit table.
  The probe source is [preserved here](RecallClockIdCharacters.java); this is not
  a Scala or cross-backend build.

Logs are lossless JSON `text` fields in `join/` and `intake/`; recorded log hashes
bind their decoded UTF-8 bytes. This retains unittest's original trailing spaces
without adding whitespace errors to repository text files.

## Review and corrections

A separate reader executed three defects: an empty unit span strictly inside a
word acquired ownership; whitespace/control/overlength IDs could pass inventory
checks; and oversized JSON integers escaped as runtime exceptions. Each has a
repair, accepting control and discriminating mutation. The interior-empty-span
fixture stays wordless, supplementary IDs retain proper UTF-16 length, and JSON
integers above 128 digits refuse privately before conversion. Six separate
targeted repair checks passed with no remaining finding.

One initial test incorrectly assumed a 2,000-deep JSON document would hit this
Python runtime's decoder limit. That document was accepted. The test now checks
translation of the decoder's `RecursionError` through an injected exception; no
fixed nesting threshold is claimed. Numeric representation limits are explicit
and tested with actual payloads.

## Qualification boundary

This establishes structural CSV-record→word→unit correspondence and onset-only
readouts. An exact transcript match does not prove two artifacts belong to the
same recording. The full mapping record still requires contextual Scala
validation, and the reader labels that operation as unperformed. Inventory-only
Unicode/regrouping envelopes are explicitly distinguished from the actual full
producer artifact.

No participant transcript or gold was opened for this slice. No scanner origin,
cartoon boundary, word duration, time exposure, mapping accuracy or temporal
calibration is established. Existing Scala mapper defaults remain unchanged.
StoryAtlas consumes the later canonical timing/query handoff; these experimental
readouts do not replace its planned interface.
