package storymodel4s.align

import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import java.nio.file.Files
import storymodel4s.core.Checksum

/** Original synthetic protocol tokens; no narrative, media or participant material. */
class AutomaticClaimAssessmentProbeSuite extends FunSuite:
  private val request = Json.obj(
    "question" -> "q".asJson,
    "arm" -> "audio".asJson,
    "identity" -> "request-hash".asJson,
    "items" -> Json.arr(Json.obj("id" -> "e".asJson, "text" -> "x=1".asJson))
  )
  private val link = Json.obj(
    "evidence" -> "e".asJson,
    "quote" -> "x=1".asJson,
    "start" -> 0.asJson,
    "end" -> 3.asJson
  )
  private val rawLink = Json.obj("evidence_id" -> "e".asJson, "quote" -> "x=1".asJson)
  private val proposal = Json.obj(
    "question" -> "q".asJson,
    "support" -> Json.arr(link),
    "counter" -> Json.arr(),
    "unresolved" -> Json.arr()
  )
  private val raw = Json.obj(
    "claim_id" -> Checksum.ofText("q").hex.asJson,
    "support" -> Json.arr(rawLink),
    "counter" -> Json.arr(),
    "unresolved" -> Json.arr()
  )
  private val output = Json.obj(
    "question" -> "q".asJson,
    "arm" -> "audio".asJson,
    "requestIdentity" -> "request-hash".asJson,
    "processing" -> "valid".asJson,
    "proposal" -> proposal,
    "raw" -> raw.noSpaces.asJson
  )

  test("consumed input must have the frozen path and content") {
    val owned = Files.createTempFile("claim-owned-", ".json")
    val copied = Files.createTempFile("claim-copy-", ".json")
    try
      val _ = Files.writeString(owned, "{}")
      val _ = Files.writeString(copied, "{}")
      val freeze = Json.obj(
        "inputs" -> Json.obj(
          owned.toAbsolutePath
            .normalize()
            .toString -> Checksum.ofBytes(Files.readAllBytes(owned)).hex.asJson
        )
      )
      assertEquals(AutomaticClaimAssessmentProbe.checkedInput(owned, freeze), Json.obj())
      intercept[IllegalArgumentException] {
        AutomaticClaimAssessmentProbe.checkedInput(copied, freeze)
      }
      val _ = Files.writeString(owned, "[]")
      intercept[IllegalArgumentException] {
        AutomaticClaimAssessmentProbe.checkedInput(owned, freeze)
      }
    finally
      Files.delete(owned)
      Files.delete(copied)
  }

  test("matching raw proposal and owned quote are accepted") {
    assertEquals(AutomaticClaimAssessmentProbe.checkedProposal(request, output), Some(proposal))
  }

  test("quotation content and UTF-16 extent must agree") {
    AutomaticClaimAssessmentProbe.checkedQuote("🔔 x=1", "x=1", 3, 6)
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedQuote("x=1", "x=0", 0, 3)
    }
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedQuote("🔔 x=1", "x=1", 2, 5)
    }
  }

  test("raw proposal must echo the opaque model claim identity") {
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedProposal(
        request,
        output.mapObject(_.add("raw", raw.mapObject(_.add("claim_id", "q".asJson)).noSpaces.asJson))
      )
    }
  }

  test("foreign request identity or arm is refused") {
    Vector("requestIdentity" -> "other", "arm" -> "visual").foreach { (k, v) =>
      intercept[IllegalArgumentException] {
        AutomaticClaimAssessmentProbe.checkedProposal(request, output.mapObject(_.add(k, v.asJson)))
      }
    }
  }

  test("a normalized proposal cannot change the raw response") {
    val altered =
      proposal.mapObject(_.add("support", Json.arr(link.mapObject(_.add("quote", "x=0".asJson)))))
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedProposal(
        request,
        output.mapObject(_.add("proposal", altered))
      )
    }
  }

  test("a source quote cannot be borrowed from a different arm") {
    val foreign = request.mapObject(_.add("items", Json.arr()))
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedProposal(foreign, output)
    }
  }

  test("failed processing differs from a valid unresolved assessment") {
    val failed =
      output.mapObject(_.add("processing", "provider_failure".asJson).add("proposal", Json.Null))
    assertEquals(AutomaticClaimAssessmentProbe.checkedProposal(request, failed), None)
    val unresolved = Json.arr("comparison_unavailable".asJson)
    val p = proposal.mapObject(_.add("support", Json.arr()).add("unresolved", unresolved))
    val r = raw.mapObject(_.add("support", Json.arr()).add("unresolved", unresolved))
    val valid = output.mapObject(_.add("proposal", p).add("raw", r.noSpaces.asJson))
    assertEquals(AutomaticClaimAssessmentProbe.checkedProposal(request, valid), Some(p))
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedProposal(
        request,
        output.mapObject(_.add("processing", "provider_failure".asJson))
      )
    }
  }

  test("ambiguous repeated quotations are refused") {
    intercept[IllegalArgumentException] {
      AutomaticClaimAssessmentProbe.checkedQuote("x x", "x", 0, 1)
    }
  }
