# Making a type carry a guarantee

A type whose name or Scaladoc asserts something — `Checked…`, `Authorized…`, `Validated…`,
`…Receipt`, `…Proof` — is making a promise to whoever holds one. This note says how to make that
promise true, because in this codebase most of them currently are not.

## The one door that matters

Scala 3 gives **every case-class companion** a public `fromProduct` through `Mirror.Product`.
Marking the constructor `private` does not remove it. So:

```scala
final case class Credence private (rawScore: Double, calibrated: Option[Probability],
                                   calibrationModel: Option[String])
```

is forgeable in one line, and its documented invariant — *a calibrated value is present only if the
calibration model that produced it is named*, which is design-contract rule 3 — is false.

Two further doors, both easy to get wrong:

- **`copy` survives a *qualified* private constructor.** Bare `private` suppresses it; `private[x]`
  gives `copy` the same `private[x]` access, so it exists and is callable from anywhere inside `x`.
  `copy` is the more dangerous door because it is idiomatic: `valid.copy(field = bad)` turns a
  validated instance into an invalid one, and in-package code does that without thinking.
- **The `Product` surface is a *read* door for a type that hides a field.** `CheckedSidecarPrelude`
  declares `private[codec] val blockDigests`; `javap` shows a public `_2()` returning it.

### What does *not* close the door

Measured with a minimal same-package probe (four shapes, all plain case classes):

| shape | `Mirror.ProductOf` |
|---|---|
| bare `private` constructor | **resolves** |
| `private[x]` constructor | **resolves** |
| bare `private` + a user-written companion | **resolves** |
| `private[x]` + a user-written companion | **resolves** |

So **writing your own companion with a smart constructor does not suppress the Mirror** — which is
exactly the thing an author is most likely to believe closes the door, because it is the thing they
just did. The same probe confirms the asymmetry: bare `private` suppresses the *generated* `apply`
(`BarePrivate.apply(1)` does not compile) while leaving `fromProduct` intact.

**Verify your probe can fail.** A negative compile-time assertion — `assert(!typeChecks("summon[…
Mirror.ProductOf[T]]"))` — returns `false` on *any* error and does not say which, so it can pass
for a reason unrelated to Mirrors and keep passing through a mutation that should break it. This
has already happened here on two carriers. Put a positive control in the same file and scope, and
use `typeCheckErrors` to read the real message when diagnosing.

## A probe that cannot fail, and why it looks fine

Measured 2026-09-17, and it invalidates mutation evidence rather than a design.

`typeCheckErrors` expands to a **literal list** at compile time, so Zinc records **no dependency**
from the probe file on the types it names in those strings. The probe suite is therefore *not
recompiled* when a probed type's shape or visibility changes, and the old result persists.

The consequence is that a mutation reads as SURVIVED when it is in fact killed. Both of these
mutations left a probe suite green under `sbt corpusJVM/test` and red only under
`sbt "corpusJVM/clean" "corpusJVM/test"`:

- `final class X private[p]` → `final case class X private[p]`
- the constructor widened to fully public

**A `zincAnchor` helps, and only for one of the two mutation kinds.** Add one never-called private
method taking the probed types as real parameters and touching a member of each. Zinc then records
a dependency and the suite is recompiled. Measured on `CorpusCarrierProbeSuite`, both runs without
`clean`:

| mutation | anchor present | outcome |
|---|---|---|
| `final class Known` → `final case class Known` (shape) | yes | **dies** (1 failed) |
| `final class Known` → `final case class Known` (shape) | no | survives |
| `private[corpus] def of` → `def of` (accessibility) | yes | **survives** |

The asymmetry is structural, not an oversight in the anchor. Zinc invalidates a dependent that
references the **changed member**. An anchor can name the type and its accessible members, but it
**cannot name the member whose inaccessibility the probe asserts** — naming it is precisely what the
probe says must not compile. So for any probe of the form "a consumer cannot call `X.y`", no anchor
can create the dependency, and the mutation is invisible without a clean.

**Therefore: a mutation run against a probe suite must still `clean` the test scope.** The anchor
narrows the hole; it does not close it. Any probe suite in this repository written before 2026-09-17
has the wider hole, and a green mutation score over one of them is not evidence until the run is
cleaned.

## The criterion

> A forgeable type's documented promise survives the forge **only if some field's type is both
> unforgeable and not otherwise obtainable.**

Both halves are required. A field type that is unforgeable but freely obtainable protects nothing,
because a caller simply obtains one.

**Or the representation cannot express the violation.** A second survival mode, and the one most
likely to produce a wasted slice. `MentionGraph.of` rejects duplicate `SurfaceUnitId` pairs before
materializing its `Map`; `fromProduct` accepts the *already-materialized* `Map`, and **no `Map`
value can encode duplicate keys**. The invariant is enforced by the data structure, so there is no
invalid state to forge. Check this before writing a probe: if you cannot construct the invalid
value by hand, the type is not a site, and manufacturing a forge the representation cannot express
is the over-correction, not diligence. This is the test to apply *before* ranking a type's severity —
not "does it validate itself", which is blind to types validated by an external gate, and which is
how the most security-relevant type in the sweep was initially ranked below four arithmetic ones.

## The pattern that works — copy this

`AuthorizedRemoteRequest` is a forgeable case class, yet its promise — *"the constructor is private
so no code path can hand raw text to a remote provider"* — **holds**. Not because of its own
constructor, but because of its payload's type:

```scala
final class PseudonymizedText private[embed] (…)   // plain class: no Mirror, no fromProduct
```

`PseudonymizedText` is **unforgeable** (not a case class, so no `Mirror`) and **unobtainable**
(only the certifying pseudonymization path produces one). Both halves. So even a forged request
must carry text that was genuinely pseudonymized.

The lesson generalises: **put the guarantee in the type of the sensitive field, not in the
visibility of the outer constructor.** An outer constructor is one door among several; a field
whose type cannot be manufactured is a wall.

*(The policy envelope around that same type does still fail — a forged request bypasses provider
allowlist, expiry, budget, model identity and digest binding. A type can keep one promise and break
another; check each promise separately.)*

## What to do at a site

1. `final` **non-case** class, construction private to the smart constructor.
2. Explicit accessors for the fields that are meant to be public.
3. Structural `equals`/`hashCode`; an intentional, non-payload `toString`.
4. Prove it **from outside the defining package** — a probe inside the package cannot fail on
   private-scoped access, so it cannot tell a closed door from an open one.
5. **Demonstrate the forge before fixing it.** One line that builds an invalid instance, and a
   mutation showing the closed version rejects it. A fix without a demonstrated hole is a fix
   nobody can evaluate.
