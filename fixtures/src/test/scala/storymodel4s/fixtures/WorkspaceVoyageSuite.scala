package storymodel4s.fixtures

import java.nio.charset.StandardCharsets

import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

class WorkspaceVoyageSuite extends FunSuite:
  import WorkspaceVoyage.{Disposition, Unavailable}
  private val revision = "0" * 40
  private lazy val fixtures = WorkspaceFixtures.all(revision).toMap
  // Producer construction and the large checked WOG repack are fixture setup, not work
  // performed by the adapter. Keep the default per-test timeout and every behavioral assertion.
  override def beforeAll(): Unit =
    val started = System.nanoTime()
    val _ = fixtures
    println(
      s"WorkspaceVoyage producer fixture setup: ${(System.nanoTime() - started) / 1000000L} ms"
    )
    val repackStarted = System.nanoTime()
    val _ = externalWinnerWorkspace
    println(
      s"WorkspaceVoyage external-winner checked fixture setup: ${(System.nanoTime() - repackStarted) / 1000000L} ms"
    )

  private lazy val bell = fixtures("bell")
  private val historical = ArtifactId.unsafe("historical-lexical")
  private val u1 = RecallUnitId.unsafe("m1:u1")
  private def q(n: Long, d: Long = 1L) = ExactRational.of(n, d).toOption.get
  private def input(workspace: SourceRecallWorkspace): WorkspaceClockInput =
    val clocks = workspace.clocks.get
    WorkspaceClockInput(
      clocks.words,
      clocks.sourceTimeline,
      clocks.recallExtent,
      clocks.declaration,
      clocks.origin,
      clocks.kind
    )
  private def repack(
      workspace: SourceRecallWorkspace,
      clocks: Option[WorkspaceClockInput],
      changed: Option[MappingResult] = None
  ): Either[WorkspaceRefusal, SourceRecallWorkspace] =
    val derivation = Canonical
      .parse(
        new String(
          workspace.archive.manifest.bytes(WorkspaceRole.Derivation).get.toArray,
          StandardCharsets.UTF_8
        )
      )
      .toOption
      .get
    val view = WorkspaceCodecs.sourceFor(workspace.model).toOption.get._1
    val hsmm = derivation.hcursor
      .get[Vector[io.circe.Json]]("mappings")
      .toOption
      .get
      .map { json =>
        val id = ArtifactId.unsafe(json.hcursor.get[String]("id").toOption.get)
        val value = json.hcursor
          .downField("hsmm")
          .get[String]("value")
          .toOption
          .map(t => HsmmResultCodec.decode(t, workspace.recall, view).toOption.get)
        id -> value
      }
      .toMap
    WorkspaceCodecs.create(
      workspace.draft.model,
      workspace.recall,
      workspace.inventory,
      None,
      workspace.policies.map(p =>
        WorkspaceMappingInput(
          p.id,
          if p.id == historical then changed.getOrElse(p.record) else p.record,
          hsmm(p.id)
        )
      ),
      workspace.timing,
      workspace.origin,
      revision,
      WorkspaceContentGrant.Granted,
      WorkspaceContentGrant.Granted,
      clocks
    )
  // Checked archive production is fixture setup; the named test still exercises the adapter.
  private lazy val externalWinnerWorkspace: SourceRecallWorkspace =
    val wog = fixtures("wog")
    val original = input(bell)
    val words = RecallTiming
      .checked(
        wog.inventory,
        original.words.clock,
        wog.inventory.words.map(w =>
          RecallTiming.Entry(
            w.id,
            original.words.clock,
            RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
          )
        ),
        original.words.provenance
      )
      .toOption
      .get
    val timeline = SourceTimeline
      .of(
        wog.source.targets.map(t =>
          SourceTimelineNode(t.ref, t.level, None, ClockSpan.of(0, 30).toOption.get, t.ref.key)
        ),
        Vector.empty
      )
      .toOption
      .get
    repack(wog, Some(original.copy(words = words, sourceTimeline = timeline))).toOption.get
  private def wordsChanged(f: RecallTiming.Entry => RecallTiming.Observation): WorkspaceClockInput =
    val original = input(bell)
    val timing = original.words
    original.copy(words =
      RecallTiming
        .checked(
          bell.inventory,
          timing.clock,
          timing.entries.map(e => e.copy(observation = f(e))),
          timing.provenance
        )
        .toOption
        .get
    )
  private def opened(clocks: WorkspaceClockInput) = repack(bell, Some(clocks)).toOption.get
  private def projection(workspace: SourceRecallWorkspace) =
    WorkspaceVoyage.from(workspace, historical).toOption.get

  test("actual timed posterior compiles, roundtrips, and preserves every state bit and ordinal") {
    val result = projection(bell)
    val document = result.document.get
    assertEquals(result.units.map(_.unit), bell.inventory.units.map(_.id))
    assertEquals(result.units.map(_.ordinal), Vector(0, 1, 2, 3))
    assert(result.units.exists(_.disposition == Disposition.Plotted))
    assertEquals(document.input.unitOf(u1).onset.map(_.value), Some(2.5))
    assertEquals(
      document.input.timeline.nodes.map(_.ref).toSet,
      bell.source.targets.map(_.ref).toSet
    )
    val reopened = VoyageCodecs.decode(VoyageCodecs.encode(document)).toOption.get
    val scene = reopened.compile(Set.empty).toOption.get
    assert(scene.marks.exists(_.isInstanceOf[VoyageMark.UnitAnchor]))
    assert(scene.provenance.basis.label.contains("synthetic presentation clocks"))
    assert(scene.provenance.basis.label.contains("unestablished"))
    reopened.input.matrix.rows.foreach { row =>
      val supplied =
        bell.policy(historical).get.record.outcome(row.unit).get.measures.posterior.get.mass
      assertEquals(
        row.mass.view.mapValues(java.lang.Double.doubleToLongBits).toMap,
        supplied.view.mapValues(java.lang.Double.doubleToLongBits).toMap
      )
      val chosen = bell.policy(historical).get.record.outcome(row.unit).get.decision.get.chosen
      assertEquals(reopened.input.decisionOf(row.unit).anchor.map(Destination.Target.apply), chosen)
    }
    assert(!document.input.units.head.text.contains("This note is outside"))
  }

  test("missing clocks and raw-only policies remain inspectable with all dispositions") {
    val untimed = projection(fixtures("wog"))
    assertEquals(untimed.document, None)
    assertEquals(
      untimed.units.map(_.disposition).distinct,
      Vector(Disposition.Unsupported(Unavailable.ClocksNotSupplied))
    )
    val authored = WorkspaceVoyage.from(bell, ArtifactId.unsafe("authored-a")).toOption.get
    assertEquals(authored.document, None)
    assertEquals(authored.units.size, 4)
    assertEquals(
      authored.units.last.disposition,
      Disposition.Unsupported(Unavailable.ProcessingNotComplete)
    )
    assertEquals(
      authored.units.head.disposition,
      Disposition.Unsupported(Unavailable.PosteriorNotSupplied)
    )
    assertEquals(bell.policies.head.matrix.rows.size, 4)
  }

  test("identical observations require an explicit bound recall-start declaration") {
    val unestablished = opened(input(bell).copy(origin = WorkspaceClockOrigin.Unestablished))
    assertEquals(unestablished.clocks.get.words.digest, bell.clocks.get.words.digest)
    assertEquals(projection(unestablished).document, None)
    assertEquals(
      projection(unestablished).units.head.disposition,
      Disposition.Unsupported(Unavailable.RecallStartUndeclared)
    )
    assertEquals(
      repack(
        bell,
        Some(
          input(bell)
            .copy(origin = WorkspaceClockOrigin.RecallStart(Checksum.ofText("foreign origin")))
        )
      ),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
  }

  test("first available later word does not replace missing first member or unit annotation") {
    val members = bell.inventory.units.find(_.id == u1).get.words
    val clocks = wordsChanged(e =>
      if e.word == members.head then
        RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
      else if e.word == members(1) then
        RecallTiming.Observation
          .OnsetOnly(q(3), RecallTiming.Basis.Estimated(Checksum.ofText("later word")))
      else e.observation
    )
    val result = projection(opened(clocks))
    assertEquals(result.document.get.input.unitOf(u1).onset, None)
    assertEquals(result.units.find(_.unit == u1).get.disposition, Disposition.Untimed)
  }

  test(
    "exact decimal and negative onsets remain in workspace while their projection is unavailable"
  ) {
    val first = bell.inventory.units.find(_.id == u1).get.words.head
    Vector(q(1, 10), q(-1)).foreach { value =>
      val clocks = wordsChanged(e =>
        if e.word == first then
          RecallTiming.Observation
            .OnsetOnly(value, RecallTiming.Basis.Estimated(Checksum.ofText("exact observation")))
        else e.observation
      )
      val workspace = opened(clocks)
      assertEquals(workspace.clocks.get.words.digest, clocks.words.digest)
      val result = projection(workspace)
      assertEquals(
        result.units.find(_.unit == u1).get.disposition,
        Disposition.Unsupported(Unavailable.ClockNotRepresentable)
      )
      assert(!result.document.get.input.unitOf.contains(u1))
      assertEquals(result.document.get.input.timeline.nodes.size, bell.source.targets.size)
    }
  }

  test("word interval end never becomes last-word onset") {
    val first = bell.inventory.units.find(_.id == u1).get.words.head
    val result = projection(
      opened(
        wordsChanged(e =>
          if e.word == first then
            RecallTiming.Observation
              .Interval(q(5, 2), q(3), RecallTiming.Basis.Estimated(Checksum.ofText("interval")))
          else e.observation
        )
      )
    )
    assertEquals(result.document.get.input.unitOf(u1).onset.map(_.value), Some(2.5))
    assertEquals(result.document.get.input.unitOf(u1).lastWordOnset, None)
  }

  test("actual mark addresses bridge source and recall selection without phantom cells") {
    val result = projection(bell)
    val scene = result.document.get.compile(Set.empty).toOption.get
    assertEquals(result.addresses.keySet, scene.navigation.byAddress.keySet)
    val anchor = scene.marks.collectFirst { case m: VoyageMark.UnitAnchor => m }.get
    val source = bell.sourceAddress(anchor.anchor).get
    val recall = bell.recallAddress(anchor.unit).get
    assertEquals(result.workspaceSelection(anchor.address), Set(source, recall))
    val selection = result.legacySelection(Set(source))
    assert(selection(anchor.address))
    val selected = result.document.get.compile(selection).toOption.get
    assert(selected.selectionPlacements.contains(anchor.address))
    assertEquals(
      result.legacySelection(Set(fixtures("wog").recallAddress(anchor.unit).get)),
      Set.empty
    )
  }

  test("external global winner with residual source mass does not become a source anchor") {
    val workspace = externalWinnerWorkspace
    val record = workspace.policy(historical).get.record
    assert(
      record.outcomes.forall(o =>
        o.decision.get.chosen.exists(_.isInstanceOf[Destination.External])
      )
    )
    assert(
      record.outcomes.forall(
        _.measures.posterior.get.mass.exists((state, mass) => state.anchor.nonEmpty && mass > 0)
      )
    )
    val result = projection(workspace)
    assertEquals(result.document, None)
    assertEquals(
      result.units.map(_.disposition).distinct,
      Vector(Disposition.Unsupported(Unavailable.DecisionDiffersFromSourceArgmax))
    )
  }

  test("agreeing structured decode and abstention cannot be relabeled posterior argmax") {
    val record = bell.policy(historical).get.record
    val decoder = DecisionPolicyId.unsafe("adapter-test-decode")
    val outcomes = record.outcomes.zipWithIndex.map { (outcome, index) =>
      if index == 2 then
        UnitOutcome.failed(outcome.unit, ProcessingFailure.InvalidOutput("control"))
      else if index == 3 then UnitOutcome.excluded(outcome.unit, "control")
      else
        UnitOutcome
          .computed(
            outcome.unit,
            outcome.measures,
            outcome.links,
            outcome.decision.get.basis,
            if index == 0 then
              DecisionRequest.ExternalDecode(outcome.decision.get.chosen.get, decoder)
            else DecisionRequest.Abstain(decoder, "control"),
            outcome.stages.get
          )
          .toOption
          .get
    }
    val policies = record.policies
    val updated = MappingResult
      .checked(
        record.inventory,
        record.source,
        MappingPolicies
          .of(
            policies.inference,
            policies.context,
            policies.candidate,
            policies.referencePrior,
            DecisionPolicy.Declared(decoder),
            policies.universe
          )
          .toOption
          .get,
        record.roles,
        record.ledger,
        outcomes
      )
      .toOption
      .get
    val result = projection(repack(bell, Some(input(bell)), Some(updated)).toOption.get)
    assertEquals(result.document, None)
    assertEquals(
      result.units.take(2).map(_.disposition).distinct,
      Vector(Disposition.Unsupported(Unavailable.DecisionNotPosteriorArgmax))
    )
    assertEquals(
      result.units.drop(2).map(_.disposition).distinct,
      Vector(Disposition.Unsupported(Unavailable.ProcessingNotComplete))
    )
  }
