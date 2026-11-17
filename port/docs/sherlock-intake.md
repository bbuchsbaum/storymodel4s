# Sherlock formats into the shared analysis workflow

The source and recall adapters consume the retained file formats, preserving original bytes
and the declarations needed to interpret them. They use synthetic fixtures for qualification;
no participant text, media file or live provider is required.

## Source annotation TSV and repair declaration

`prepare_sherlock_source(key, tsv_bytes, repair_bytes)` reads the annotation table and the
existing `storymodel4s.sherlock.timebase-repair`, schema version 2, declaration. It checks the
annotation hash, sequential row numbers, raw bounds, scene openings, native part extents,
crosswalk equations, exact frame boundaries, scanner bounds and missingness, declared tails,
and notebook-coordinate replay checksum. Notebook repair remains declared provenance; its
offsets and dropped scan-break rows do not replace the media playback coordinates.

The receipt retains raw TSV/repair JSON, parsed rows, raw annotation cells, scene groups,
part identities and per-row loci. A zero-duration annotation is an instant. Every other
nonempty interval retains its own part-local clock. Scan-break rows keep missing TR bounds.
The `.prepared` view derives the existing generic source text/row representation in declared
part order. Graph construction uses the annotation descriptions as textual evidence; it does
not certify audiovisual content or turn annotation metadata into accepted graph claims.

## Recall word CSV and lineage

`prepare_sherlock_recall(key, csv_bytes, lineage_bytes, source_id=...)` checks the selected
entry of the retained recall-lineage schema: exact byte length, SHA-256, record count and the
seven-column header. It retains all cells, including unused timing columns and blank words.
The word column is trimmed using the retained ASCII trim policy, nonblank cells are joined
with single spaces, and existing recall canonicalization/segmentation constructs the text view.
Lexical occurrence identities bind the CSV bytes and parsed index; their Python namespace is
explicitly distinct from Scala IDs.

Use the checked strict CSV behavior from `RecallDisclosure`/`RecallTimingIntake`, including
quoted commas/newlines and doubled quotes. The old convenience reader that split lines and
commas is not reproduced. A BOM remains in the raw receipt but is ignored for header parsing.
Caller-supplied lineage is an identity declaration, not a public-disclosure permission grant.

`sherlock_recall_timing(intake, "princeton" | "openneuro")` selects one word-onset column.
It requires unchanged replay coordinates and exactly one lexical word per nonblank cell;
an onset is never distributed across several words. Selected blank onset cells remain missing.
The readout preserves input order, including repeated or decreasing onsets. Unit onset is the
first assigned lexical word's onset, even when missing; it is not the minimum timestamp or
the next available measurement. Recording identity, origin and synchronization remain
unestablished. No duration or interval is inferred from an onset.

Decimal parsing is exact and bounded in length/exponent. Python integers deliberately allow
exact rationals beyond Scala's 64-bit range, with fraction strings on the wire. This extends
representation without floating conversion or rounding. Nonfinite/malformed values refuse.
Only the selected onset column is interpreted; other clock/TR columns remain raw evidence.
`verify_sherlock_recall_timing` recomputes the readout from actual input, including its lineage.

## Construction and installed commands

`prepare_sherlock_construction(intake, export_permission="allowed")` creates an explicit
prepared-construction/v2 wrapper. V1 remains the generic annotation/text profile. The full
study receipt binds the recording; only its derived request goes to the existing rich graph
constructor. Existing prepared prompt/import/replay/verify commands and analysis-plan/v2
consume this wrapper without a new orchestration engine.

```sh
storymodel prepare-sherlock-source source.tsv repair.json --key source --allow-export --output source-prepared.json
storymodel prepare-sherlock-recall recall.csv lineage.json --key recall --source-id SOURCE_ID --allow-export --output recall-prepared.json
storymodel sherlock-recall-timing recall-prepared.json --clock princeton --output timing.json
```

Both preparation commands accept `--expected-input-sha256` and `--expected-record-sha256` for
independent file pins. Default export permission is denied. A matching lineage does not change it.
From `port/`, run the complete abstract example into a new directory:

```sh
python examples/sherlock_workflow.py data/sherlock-example
storymodel replay-analysis data/sherlock-example/analysis-1.json --output data/sherlock-analysis.json
storymodel verify-analysis data/sherlock-example/analysis-1.json data/sherlock-analysis.json
```

One six-row source feeds two canonically equal, raw-distinct recalls through detail admission,
reference mapping, conditional fidelity and the three analysis views. The example includes a
native clock restart, point annotation, missing TR bounds, first-onset missingness and refused
fidelity evidence. It shares the generic example's existing recording and analysis functions.

Still owed: composed native-axis/evidence consumers, general recall clock/recording/scanner
linkage and downstream measured-time analysis, the remaining study adapters, interrupted-run
recovery and full P2–P6 coverage. Replayed metadata and synthetic engineering checks do not
establish source-file availability, media reachability, provider accuracy or scientific validity.
