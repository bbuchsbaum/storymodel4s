package storymodel4s.fixtures

import munit.FunSuite
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

class WorkspaceClockSuite extends FunSuite:
  private val revision = "0" * 40
  private lazy val base =
    WorkspaceFixtures.all(revision, includeHistorical = false).toMap.apply("bell")
  private def q(n: Long, d: Long = 1L) = ExactRational.of(n, d).toOption.get
  private lazy val clock = RecallTiming.Clock.declared(
    Checksum.ofText("synthetic-clock"),
    RecallTiming.ClockKey.unsafe("word-onsets"),
    Checksum.ofText("synthetic timing declaration"),
    RecallTiming.RecordingIdentity.Unestablished,
    RecallTiming.Origin.Unestablished
  )
  private def words(at: ExactRational = q(5, 2)): RecallTiming =
    val first = base.inventory.units(1).words.head
    val entries = base.inventory.words.map(w =>
      RecallTiming.Entry(
        w.id,
        clock,
        if w.id == first then
          RecallTiming.Observation.OnsetOnly(
            at,
            RecallTiming.Basis.Estimated(Checksum.ofText("synthetic scripted onsets"))
          )
        else RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
      )
    )
    RecallTiming
      .checked(
        base.inventory,
        clock,
        entries,
        RecallTiming.Provenance(
          Checksum.ofText("synthetic correspondence"),
          RecallTiming.RecordingLink.NotEstablished
        )
      )
      .toOption
      .get
  private lazy val timeline = SourceTimeline
    .of(
      base.source.targets.map(t =>
        SourceTimelineNode(t.ref, t.level, None, ClockSpan.of(0, 30).toOption.get, t.ref.key)
      ),
      Vector.empty
    )
    .toOption
    .get
  private def input(at: ExactRational = q(5, 2)) = WorkspaceClockInput(
    words(at),
    timeline,
    Seconds.of(10).toOption.get,
    Checksum.ofText("synthetic source presentation")
  )
  private def create(clocks: Option[WorkspaceClockInput]) = WorkspaceCodecs.create(
    base.draft.model,
    base.recall,
    base.inventory,
    None,
    base.policies.map(p => WorkspaceMappingInput(p.id, p.record, None)),
    base.timing,
    WorkspaceOrigin.AuthoredFixture,
    revision,
    WorkspaceContentGrant.Granted,
    WorkspaceContentGrant.Granted,
    clocks
  )

  test("optional clocks bind the actual workspace and preserve independent word observations") {
    val opened = create(Some(input())).toOption.get
    assertEquals(opened.clocks.get.words.digest, words().digest)
    assertEquals(opened.clocks.get.sourceTimeline.nodes.size, base.source.targets.size)
    val archive = WorkspaceArchiveCodec.encode(opened.archive.manifest).toOption.get
    val reopened = WorkspaceCodecs.decode(archive).toOption.get
    assertEquals(reopened.clocks.get.words.digest, words().digest)
    assertEquals(create(None).toOption.get.clocks, None)
    val exported =
      WorkspaceSubsetCodec.selected(opened, opened.policies.head.id, Set.empty).toOption.get
    assert(exported.dataJson.contains("recall-timing/v0.1"))
    assert(exported.dataJson.contains("unestablished"))
  }

  test("foreign timeline refs and incomplete dictionaries refuse instead of changing denominator") {
    val incomplete = SourceTimeline.of(timeline.nodes.tail, Vector.empty).toOption.get
    assertEquals(
      create(Some(input().copy(sourceTimeline = incomplete))),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    val foreign = timeline.nodes.head
      .copy(ref = storymodel4s.align.SourceNodeRef.Situation(SituationId.unsafe("foreign")))
    val changed = SourceTimeline.of(foreign +: timeline.nodes.tail, Vector.empty).toOption.get
    assertEquals(
      create(Some(input().copy(sourceTimeline = changed))),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
  }

  test(
    "negative and decimal word observations remain exact even when legacy view cannot place them"
  ) {
    Vector(q(-1), q(1, 10)).foreach { value =>
      val opened = create(Some(input(value))).toOption.get
      assertEquals(opened.clocks.get.words.digest, words(value).digest)
    }
    assertEquals(create(Some(input(q(11)))), Left(WorkspaceRefusal.SemanticJoinMismatch))
  }
