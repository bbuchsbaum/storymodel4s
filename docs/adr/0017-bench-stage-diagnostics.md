# ADR 0016: opt-in bench stage diagnostics

Accepted 2026-09-05 in single-developer mode, for the owner's five-step Film Festival evaluation.

`STORYMODEL4S_STAGE_TRACE=on` writes `storymodel4s.bench.stage-trace/v1` beside a recall report.
It records nomination ranks within level, actual admissible local costs, independent normalized
emissions on those same states, HSMM posterior/Viterbi, decode without fill and final anchor.
It changes neither inference nor defaults. The receipt is bench-only; no module or dependency is
added. It contains no source/participant prose and uses full-precision finite JSON numbers.

Independent emissions mean a uniform state prior and no transition/refinement contribution; they
are not calibrated confidence. External states remain in the normalization. Refined runs are
refused because `HsmmResult.costs` retains base costs, not the refined emissions. Candidate coverage
is a ceiling only for selectors restricted to nominations: scene fill can search outside them.

Rejected: reconstructing nomination ranks from posterior order, treating an absent old trace as
an empty candidate set, mutating the existing posterior schema or changing Sherlock defaults.
Historical outputs remain immutable. Trace parity is checked against the uninstrumented baseline.
