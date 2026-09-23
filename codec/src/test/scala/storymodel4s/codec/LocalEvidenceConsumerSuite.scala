package storymodel4s.codec

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.align.*
import storymodel4s.core.Checksum
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Outside-align pairing witnesses: public identity is derived, and legacy hash aliases refuse. */
class LocalEvidenceConsumerSuite extends FunSuite:
  private val fixture = MappingCodecFixture
  private val cost = DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
  private val nominations = fixture.refs.flatMap { ref =>
    Vector("receipt-a", "receipt-b").map(receipt =>
      Nomination(ref, "consumer", 0, Some(0.5), Some("space"), Some(receipt))
    )
  }
  private def candidates(ns: Vector[Nomination]): Candidates = Candidates(
    fixture.recall.ordered.map(u => u.id -> CandidateSet(ns, abstained = false)).toMap
  )
  private def evidence(
      ns: Vector[Nomination] = nominations,
      recall: RecallGraph[Checked] = fixture.recall,
      view: SourceView = fixture.view
  ): LocalEvidence = LocalEvidence
    .compute(recall, view, candidates(ns), cost, gate = true)
    .fold(e => fail(e.message), identity)

  test("consumer can read the derived digest but cannot manufacture its identity") {
    val positive = typeCheckErrors("""
      import storymodel4s.align.{LocalEvidence, LocalEvidenceId}
      import storymodel4s.core.Checksum
      def read(e: LocalEvidence): Checksum = e.identity.checksum
    """)
    val assignment = typeCheckErrors("""
      import storymodel4s.align.LocalEvidenceId
      import storymodel4s.core.Checksum
      val forged: LocalEvidenceId = Checksum.ofText("asserted")
    """)
    val derive = typeCheckErrors("""
      import storymodel4s.align.{LocalEvidence, LocalEvidenceId}
      def forge(e: LocalEvidence) = LocalEvidenceId.derive(e)
    """)
    assert(positive.isEmpty, positive.toString)
    assert(assignment.nonEmpty, assignment.toString)
    assert(derive.nonEmpty, derive.toString)
    assertEquals(evidence().identity.checksum, evidence().identity.checksum)
    assertEquals(
      Checksum.from(evidence().identity.checksum.hex).toOption.get,
      evidence().identity.checksum
    )
  }

  test("consumer identity ignores storage order even when nominations tie except for receipt") {
    assert(nominations.size > 1)
    assertNotEquals(nominations, nominations.reverse)
    assertEquals(evidence(nominations).identity, evidence(nominations.reverse).identity)
  }

  test("consumer identity preserves absent versus empty nomination metadata") {
    val absent = nominations.map(_.copy(space = None, receipt = None))
    val empty = nominations.map(_.copy(space = Some(""), receipt = Some("")))
    assertEquals(evidence(absent).breakdowns, evidence(empty).breakdowns)
    assertNotEquals(evidence(absent).identity, evidence(empty).identity)
  }

  private def recallWithCause(cause: Option[String]): RecallGraph[Checked] = RecallGraph
    .validated(
      fixture.recall.transcript,
      fixture.recall.atlas,
      fixture.recall.units.map(u => u.copy(proposition = u.proposition.copy(cause = cause))),
      fixture.recall.relations
    )
    .toOption
    .get

  test("consumer cannot reuse evidence across absent versus empty recall content") {
    val absent = recallWithCause(None)
    val empty = recallWithCause(Some(""))
    assertEquals(
      AlignWire.recallChecksum(absent),
      AlignWire.recallChecksum(empty),
      "legacy alias witness"
    )
    val saved = evidence(recall = absent)
    val refused = GraphHsmm.infer(empty, fixture.view, saved, HsmmConfig.default)
    assert(refused.left.exists(_.message.contains("different recall")), refused.toString)
    assertNotEquals(saved.identity, evidence(recall = empty).identity)
  }

  test("consumer cannot reuse evidence across absent versus empty source content") {
    val absent = fixture.view.copy(nodes = fixture.view.nodes.map(_.copy(cause = None)))
    val empty = fixture.view.copy(nodes = fixture.view.nodes.map(_.copy(cause = Some(""))))
    assertEquals(ViewFingerprint.of(absent), ViewFingerprint.of(empty), "legacy alias witness")
    val saved = evidence(view = absent)
    val refused = GraphHsmm.infer(fixture.recall, empty, saved, HsmmConfig.default)
    assert(refused.left.exists(_.message.contains("different source view")), refused.toString)
    assertNotEquals(saved.identity, evidence(view = empty).identity)
  }
