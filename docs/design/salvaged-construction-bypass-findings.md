# Salvaged construction-bypass findings (2026-09-01)

Two red-team probes were recovered uncommitted from
`/private/tmp/storymodel4s-output-recovery`. Both were written to answer the
question posed by `e0ede3e7 Probe exact package issuer bypasses`, and both
**pass against current `main` plus the salvaged output bundle** — meaning both
doors are open.

They are recorded here rather than landed as suites. A test that asserts the
attacker wins is not a regression guard: it goes red the moment the hole is
closed, so it inverts the signal it appears to give. The probe bodies are
reproduced below so either finding can be re-run on demand.

Neither finding was introduced by the salvage. Both describe the state of the
code as the original authors left it.

---

## F1 — a downstream `acquire` child package can forge an invalid `EstablishedRate`

`storymodel4s.acquire.EstablishedRate.make` is reachable from any package
nested under `storymodel4s.acquire`, and it does not reject a zero denominator
or a negative numerator. The resulting value is `-Infinity`.

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

Observed: **passes** — the forge succeeds.

Note the probe lives *inside* `storymodel4s.acquire.*`. Per AGENTS.md, a probe
inside the defining package cannot distinguish a closed door from an open one
on `private[acquire]` access. Here that is precisely the point: the scope is
package-wide, so every child package inherits construction rights it should not
have. Closing this means narrowing the scope to the smart constructor and
validating the denominator, then re-proving from *outside* `storymodel4s.acquire`.

## F2 — public receipt-shaped data mints a satisfied local-open proof

`VerifiedProfileReceipt.localOpen` verifies the *shape* of the court plan — that
the receipt names exactly the two required courts and carries `Passed` for each —
but nothing binds those outcomes to a court that actually ran. Every field it
reads is publicly constructible, so a caller can assemble a `ProfileReceipt`
out of invented ids and get a `Right` back.

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

Observed: **passes** — the fabricated proof is accepted.

This is the `CachedParserProposal` shape recorded in AGENTS.md: the compiled
path is safe because the verifier recomputes what it needs, but the *public
preflight contract* is not, because a consumer can mint and then serialize or
display a claim the verifier would never have issued. Closing it means the
court outcome carries evidence only the court can produce.

---

## Reproducing

The probes were never committed to any branch, so the bodies above are their
only record. Copy them into:

- `acquire/src/test/scala/storymodel4s/acquire/attack/EstablishedRatePackageBypassSuite.scala`
- `view/src/test/scala/storymodel4s/consumerattack/FabricatedProfileReceiptSuite.scala`

then:

```
sbt "acquireJVM/testOnly storymodel4s.acquire.attack.EstablishedRatePackageBypassSuite" \
    "viewJVM/testOnly storymodel4s.consumerattack.FabricatedProfileReceiptSuite"
```

Both reported `1 total, 0 failed` on 2026-09-01 against
`a1d9bab5`.
