# Salvaged construction-bypass findings (2026-09-01)

Two red-team probes were recovered uncommitted from
`/private/tmp/storymodel4s-output-recovery`. Both were written to answer the
question posed by `e0ede3e7 Probe exact package issuer bypasses`.

**Both bypasses are closed.** They were closed by the nine commits that continue
that lineage — `329ae16b Refuse fabricated output satisfaction` through
`84b6ce8d fix(codec): preserve contextual refusal fixed point` — which are the
approved candidate `cand-6D9Z91FK56DE5Q88QGH3XGKXF5` and are now on `main`.

This file records the probes and their disposition. It is history, not a
standing defect list.

> An earlier revision of this document reported both bypasses as open. That was
> measured against `e0ede3e7`, which was the wrong tip: the salvage had merged
> the 08-31 19:20 state rather than the approved 09-01 08:14 one. The probes were
> re-run against the correct tip and the results below are that re-run.

---

## F1 — a downstream `acquire` child package could forge an invalid `EstablishedRate` — CLOSED

The probe called `storymodel4s.acquire.EstablishedRate.make(-1, 0)` from a
nested package and asserted the forged value came back with a zero denominator
and an infinite `value`.

```scala
package storymodel4s.acquire.attack

import munit.FunSuite

class EstablishedRatePackageBypassSuite extends FunSuite:
  test("a downstream acquire child package can forge an invalid established rate") {
    val forged = storymodel4s.acquire.EstablishedRate.make(-1, 0)

    assertEquals(forged.numerator, -1)
    assertEquals(forged.denominator, 0)
    assert(forged.value.isInfinite)
  }
```

Against `main` this no longer compiles:

```
[error] value make is not a member of object storymodel4s.acquire.EstablishedRate
```

`f28e0101 Prove established rate construction boundary` removed the door rather
than validating behind it. A compile error is the strongest available refutation
here — per AGENTS.md a probe inside `storymodel4s.acquire.*` cannot distinguish a
closed door from an open one by *runtime* behaviour, because package-private
access would succeed either way. There is no door left to test.

## F2 — public receipt-shaped data could mint a satisfied local-open proof — CLOSED

The probe assembled a `ProfileReceipt` entirely from public constructors, naming
exactly the two required courts with `Passed` outcomes, and asserted
`VerifiedProfileReceipt.localOpen` returned a `Right`.

```scala
package storymodel4s.consumerattack

import munit.FunSuite
import storymodel4s.acquire.*
import storymodel4s.core.Checksum
import storymodel4s.view.*

class FabricatedProfileReceiptSuite extends FunSuite:
  test("public receipt-shaped data can mint a satisfied local-open proof without running a court") {
    val preview = ProfileArtifactBinding(
      ArtifactRole.BrowserPreview,
      BundlePath.unsafe("preview.html"),
      MediaTypeId.unsafe("text/html"),
      Checksum.ofText("invented-preview")
    )
    val claim = ProfileReceipt(
      OutputReceiptId.unsafe("invented-profile-receipt"),
      ProfileSchemaId.unsafe("local-open/v1"),
      ProfileVerifierId.unsafe("invented-verifier"),
      OutputReceiptId.unsafe("invented-execution"),
      OutputSchemaId.unsafe("invented-policy"),
      ProfileReceiptDecision.Satisfied,
      Vector.empty,
      Some(preview),
      Vector.empty,
      VerifiedProfileReceipt.LocalOpenRequiredCourts.map(id =>
        ProfileCourtOutcome(id, ProfileCourtDisposition.Passed)
      )
    )

    assert(VerifiedProfileReceipt.localOpen(claim).isRight)
  }
```

Against `main` it still compiles — the receipt type is still publicly
constructible, which is correct, since receipts have to cross the wire — but the
assertion fails:

```
==> X FabricatedProfileReceiptSuite.public receipt-shaped data can mint a
      satisfied local-open proof without running a court
    munit.FailException: assertion failed
1 failed, 0 ignored, 1 total
```

`329ae16b Refuse fabricated output satisfaction` bound the court outcome to
evidence the court alone can produce, so shape-conformance is no longer
sufficient. This is the `CachedParserProposal` shape recorded in AGENTS.md,
resolved the way that entry prescribes: the compiled path was never at risk; the
public preflight contract was, and now is not.

## Reproducing

The probes were never committed to any branch, so the bodies above are their
only record. Copy them into
`acquire/src/test/scala/storymodel4s/acquire/attack/` and
`view/src/test/scala/storymodel4s/consumerattack/`, then:

```
sbt "acquireJVM/Test/compile"    # expected: fails, `make` is not a member
sbt "viewJVM/testOnly storymodel4s.consumerattack.FabricatedProfileReceiptSuite"
                                 # expected: 1 failed, 0 passed
```

Both results above were observed on 2026-09-01 against `42fb3574`.

Note the inversion: for these two probes, **failing is the passing condition.**
That is why they are recorded here rather than landed as suites — as written
they assert the attacker wins, so a green run would now be the alarm.
