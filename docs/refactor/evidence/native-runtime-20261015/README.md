# Native zero-hash repair qualification

Ticket: `bd-01M4YP83C7925M22V0K5AQ88BX`.
Upstream base: Scala Native 0.5.12,
`65dd513df188300d0180514ad828308facc5ca61`.
Patch: `c3d9136408a18515e51a36561c24feeb595fc44f`, retained as
[`zero-hash.patch`](../../../../tools/native-runtime/zero-hash.patch).
Independently reviewed qualification code: StoryModel
`3506dc6281de009f2e7941f7bbac29255a2344b5`.

## Cause and repair

The compiler emits literal strings in constant storage and precomputes their hash.
Zero also denotes the runtime's uncached hash. Hashing a nonempty zero-hash literal
therefore attempted to write zero back into read-only storage. The patch skips
that redundant assignment when the computed hash is zero; the hash algorithm is
unchanged. It adds nine upstream JUnit regressions.

The original/fixed court exercises empty, dynamically constructed NUL, dynamic
zero-hash ASCII, ordinary and negative-hash controls before reaching literal NUL.
The original must pass all five controls and crash on the named literal case.
The repaired probe must pass all eight cases, including two-NUL and non-NUL ASCII
zero-hash literals, repeated hashing and hash collection lookup.

The repaired runtime also runs the complete upstream `StringTest` alongside the
nine new tests. Combined totals are 64 passed, zero failed/errors and one existing
ignored `intern` test. The upstream JUnit adapter's reported `total` excludes
ignored methods; the receipt retains executed and inventory counts separately.

## Evidence and adverse attempts

The initial macOS arm64 debug controls passed under Apple Clang 15, with the
original literal NUL crashing. The LLVM 20 debug probe likewise distinguished
original and fixed runtime behavior. Its first expanded-suite invocation failed
because the selected source list omitted required helpers. The corrected selection
passed all 64 String tests, with one ignored. These are separate invocations,
retained in `logs.json`; the failed qualifier receipt remains failed.

Linux debug and release-fast run
[38752601266](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38752601266)
uses LLVM 18 and JDK 21 at the reviewed qualification SHA. Both matrix cells passed.
The [debug receipt](linux-native-debug-final-receipt.json) and
[release-fast receipt](linux-native-release-fast-receipt.json) each record the
original signal-11 crash after five controls, eight repaired probe passes and the
64/0/1 suite result. [Hosted status](hosted.json) binds both successful jobs to
the exact source commit.

The previous Linux run executed those tests successfully but failed to recognize
ANSI-colored totals. The repair strips terminal styling for parsing while leaving
the raw logs unchanged. Two parser controls pass; deleting the strip kills the
named colored-total assertion while its plain-log sibling passes. The prior
failed receipt is retained, rather than relabeled as a successful run.

An initial macOS release-fast attempt used a relative local source path and
stopped before compilation. The absolute-path retry exposed an upstream build
filter that removes every compiler option containing `-release`; that substring
in the checkout directory removed the `-Xplugin` option. Packaging the plugin
first did not repair it. [Independent diagnosis](macos-build-path.json) binds the
source predicate and evidence. The unchanged runtime and qualifier passed from
a neutral directory with LLVM 20 and JDK 21. The
[macOS release-fast receipt](macos-release-fast-receipt.json) records the original
signal-10 crash after five controls, eight repaired probes and 64 passed/zero
failed/one ignored String tests.

[Log inventory](logs.json) gives raw hashes for compressed logs.
[Independent review](review.json) approves the patch and qualification code.
These records do not establish a released corrected runtime: StoryModel still
selects 0.5.12. The latest official release checked on 15 October was also 0.5.12.
Deliver the corrected runtime through an accepted release route, then qualify
StoryModel against that exact artifact before closing the Native prerequisite.
