# Dependency qualification scope

The exchange, offline-source, lambda and workspace full source gates explicitly
selected a local grakern checkout. For the final workspace gate that checkout was
clean at `8efc5efade4e56c6e0b29745bd1b108d4c9a9d57`. These are results with that
override, not evidence that the repository's declared engine revision was tested.

The declared revision is `0329c43c88a0b71e9aa4456723bb16bac2fa3841`. Independent
reviewer `reconstruction_review` checked the affected dependency seam and specified
a supplement: clean and test `embedGrakern` and downstream `embedBench` on exact
source `34c0bc9a367c7c4e3cdad6743feb33cc4371f5ca`, using a clean checkout at the
declared engine revision. Preserve its graph4s and gale pins. The executable
`workspace-dependency-gate.py` implements this scope and records completion only
after both test cells pass.

The adapter consumes the engine's WL compilation, cross-kernel, query matrices
and shared features. Its distance and integration suites exercise this seam.
Pipeline, portable modules and Atlas's view/codec/fixtures chain do not depend on
grakern; repeating their portable gates solely for this correction adds no seam
coverage. The separate exact consumer gate remains required.

Generated `GrakernPin.revision` always contains the declared revision, even when
an override is supplied. It cannot identify the engine actually tested. The
external checkout and command receipts bind that fact. This qualification does
not change or upgrade the declared dependency.
