package storymodel4s.fixtures

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.RecallUnitId
import storymodel4s.view.*

class WorkspaceFixtureSuite extends FunSuite:
  private lazy val examples = WorkspaceFixtures.all("0" * 40, includeHistorical = false).toMap
  private val a = ArtifactId.unsafe("authored-a")
  private val b = ArtifactId.unsafe("authored-b")
  private val u0 = RecallUnitId.unsafe("m1:u0")

  test("two generated checked fixtures differ in hierarchy and retain partial authority") {
    val wog = examples("wog")
    val bell = examples("bell")
    assert(wog.model.graph.segments.values.map(_.level).max == 3)
    assertEquals(bell.model.graph.segments.size, 1)
    assertEquals(bell.model.graph.situations.size, 2)
    assertEquals(bell.draft.abstentions.size, 3)
    assertEquals(wog.draft.derivation, DerivationRecord.NotSupplied)
    assertEquals(bell.origin, WorkspaceOrigin.AuthoredFixture)
    assertNotEquals(wog.modelArtifact, bell.modelArtifact)
    assertNotEquals(wog.recallAddress(u0), bell.recallAddress(u0))
  }

  test("known authored answers preserve decoding disagreement, external mass and missing cells") {
    val workspace = examples("bell")
    val ring = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("bell:sit:ring")))
    val quiet = Destination.Target(SourceNodeRef.Situation(SituationId.unsafe("bell:sit:quiet")))
    val row = workspace.policy(a).get.matrix.row(u0).get
    assertEquals(row.cell(ring).get.raw.map(_._2), Vector(0.9))
    assertEquals(row.cell(ring).get.normalized, Some(0.25))
    assertEquals(row.cell(quiet).get.normalized, Some(0.25))
    assertEquals(row.cell(Destination.External(ExternalState.Intrusion)).get.normalized, Some(0.5))
    assertEquals(row.outcome.decision.get.rawArgmax.map(_._1), Some(ring))
    assertEquals(row.outcome.decision.get.chosen, Some(quiet))
    assert(row.cell(quiet).get.chosen)
    assertEquals(row.cell(ring).get.posterior, Vector.empty)
    assertEquals(
      workspace.policy(b).get.matrix.row(u0).get.cell(quiet).get.raw.map(_._2),
      Vector(0.8)
    )
    assertEquals(workspace.policy(a).get.matrix.rows.size, 4)
    assertEquals(
      workspace.timing(RecallUnitId.unsafe("m1:u1")),
      WorkspaceTiming.Onset(Seconds.of(2.5).toOption.get)
    )
    assertEquals(
      workspace.policy(a).get.matrix.rows.last.outcome.localization,
      LocalizationStatus.NotComputed
    )
  }

  test("both source and recall retain exact discontiguous evidence") {
    val workspace = examples("bell")
    val ring = SourceNodeRef.Situation(SituationId.unsafe("bell:sit:ring"))
    assertEquals(
      workspace.sourceEvidence(ring).toOption.flatten.get.map(_._2),
      Vector("A bell rang.", "The bell rang again.")
    )
    assertEquals(
      workspace.recallEvidence(u0).toOption.get.map(_._2),
      Vector("A bell rang.", "It rang again.")
    )
    assertEquals(workspace.inverse(a, ring).toOption.get.map(_.value), Vector("m1:u0", "m1:u1"))
    assertEquals(workspace.timing(u0), WorkspaceTiming.Untimed)
    val encoded = WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get
    val reopened = WorkspaceCodecs.decode(encoded).toOption.get
    assertEquals(reopened.sourceEvidence(ring), workspace.sourceEvidence(ring))
  }
