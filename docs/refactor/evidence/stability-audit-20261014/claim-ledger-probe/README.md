# ClaimLedger bounded external probe

Run `python3 run.py`. Commands, verified distribution/compiler provenance and log hashes are in `receipt.json`; exact argv are also in `commands.json`. Compiler/runtime are capped at 2 GiB and two processors. The qualified installed distribution is required at the path in run.py.

Result: compile 0, run 0. External Mirror and fromProduct manufacture map/order contradictions. The hidden-map witness is consequential: `lookup` returns an entry, while `size` and `all` report none; `JsonLines.claims` emits zero bytes and readback loses the entry. Duplicate order fails reader roundtrip; wrong map key changes key after reader normalization. Direct new/apply/copy refuse. Ordinary empty/add/duplicate-refusal/readback controls pass.

BLOCK for a universal stable checked-ledger integrity claim; FOLLOW-UP for the current qualified study route. Existing historical open-Mirror test already records debt on bd-01M183VBPNEAPT5JNQMBMYMKQ9. The normal model and JSONL reader use empty.addAll; no occurrence in study data has been established.

See assessment.json for exact SHA, byte-equivalent current source, family judgments, caller anchors and minimal repair. The initial probe quoting error is retained; it is not counted as a finding or successful test.
