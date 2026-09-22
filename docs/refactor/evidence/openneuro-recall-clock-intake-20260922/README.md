# Explicit recall-clock intake evidence

22 September 2026. Source revision `5a2c5387240ae44b18977b226c32f33d7928a742`,
preserved on `solo/openneuro-recall-clock-intake`. This is the independent input
oracle slice of scanner-crosswalk ticket `bd-01M354E83Z4Z7DR4KPP6Q45MAJ`.
The full ticket remains open and retains its existing dependencies.

[Usage and output contract](../../../../tools/recall-study/README.md#explicit-recall-clock-intake)
describe the tool. [receipt.json](receipt.json) binds source/test/fixture bytes,
commands, totals, logs and the development check. The main Scala mapper remains
unchanged. The scope checker reports no changed Scala types; no Scala build or
`checkAll` result is claimed for this Python-only implementation.

Both restored controls passed 22 tests. All 11 independently compiled mutations
failed their named assertions, with one test failure each and no error-only kill.
[mutation-receipt.json](mutation-receipt.json) binds each mutant and log; the
complete synthetic logs are retained in `logs/` as lossless JSON `text` fields.
Their digests bind the decoded UTF-8 bytes, preserving unittest's trailing spaces
without introducing whitespace errors into committed text files. The mutants cover wrong clock
selection, missing byte identity, dropped empty records, fabricated zero,
floating-point rounding, missing count enforcement, broken alias identity/hash,
invented scanner authority, leaked source text and output overwrite.

Separate review executed two extreme-number failures. The repair bounds numeric
representation before decimal/fraction expansion and returns located structured
refusals. The separate targeted recheck passed both new numeric tests. Tests and
mutation controls were run against the final source bytes before the source
commit; the receipt checks those bytes against the committed revision.

The [development receipt](development-receipt.json) records a single fixed source,
`recall-source-03`. Membership in the frozen development partition was checked
before reading the CSV. Both CLI clock selections passed with all 2,495 records,
no missing numeric values, and the same canonical identity and six-column
values. A separate CSV read compared every selected onset to its original column.
Its exact paired differences are 7.5 seconds and 5 released TR numbers. These
are observations for this source, not a general correction rule or a verified
BIDS volume convention. The checks read no gold or untouched-test transcript.

The development probe and full numerical artifacts remain in the task's private
temporary evidence; their hashes are recorded here. Commands in the committed
receipt replace private input/output paths with explicit placeholders. No
participant prose is committed. The original synthetic fixtures support replay
without access to those data.

This qualifies byte-bound clock intake and record accounting. Canonical recall
word joins, source/scan-run transforms, cartoon boundaries, word durations,
recall-to-encoding inference and temporal uncertainty remain later work under
the [workflow plan](../../../plans/2026-09-22-recall-encoding-workflow.md).
StoryAtlas continues to consume the later canonical mapping/query contract;
this experimental intake artifact does not add a second visualization format.
