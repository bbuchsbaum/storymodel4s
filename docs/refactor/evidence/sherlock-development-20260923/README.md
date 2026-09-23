# Sherlock development reproduction — 23 September 2026

Mote: `bd-01M35JFJCQ2ANTVED792H705X6`.

The [protocol](protocol.json) and study-log registration precede the first gold
read. The frozen mapper settings are reused for all 11 admitted development
participants. Historical captions have matching request, outcome and frame-byte
provenance, but predate the receipt-checked timed-source loader. AV uses those
captions as embedded scene text; it is not a newly qualified video compiler.

`run_study.py` checks all input pins, requires committed tracked files, refuses
existing outputs, clears ambient mapper settings and records every process
attempt. Raw reports and logs remain beneath the ignored data root. The
observation manifest is reused by its admitted byte digest; no other raw recall
or saved prediction is opened. No gold has been read at this initial checkpoint.

Execution order:

1. Commit the protocol, runner and study-log registration.
2. Run `run_study.py freeze --data-root DATA_ROOT --out NEW_STUDY_DIRECTORY`.
3. Commit `support-registration.json` with the protocol, gold-file and canonical
   support hashes before importing predictions.
4. Compile the pinned producer and retain its runtime classpath/build receipt.
5. Run `run_study.py map --data-root DATA_ROOT --out STUDY_DIRECTORY --classpath
   CLASSPATH_FILE`; A and AV receive separate Java processes for every participant.
6. Run `run_study.py score --data-root DATA_ROOT --out STUDY_DIRECTORY` and an
   independent integer-count/Decimal-interval readout check.
7. Retain content-free results, exact source/config/input/output digests, all
   participant outcomes and the historical-parity assessment here; request a
   separate review of the scientific wording before closing the issue.

The repaired scene scorer is `tools/recall-study/gold_scene.py`;
`tools/recall-study/score.py` serves different gold-free diagnostics. No scorer or
production mapper algorithm is changed by this reproduction.
