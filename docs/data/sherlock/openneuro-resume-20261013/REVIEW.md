# Independent evidence review

Reviewer: `/root/package_contract_review` (did not author the slice).
Verdict: **PASS**, no BLOCK or FOLLOW-UP.
Exact candidate: `8e5d53b97ecfe73d9a0c89245004027e2ddacb72`.
Exact parent: `baec59758d1bedc5491c44520522c267808e2cb9`.

Scope: the five input-verification/recall-coverage evidence files in this
directory at that candidate. This receipt records the returned review; it does
not expand approval to physical encoding synchronization or the parent beads.

The independent Fraction oracle used `floor(onset / 1.5)`, rather than the
reader's released-TR-number assignment, and recovered 2,495 requested records,
2,489 assigned records, six outside coverage and 624 occupied bins. The complete
six-row missing-acquisition ledger agreed. Prior file pins, three header/event
records, two media pins and crop arithmetic were cross-checked; the reviewer
did not repeat the author's full image/media hashing.

Original controls: five boundary cases passed and five contradictions refused.
Two independently compiled mutations were discriminated:

| Mutation in `assign` | Failing witnesses | Passing siblings |
| --- | --- | --- |
| `< count` becomes `<= count` | `exactEndMissing` | first, next, last and later-missing bins (4) |
| Return `index + 1` for acquired rows | first, next and last bins (3) | exact-end and later-missing bins (2) |

Reproduction uses the exact candidate blob:

```sh
git show 8e5d53b97ecfe73d9a0c89245004027e2ddacb72:docs/data/sherlock/openneuro-resume-20261013/recall_coverage.py > /tmp/passport-review-coverage.py
```

Load each original/modified source with `runpy.run_path`. The named witness
`exactEndMissing` evaluates
`assign(Fraction(1074), Fraction(717), Fraction(1074), 716) is None`.
It passes on the original and fails on the inclusive-bound mutant, which
returns nonexistent index 716. Siblings use `(onset, TR, TR-onset, count)` values
`(0,1,0,716)`, `(3/2,2,3/2,716)`, `(2147/2,716,2145/2,716)` and
`(10759/10,718,2151/2,716)` with expected results `0,1,715,None`.

Both Python sources compiled. JSON parsing, final newlines and trailing-space
checks passed. `git diff --check` over the exact parent/candidate passed.
The evidence-only slice required no Scala build. The author executions of
`verify.py` and `recall_coverage.py` both exited zero; their outputs are retained
beside the scripts. Public-paper and pinned-README claims were independently
checked. No participant prose, voxel decoding or held-out analysis was involved.
