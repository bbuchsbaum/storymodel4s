# Local evidence consumer review

The independent navigator reproduced three defects with synthetic tests outside
`align`, then reran the same six-test suite against the repair. The retained
logs and command receipts include both failing runs and the green control.
`receipt.json` binds their source commits, totals, exit status and file hashes.

The first run admitted evidence from a different recall and source because
missing and explicitly empty fields shared legacy identities. Supplemental
identities repaired both admissions. The second run changed the likelihood
from -3.473917465573736 to -2.6285460342350806 under the same published source
fingerprint by changing adjacency only during computation. One frozen source
view across admission, inference and validation repairs that inconsistency.

All six tests pass on the repaired JVM source. These are observed regression
checks, not a completed full-platform release gate or evidence of calibrated
mapping probabilities. Earlier exploratory snapshot probes that only failed
closed are not counted as reproduced unsafe admissions.
