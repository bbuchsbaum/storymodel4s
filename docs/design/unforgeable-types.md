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

## The criterion

> A forgeable type's documented promise survives the forge **only if some field's type is both
> unforgeable and not otherwise obtainable.**

Both halves are required. A field type that is unforgeable but freely obtainable protects nothing,
because a caller simply obtains one. This is the test to apply *before* ranking a type's severity —
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
