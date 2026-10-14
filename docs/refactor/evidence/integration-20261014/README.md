# Reviewed-branch integration

Candidate `39fe44354971e03b8c2b006406acfe463fb3010c` merges organization main
`f1676de1ff6e9faaa870bdc18a41a8ce8b1949bb` and Passport recall export
`eca26785f210506016d928f242cd9d03a067da84`. The only conflict was the older
organization diagnosis plan. The main version was retained byte-for-byte;
its SHA-256 `d18a2331c666b0d5487bcc9b4e28e9c0ea9915bd0120b84127776e835232e526`
continues to match the diagnosis artifact receipt. Independent source review
confirmed the rest of the tree is the exact union of both reviewed branches.

The union reference gate passed in a clean standalone clone with fatal warnings:
JVM 3,479 passed / 0 failed / 4 optional live witnesses skipped; JavaScript and
Native each 2,594 passed / 0 failed / 0 skipped. Python passed 213 organization
checks and 11 recall corruption checks. Formatting ran last. `checks.json`,
`gate-totals.json`, and compressed logs bind the commands and results. The initial
incorrect Python recall test invocation is retained separately and explained.

Actual recall export reproduced all 30 output files byte-for-byte, including
manifest SHA-256 `1d9027e543c32dab89cd2bb8888cfa727c50261dd0c1ff65876f55867dc763f4`.
Independent readback retained all 2,495 records, including six outside raw coverage,
94 words without original-unit membership, and one partially attached unit.
The attachments remain non-additive references to original semantic units.
Participant payloads remain in ignored local storage.

The synthetic replay reproduced all 84 development and 140 previously opened
heldout payloads byte-for-byte. This is an integration replay, not a new holdout
or refit. Only producer provenance changed in the replay fit; every selected
temperature and objective is the frozen original. The direct test witness was
used with explicitly rebound execution metadata. The prospective checked launch
and its leakage guard were not changed. New manifests pin the unchanged payloads;
the original archive on main supplies their public bytes. `replay-plan.json` and
`parities.json` record that distinction.

These are local results. No hosted CI or publication is claimed. This integration
does not establish physical encoding synchronization or general model efficacy.
