# Pre-migration WOG HSMM baseline — 19 September 2026

This records the existing text reconstruction output for the later D1A S4c preservation
check. Production revision: `75c8df52adb2ffc54a5cd3586528deddabf4a531`. Test-only
capture revision: `f3c6edb41621c43de599ef76f66790ae52b5af1e`, preserved on local branch
`work/film-hsmm-baseline-20260919`. The instrumentation adds one test that prints the
existing `WarOfTheGhostsCodecGolden.encoded`; it changes no production code or expectation.

| Backend | Bytes | SHA-256 |
|---|---:|---|
| JVM | 26,284 | `ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a` |
| JavaScript | 26,284 | `ce61e761a131c1e2ffeebcf912a9c04d9dd2bf2fb4c09f9d4bda0c2741f2cf1a` |
| Native | 26,284 | `ccb0b98f21c7c2e1c9d5d81bad3f0858ed4c2f5d4f4c3ac671a6f45b152af012` |

The canonical [JVM](jvm.json), [JavaScript](js.json), and [Native](native.json) bytes all
use `hsmm/v3`. Each backend ran the capture test plus the existing two-test golden
suite: three passed, zero failed/skipped. The [manifest](manifest.json) binds the exact
commands, revisions, log hashes, clean before/after state, artifact hashes, and named tests.
The full repository gate was not run for this test-only capture.

The [difference record](backend-differences.json) identifies five changed mass leaves
between Native and JVM, each one adjacent IEEE-754 bit pattern, with no structural difference.
This is observed preexisting behavior. No tolerance or numerical policy was changed;
`bd-01M1D215EY4T5BR0VRJ694AMBQ` retains the Native numerical-policy decision.
A later candidate must be compared with its corresponding backend baseline on the recorded
platform/runtime. These Native bytes are not a portable cross-OS expectation.

The [runtime record](runtime.json) distinguishes the actual sbt launcher runtime,
Homebrew Java 25.0.1, from the direct shell `java` command, which selects Java 22.
OS/architecture, Node, and default clang are separately recorded; the default clang probe
does not prove the Native compiler's selected binary. No media, participant recall, gold,
provider calls, or scientific accuracy evaluation were involved.

The [runner](run.py) and [capture source](WogHsmmBaselineCaptureSuite.scala) preserve the
capture procedure. Raw logs and fresh JUnit checks remain under the ignored local data
workspace `data/study/film-foundation-goal-20260919/hsmm-baseline/` and the clone paths
recorded in the manifest. A new capture needs fresh output paths; the runner refuses to
overwrite earlier logs. Baseline identity is fixed by this commit; a later mismatch cannot
be repaired by regenerating these expected values.

This evidence is preparation for S4c. It does not close S2, S4c, or the goal, qualify the
migration, establish empirical measurement validity, or report an executed CI run.
