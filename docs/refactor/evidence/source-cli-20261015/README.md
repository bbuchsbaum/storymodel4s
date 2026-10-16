# GitHub source build and offline CLI qualification

Mote: `bd-01M50K8E8KMRGY0MYXJRT0SZ8G`, tag `reality-check-20261015`.
This is a work-in-progress S1 record. Candidate packaging, exact-commit review and the full
required gate remain open.

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

Full logs are retained as deterministic gzip files beside their receipts. Each receipt's log
hash covers the original uncompressed bytes. Build workspaces and the installed baseline remain
under `/Volumes/My Passport for Mac/agent-work/storymodel4s/reality-check-20261015-s1-shortipc/`;
the committed receipts and logs are the portable evidence. Local qualification does not establish
publication, corrected Native delivery, or scientific efficacy.
