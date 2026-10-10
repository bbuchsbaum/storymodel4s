package storymodel4s.pipeline

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.TextSpan
import storymodel4s.embed.*

class RecallDisclosureSuite extends FunSuite:
  private val policy = PrivacyPolicyId.unsafe("recall-disclosure-policy")
  private val key = KeyId.unsafe("recall-disclosure-key")
  private val keys = SensitiveKeyProvider.static(key, Array[Byte](3, 5, 7, 11))

  private def certifiedPayload: PseudonymizedText =
    val detector = PseudonymizationDetector
      .wholeWordTable(Vector(PseudonymizationTableEntry("Jane", "[P1]")))
      .toOption
      .get
    val source = "Jane arrived."
    val text = "[P1] arrived."
    val spans = Vector(TextSpan.unsafe(0, 4) -> TextSpan.unsafe(0, 4))
    val detection = detector.detect(source, key, keys).toOption.get
    PseudonymizedText
      .checked(policy, key, source, text, spans, detection, detector, keys)
      .toOption
      .get

  test("certified input derives its exact text from the actual pseudonymization witness") {
    val payload = certifiedPayload
    val input = RecallDisclosure.certified(payload)

    assertEquals(input.text, payload.text)
    assertEquals(input.scope, RecallDisclosure.Scope.LocalWorkspace)
    assert(RecallDisclosure.canPublishLocalWorkspace(input, payload.text))
    assert(!RecallDisclosure.canPublishLocalWorkspace(input, payload.text + " changed"))
    assertEquals(
      input.basis.asInstanceOf[RecallDisclosure.Basis.CertifiedPseudonymized].payload,
      payload
    )
  }

  test("ordinary local text cannot substitute for a certified disclosure witness") {
    val local = RecallDisclosure.local("[P1] arrived.")

    assertEquals(local.basis, RecallDisclosure.Basis.LocalOnly)
    assert(!RecallDisclosure.canPublishLocalWorkspace(local, local.text))
    assert(!local.basis.isInstanceOf[RecallDisclosure.Basis.CertifiedPseudonymized])
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.RecallDisclosure
RecallDisclosure.certified("[P1] arrived.")"""
      ).nonEmpty
    )
    assert(RecallDisclosure.receiptDescription(local).contains("local-only"))
  }

  test("the closed project-synthetic public control admits its exact bytes only") {
    val admitted = RecallDisclosure.projectSynthetic("Anna arrived.").toOption.get

    assert(RecallDisclosure.canPublishLocalWorkspace(admitted, "Anna arrived."))
    assertEquals(
      admitted.basis.asInstanceOf[RecallDisclosure.Basis.PublicCorpusAdmitted].record.sourceId,
      "project-synthetic-anna-arrived"
    )
    assertEquals(
      RecallDisclosure.projectSynthetic("Anna arrived!"),
      Left(RecallDisclosure.Refusal.SyntheticTextMismatch)
    )
  }

  test("a foreign Sherlock lineage digest refuses before any supplied data can mint authority") {
    val result = RecallDisclosure.sherlock(
      "recall-source-01",
      "word,princeton-word-onset-seconds\nAnna,0\n".getBytes("UTF-8"),
      "{}".getBytes("UTF-8")
    )

    assertEquals(result, Left(RecallDisclosure.Refusal.UntrustedSherlockLineage))
  }

  test("receipt descriptions retain identities without retaining disclosed text") {
    val payload = certifiedPayload
    val description = RecallDisclosure.receiptDescription(RecallDisclosure.certified(payload))

    assert(description.contains("certified-pseudonymized"))
    assert(description.contains(payload.digest.render))
    assert(!description.contains(payload.text))
    assert(!description.contains("Jane"))
  }
