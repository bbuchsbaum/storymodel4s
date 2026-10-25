# Proposed title: Avoid writing a computed zero hash back into literal String storage

Status: prepared contribution text; not posted. No fork or runtime adoption is authorized by
this artifact. Mote: `bd-01M4YP83C7925M22V0K5AQ88BX`, tag `reality-check-20261015`.

## Problem and change

Scala Native 0.5.12 crashes when repeatedly hashing the nonempty literal `"\u0000"`.
Literal storage is constant and contains its precomputed hash. Zero is also the uncached
sentinel, so `String.hashCode` recomputes zero and writes it into the literal's read-only
storage. Dynamically constructed equal strings, empty strings and ordinary hashes distinguish
this case from a general hashing or StoryModel identity failure.

The patch skips the redundant assignment when the computed hash is zero. It preserves the hash
algorithm and adds nine upstream JUnit regressions: literal/dynamic NUL and zero-hash ASCII,
two NULs, empty/ordinary/negative hashes, repeated calls and hash-collection lookup by equal
dynamic keys. The retained patch is
[`tools/native-runtime/zero-hash.patch`](../../../../../tools/native-runtime/zero-hash.patch),
prepared as `c3d9136408a18515e51a36561c24feeb595fc44f` over release base
`65dd513df188300d0180514ad828308facc5ca61`.

## Qualification already observed

For each original/fixed comparison, five named controls pass before the original runtime
crashes at the literal NUL. The repaired runtime passes all eight standalone probe cases.
Its complete `StringTest` plus the nine new tests reports **64 passed, zero failed/errors,
one existing ignored `intern` test**. Ignored methods are additional to the upstream JUnit
adapter's executed total.

Linux LLVM 18/JDK 21 debug and release-fast passed in
[the retained hosted run](https://github.com/bbuchsbaum/storymodel4s/actions/runs/38752601266).
macOS arm64 LLVM 20/JDK 21 debug and release-fast have retained local receipts. Initial failures
and their causes remain in [the qualification record](../README.md), alongside independent
review. Run the standalone original/fixed reproducer from StoryModel:

```sh
LLVM_BIN=/absolute/path/to/llvm/bin JAVA_HOME=/absolute/path/to/jdk21 \
  python3 tools/native-runtime/qualify.py --mode debug --output /absolute/new/native-debug
LLVM_BIN=/absolute/path/to/llvm/bin JAVA_HOME=/absolute/path/to/jdk21 \
  python3 tools/native-runtime/qualify.py --mode release-fast --output /absolute/new/native-rf
```

Use output paths without `-release`, `-target` or `-Xtarget`: the qualified upstream revision
filters compiler options containing those substrings and can accidentally remove `-Xplugin`.

## Current upstream and requested route

[Live source/readiness receipt](upstream-readiness.json) records official latest `v0.5.12`
and upstream main `c3918a9fe52a4217842b60935378a4da054a9380`. The retained patch applies cleanly
to that exact main source with `git apply --check --whitespace=error-all`; its new test path
does not already exist there. This is applicability evidence, **not a main-based compilation
or test result**. The runtime qualification above remains bound to release base `65dd513`.

Proposed next external action, after the owner selects/authorizes the contribution route:
submit the two-file patch and original/fixed evidence to Scala Native for review and an accepted
release/backport route. Any requested revision needs new exact-SHA qualification. StoryModel
continues to select official 0.5.12 until a corrected independently fetchable runtime is accepted,
then runs its producer and Atlas consumer gates against that selected artifact. A private
patch, a passing fixture workaround or this prepared text cannot close runtime delivery.
