# GitHub source build and offline CLI qualification

Mote: `bd-01M50K8E8KMRGY0MYXJRT0SZ8G`, tag `reality-check-20261015`.
The [exact candidate qualification](qualified-candidate-c69a5d75/receipt.json) passed at
`c69a5d754a21fa455c64901334d9509278401091`: 56 task totals, **11,067 passed, zero failures/errors
and five skips**, followed by passing format checks, actual packaging and extracted-CLI readback.
This is locally observed evidence. The [timezone witness](timezones-c69a5d75/receipt.json) also
passes: the original archive and actual Toronto/Honolulu rebuilds are byte-identical. S1 remains
open for the pinned Atlas consumer gate, independent artifact review and authorized landing.

The fresh checkout fetched all three declared Git pins without source overrides, using configured
SSH transport. Its 143,475,273-byte archive has SHA256
`dc99dca194ea808b60161d11fc4b26845d40e1b1600f9b46c8aa7f37deb7edb9`.
The extracted manifest and dependency provenance were verified, including applicable notices.
Paired export, three weights and both independent readers passed online and under measured
network denial; replay bytes matched, exact resume passed and overwrite refused. The live
connection control exited 0 and its sandboxed permission-refusal control exited 23.

The [actual candidate POM inventory](qualified-candidate-c69a5d75/maven-closure.json) contains
56 POMs and 50 external direct-root checks: 46 returned HTTP 200 and four returned HTTP 404.
The missing `grakern-core_3`, `grakern-engine-jvm_3`, `grakern-graph4s_3` and
`grakern-standard_3` coordinates all use version `0.1-0329c43-SNAPSHOT`. They affect
`storymodel4s-embed-grakern_3` and `storymodel4s-embed-bench_3` in the recorded nonoptional
compile/runtime graph. This is not full public transitive resolution or publication acceptance.

The [reviewed downstream witnesses](qualification-drivers/receipt.json) bind the same candidate.
Their seven admission controls pass; execution receipts remain separate from driver preparation.

- [Fresh baseline](fresh-baseline/summary.json): exact source
  `e5bd6aed352aa7da5aba1f1e37fb1aadb2ee6f7c`, OpenJDK 22, sbt 1.12.14, Scala 3.7.4.
  Fresh boot/global/staging/Ivy/Coursier/config directories fetched all three exact source pins
  with no override. The adapter and pipeline tasks passed **388 tests, zero failures/errors
  and zero skips**; CLI packaging exited 0.
- [Installed baseline control](baseline-runtime/receipt.json): 14 literal invocations checked
  the extracted distribution, paired exchange, three weights, independent readers, exact
  resume and overwrite refusal. A live connection control exited 0; the same operation under
  `sandbox-exec` received permission refusal (exit 23). Online/offline output bytes matched.
  This uses the existing packager and does not establish the new dependency-notice acceptance.
- [Initial disk failure](initial-failed-build/receipt.json): source fetches succeeded; resolution
  exited 1 when the system volume filled. No tests ran. Only that invocation's disposable
  checkout and caches were removed after its logs/identities were retained.
- [Long IPC-path failure](long-ipc-failed-build/crash-summary.json): the external-drive retry
  exited by SIGTRAP in sbt's native IPC bind. The retained diagnostic names a buffer overflow.
  A short private `XDG_RUNTIME_DIR` allowed the otherwise equivalent fresh retry to pass.
- [Metabuild diagnostic](metabuild-diagnostic/receipt.json): uncommitted packaging changes
  compiled, then formatting refused JDK 22 because a formatter dependency has class version 67.
  [JDK 25 formatting and workflow generation](metabuild-diagnostic/format-jdk25-receipt.json)
  exited 0, with no workflow diff. This is a source-hash-bound diagnostic, not a commit gate.
- [Full-test run with failed formatting](format-failed-candidate-65811d01/receipt.json): all
  56 test totals passed, then `scalafmtSbtCheck` failed before packaging. The formatter's exact
  whitespace-only correction and passing format check are retained beside that adverse receipt.
  The corrected candidate above was qualified afresh; this failed run was not relabelled green.

Full logs are retained as deterministic gzip files beside their receipts. Each receipt's log
hash covers the original uncompressed bytes. Build workspaces and the installed baseline remain
under `/Volumes/My Passport for Mac/agent-work/storymodel4s/reality-check-20261015-s1-shortipc/`.
The qualified candidate's workspace and installed artifact are under
`/Volumes/My Passport for Mac/agent-work/storymodel4s/reality-check-20261015-s1-candidate-c69a5d75/`.
Committed receipts, POMs, manifests and compressed logs are the portable evidence; the large ZIP
remains on the build volume. Local qualification does not establish publication, corrected
Native delivery, or scientific efficacy.
