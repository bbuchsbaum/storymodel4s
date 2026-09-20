package storymodel4s.codec

import io.circe.{Decoder, Encoder, Json}
import munit.FunSuite
import storymodel4s.acquire.ResolutionFailure
import storymodel4s.document.DerivationGapReason

class D1aGapWireSuite extends FunSuite:
  import DerivationCodecs.given

  test("historical direct-support failure and gap names keep literal wire values"):
    val failure = ResolutionFailure.NoSpanEvidence
    val failureJson = Json.fromString("NoSpanEvidence")
    assertEquals(Encoder[ResolutionFailure].apply(failure), failureJson)
    assertEquals(Decoder[ResolutionFailure].decodeJson(failureJson), Right(failure))
    val gap = DerivationGapReason.MissingSpanEvidence
    val gapJson = Json.fromString("MissingSpanEvidence")
    assertEquals(Encoder[DerivationGapReason].apply(gap), gapJson)
    assertEquals(Decoder[DerivationGapReason].decodeJson(gapJson), Right(gap))
    assertEquals(gap.render, "missing-span-evidence")
    val unresolved = Json.obj("type" -> Json.fromString("Unresolved"), "reason" -> failureJson)
    assertEquals(Encoder[DerivationGapReason].apply(DerivationGapReason.Unresolved(failure)), unresolved)
    assertEquals(Decoder[DerivationGapReason].decodeJson(unresolved),
      Right(DerivationGapReason.Unresolved(failure)))
