# Scala Native zero-hash runtime repair

The retained patch targets Scala Native 0.5.12 at
`65dd513df188300d0180514ad828308facc5ca61`. It skips the redundant assignment of a
computed zero to `String.cachedHashCode`. The compiler emits string literals as
constants with a precomputed hash. Zero is also the runtime's uncached sentinel,
so the original implementation attempts to write to a constant for nonempty
zero-hash literals. The hash algorithm itself is unchanged.

`ZeroHashProbe.scala` exercises repeated hashing and Java hash collection access
for eight named cases. Five controls run before the original runtime crashes on
the literal NUL. A non-NUL ASCII string with a zero hash is also covered by the
fixed run. The patch adds nine JUnit regressions to the upstream suite.

Run the complete original/fixed comparison in a new standalone checkout:

```sh
LLVM_BIN=/path/to/llvm/bin python3 tools/native-runtime/qualify.py \
  --output /absolute/path/to/new-evidence --mode debug
LLVM_BIN=/path/to/llvm/bin python3 tools/native-runtime/qualify.py \
  --output /absolute/path/to/another-new-evidence --mode release-fast
```

An optional `--source /path/to/scala-native` uses an existing Git clone containing
the exact upstream commit. Both runs compile Scala 3.7.4. The repaired run also
executes the upstream `StringTest` suite. Raw logs, exit codes, source hashes and
passed/failed/ignored totals are retained in the output directory. The hosted
workflow repeats the comparison on Linux with LLVM 18.

This is a locally prepared upstream patch. It does not alter StoryModel's Native
dependency, publish a fork, or establish that an official corrected runtime is
available. Unrestricted Native publication still requires that delivery decision
and qualification against the selected released runtime.
