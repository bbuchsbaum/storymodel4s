package storymodel4s.fixtures

import java.nio.charset.StandardCharsets

import io.circe.Json
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.codec.*
import storymodel4s.core.*
import storymodel4s.document.{SentenceCoverage, SummaryCoverage}
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.story.*
import storymodel4s.view.*

class WorkspaceJoinSuite extends FunSuite:
  private val model = WarOfTheGhostsModel.draft
  private val pair = WorkspaceCodecs.sourceFor(WarOfTheGhostsModel.model).toOption.get
  private val source = pair._2
  private val target = source.targets.find(_.level == 0).get.ref
  private val other = source.targets.filter(_.level == 0).find(_.ref != target).get.ref
  private val policyId = ArtifactId.unsafe("authored-control")
  private val revision = "0" * 40
  private val recallText =
    "Two men walked. A note was omitted. A canoe arrived. One man returned. I heard a bell. I forgot the ending."
  private def recallFor(text: String): RecallGraph[Checked] =
    val transcript = StorySource.fromText(text, Some("Synthetic recall")).toOption.get
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val groups = Vector(Vector(0, 2), Vector(3), Vector(4), Vector(5))
    val units = groups.zipWithIndex.map { (indices, ordinal) =>
      val spans = SpanSet
        .of(indices.map(i => SpanRef(Some(atlas.sentences(i).id), atlas.sentences(i).span)))
        .get
      RecallUnit(
        RecallUnitId.unsafe(s"u$ordinal"),
        ordinal,
        spans,
        spans.minSpan.slice(transcript.canonicalText).toOption.get,
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    }
    RecallGraph.validated(transcript, atlas, units, RecallRelations.empty).toOption.get
  private val recall = recallFor(recallText)
  private def inventoryFor(graph: RecallGraph[Checked]): RecallInventory = RecallInventory
    .of(
      graph,
      "\\S+".r
        .findAllMatchIn(graph.transcript.canonicalText)
        .map(m => TextSpan.unsafe(m.start, m.end))
        .toVector,
      WordIdPolicy.inputArtifact(Checksum.ofText(graph.transcript.canonicalText))
    )
    .toOption
    .get
  private val inventory = inventoryFor(recall)
  private val timing = Map[RecallUnitId, WorkspaceTiming](
    recall.ordered(0).id -> WorkspaceTiming.Untimed,
    recall.ordered(1).id -> WorkspaceTiming.Onset(Seconds.of(2.5).toOption.get),
    recall.ordered(2).id -> WorkspaceTiming.Interval(ClockSpan.of(4.0, 5.0).toOption.get),
    recall.ordered(3).id -> WorkspaceTiming.Untimed
  )
  private val ledger = StageLedger
    .of(
      Stage.values.toVector.map(stage =>
        StageEntry
          .of(stage, StageProvenance.unknown(UnknownProvenanceReason.NotRun, None))
          .toOption
          .get
      )
    )
    .toOption
    .get
  private def stage(value: Stage) = ledger.at(value).head.id
  private val stages = UnitStageRefs
    .of(ledger, stage(Stage.Inference), stage(Stage.Candidates), stage(Stage.Decision))
    .toOption
    .get
  private val grain = TargetGrain.SingleLevel(0)
  private val universe =
    DeclaredUniverse.of(source.targets.filter(_.level == 0).map(_.ref), grain).toOption.get
  private val policies = MappingPolicies
    .of(
      InferencePolicy.Unspecified("authored synthetic control"),
      ContextPolicy.Unspecified("not modeled"),
      CandidatePolicy.Unknown("nomination recall unmeasured"),
      ReferencePrior.NotApplicable("raw scores"),
      DecisionPolicy.NotApplicable("raw argmax"),
      universe
    )
    .toOption
    .get
  private val roles = UnitRoles
    .of(
      AnalysisGrain.InferenceUnit(inventory.segmentation),
      AnalysisGrain.InferenceUnit(inventory.segmentation),
      AnalysisGrain.Targets(grain)
    )
    .toOption
    .get
  private val outcomes = recall.ordered.zipWithIndex.map { (unit, i) =>
    if i == 3 then
      UnitOutcome.failed(unit.id, ProcessingFailure.ProviderFailure("synthetic failure"))
    else
      val values: Map[Destination, Double] =
        if i == 2 then Map(Destination.External(ExternalState.Association) -> 1.0)
        else
          Map(
            Destination.Target(target) -> 0.6,
            Destination.Target(other) -> 0.2,
            Destination.External(ExternalState.Intrusion) -> 0.2
          )
      val raw = RawScores
        .of(
          "authored",
          ScoreDirection.HigherIsBetter,
          "synthetic arbitrary units",
          values,
          stage(Stage.Scoring)
        )
        .toOption
        .get
      val measures = UnitMeasures.of(Vector(raw), None, None, None).toOption.get
      val candidates = CandidateSetId.of(stages.candidates, unit.id, values.keySet)
      val links = values.keys.toVector.sorted.map(MappingLink.ungated(_, stages, candidates))
      UnitOutcome
        .computed(
          unit.id,
          measures,
          links,
          DecisionBasis.of(MeasureKind.RawScore, Some("authored")).toOption.get,
          DecisionRequest.RawArgmax,
          stages
        )
        .toOption
        .get
  }
  private val record =
    MappingResult.checked(inventory, source, policies, roles, ledger, outcomes).toOption.get
  private val mapping = WorkspaceMappingInput(policyId, record, None)
  private def create(
      m: TextModel[ModelStatus.Draft] = model,
      r: RecallGraph[Checked] = recall,
      inv: RecallInventory = inventory,
      records: Vector[WorkspaceMappingInput] = Vector(mapping),
      clocks: Map[RecallUnitId, WorkspaceTiming] = timing,
      compilation: Option[DerivationArtifact] = None,
      inspection: WorkspaceContentGrant = WorkspaceContentGrant.Granted,
      exportPermission: WorkspaceContentGrant = WorkspaceContentGrant.Granted
  ) = WorkspaceCodecs.create(
    m,
    r,
    inv,
    compilation,
    records,
    clocks,
    WorkspaceOrigin.AuthoredFixture,
    revision,
    inspection,
    exportPermission
  )
  private lazy val workspace = create().fold(e => fail(e.toString), identity)

  test("generated WOG model, recall, inventory and precomputed mapping open together") {
    assertEquals(workspace.model.source.canonicalText, model.source.canonicalText)
    assertEquals(workspace.recall, recall)
    assertEquals(workspace.inventory.digest, inventory.digest)
    assertEquals(workspace.policy(policyId).get.record.digest, record.digest)
    assertEquals(
      workspace.policy(policyId).get.matrix.rows.map(_.unit.id),
      recall.ordered.map(_.id)
    )
    assertEquals(workspace.draft.derivation, DerivationRecord.NotSupplied)
    assertEquals(workspace.draft.promotion.gapCount, None)
    assertEquals(workspace.origin, WorkspaceOrigin.AuthoredFixture)
    assertEquals(workspace.producerRevision, revision)
  }
  test("untimed, onset-only, all-external and failed units remain in the ordinal inventory") {
    assertEquals(workspace.timing, timing)
    assertEquals(workspace.policy(policyId).get.matrix.rows.map(_.unit.ordinal), Vector(0, 1, 2, 3))
    assertEquals(
      workspace.policy(policyId).get.record.outcomes(2).localization,
      LocalizationStatus.Nonlocalizable
    )
    assert(
      workspace
        .policy(policyId)
        .get
        .record
        .outcomes(3)
        .processing
        .isInstanceOf[ProcessingStatus.Failed]
    )
    assertEquals(
      create(clocks = timing - recall.ordered.head.id),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
  }
  test("exact discontiguous recall evidence omits hull-only text and retains SpanRefs") {
    val pieces = workspace.recallEvidence(recall.ordered.head.id).toOption.get
    assertEquals(pieces.map(_._1), recall.ordered.head.span.refs.toVector)
    assertEquals(pieces.map(_._2), Vector("Two men walked.", "A canoe arrived."))
    assert(!pieces.map(_._2).mkString.contains("note was omitted"))
    assert(workspace.recallEvidence(RecallUnitId.unsafe("foreign")).isLeft)
  }
  test("exact source evidence and repeated inverse references remain available") {
    val spans = source
      .target(target)
      .get
      .sourceSupport
      .asInstanceOf[SourceSupportStatus.Located]
      .support
      .textSpans
      .get
    val pieces = workspace.sourceEvidence(target).toOption.flatten.get
    assertEquals(pieces.map(_._1), spans.refs.toVector)
    assertEquals(
      pieces.map(_._2),
      spans.refs.toVector.map(_.span.slice(model.source.canonicalText).toOption.get)
    )
    assertEquals(workspace.inverse(policyId, target), Right(recall.ordered.take(2).map(_.id)))
    assert(workspace.inverse(ArtifactId.unsafe("foreign"), target).isLeft)
  }
  test("compatible policies preserve qualified semantic addresses and fixed cut") {
    val secondId = ArtifactId.unsafe("second-precomputed-policy")
    val second = create(records = Vector(mapping, mapping.copy(id = secondId))).toOption.get
    assertEquals(second.policies.map(_.id).toSet, Set(policyId, secondId))
    recall.ordered.foreach(u =>
      assertEquals(second.recallAddress(u.id), workspace.recallAddress(u.id))
    )
    assertEquals(second.sourceAddress(target), workspace.sourceAddress(target))
    assert(second.contains(second.recallAddress(recall.ordered.head.id).get))
    assertEquals(second.recallAddress(RecallUnitId.unsafe("unknown")), None)
  }
  test("foreign recall with the same local unit IDs and spans refuses the old mapping") {
    val foreign = recallFor(recallText.replace("bell", "bird"))
    assertEquals(foreign.ordered.map(_.id), recall.ordered.map(_.id))
    assertEquals(foreign.ordered.map(_.span.spans), recall.ordered.map(_.span.spans))
    assertEquals(
      create(r = foreign, inv = inventoryFor(foreign)),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    assertEquals(create(r = foreign), Left(WorkspaceRefusal.SemanticJoinMismatch))
  }
  test("a structurally checked model retains reported no-chart abstention") {
    val compilation = DerivationArtifact
      .of(
        model.source.id,
        model.source.canonicalChecksum,
        StoryModelCodec.contentChecksum(model),
        Checksum.ofText("synthetic compilation"),
        Checksum.ofText("synthetic candidates"),
        Vector.empty,
        Vector.empty,
        Vector(SentenceCoverage.NoChart(model.atlas.sentences.head.id)),
        SummaryCoverage.NoTitle
      )
      .toOption
      .get
    val partial = create(compilation = Some(compilation)).toOption.get
    assert(partial.draft.promotion.promoted)
    assertEquals(partial.draft.abstentions.map(_._1), Vector(model.atlas.sentences.head.id))
    assertEquals(partial.draft.derivation, compilation.record)
  }
  test("missing permission refuses before any packet is returned") {
    assertEquals(
      create(inspection = WorkspaceContentGrant.Denied),
      Left(WorkspaceRefusal.PermissionDenied)
    )
    assertEquals(
      create(exportPermission = WorkspaceContentGrant.Denied),
      Left(WorkspaceRefusal.PermissionDenied)
    )
  }
  test("duplicate policies and foreign fixed cuts refuse") {
    assertEquals(
      create(records = Vector(mapping, mapping)),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    val smaller = DeclaredUniverse.of(Vector(target, other), grain).toOption.get
    val changedPolicies = MappingPolicies
      .of(
        policies.inference,
        policies.context,
        policies.candidate,
        policies.referencePrior,
        policies.decision,
        smaller
      )
      .toOption
      .get
    val changed = MappingResult
      .checked(inventory, source, changedPolicies, roles, ledger, outcomes)
      .toOption
      .get
    assertEquals(
      create(records =
        Vector(mapping, WorkspaceMappingInput(ArtifactId.unsafe("foreign-cut"), changed, None))
      ),
      Left(WorkspaceRefusal.IncompatiblePolicy)
    )
  }
  test("archive round trip rechecks the complete semantic join") {
    val encoded = WorkspaceArchiveCodec.encode(workspace.archive.manifest).toOption.get
    val reopened = WorkspaceCodecs.decode(encoded).toOption.get
    assertEquals(reopened.policy(policyId).get.record.digest, record.digest)
    assertEquals(reopened.recallAddresses, workspace.recallAddresses)
    assertEquals(reopened.sourceAddresses, workspace.sourceAddresses)
    assertEquals(reopened.timing, timing)
    assertEquals(
      new String(
        reopened.archive.manifest.bytes(WorkspaceRole.SourceModel).get.toArray,
        StandardCharsets.UTF_8
      ).contains("schemaVersion"),
      true
    )
  }

  test("a foreign model interpretation with unchanged story, text and local IDs refuses") {
    val first = model.graph.situations.toVector.collectFirst {
      case (id, SituationNode.Event(event)) => id -> event
    }.get
    val graph = model.graph.copy(situations =
      model.graph.situations.updated(
        first._1,
        SituationNode.Event(
          first._2.copy(description = "A synthetic spaceship carries a telescope")
        )
      )
    )
    val foreign = StoryModel
      .draftText(
        model.atlas,
        graph,
        model.hierarchy,
        model.trajectory,
        model.featureSpaces,
        model.sidecars,
        model.featureRefs,
        model.descriptors,
        model.hypotheses,
        model.sensoryProfiles,
        model.receipt,
        model.schemaVersion
      )
      .toOption
      .get
    assert(StoryValidator.validate(foreign).validated.isDefined)
    assertEquals(foreign.source, model.source)
    assertEquals(foreign.graph.situations.keySet, model.graph.situations.keySet)
    assertEquals(create(m = foreign), Left(WorkspaceRefusal.SemanticJoinMismatch))
  }

  test("historical results are contextually decoded and cannot float to changed recall semantics") {
    val view = pair._1
    val result = GraphHsmm
      .infer(
        recall,
        view,
        Candidates.of(recall.ordered.map(u => u.id -> Vector(target, other)).toMap),
        DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard)
      )
      .toOption
      .get
    val historical = HistoricalMapping
      .of(result, recall, view, inventory, source, HistoricalDecode.ArgmaxOnly)
      .toOption
      .get
    val input = WorkspaceMappingInput(ArtifactId.unsafe("historical"), historical, Some(result))
    val opened = create(records = Vector(input)).toOption.get
    assertEquals(opened.policies.head.record.digest, historical.digest)
    assertEquals(opened.policies.head.record.policies.inference, historical.policies.inference)
    opened.policies.head.matrix.rows.foreach { row =>
      assertEquals(row.cells.flatMap(_.posterior).toMap, result.posterior.row(row.unit.id).get.mass)
    }
    assertEquals(
      create(records = Vector(input.copy(result = None))),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    assertEquals(
      create(records = Vector(mapping.copy(result = Some(result)))),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
    val changedUnits = recall.ordered.updated(
      0,
      recall.ordered.head.copy(
        proposition =
          recall.ordered.head.proposition.copy(cause = Some("changed semantic annotation"))
      )
    )
    val foreign = RecallGraph
      .validated(recall.transcript, recall.atlas, changedUnits, recall.relations)
      .toOption
      .get
    assertEquals(inventoryFor(foreign).digest, inventory.digest)
    assertEquals(
      create(r = foreign, records = Vector(input)),
      Left(WorkspaceRefusal.SemanticJoinMismatch)
    )
  }

  test("selection export copies complete original rows and binds exact text/table/data payloads") {
    val address = workspace.recallAddress(recall.ordered.head.id).get
    val payload = WorkspaceSubsetCodec.selected(workspace, policyId, Set(address)).toOption.get
    val data = Canonical.parse(payload.dataJson).toOption.get
    val receipt = Canonical.parse(payload.receiptJson).toOption.get.hcursor
    val original = MappingCodecs.toJson(record).hcursor.get[Vector[Json]]("outcomes").toOption.get
    assertEquals(data.hcursor.get[Vector[Json]]("outcomes").toOption.get, Vector(original.head))
    assertEquals(data.hcursor.get[String]("original_record_digest").toOption.get, record.digest.hex)
    assertEquals(data.hcursor.get[String]("policy").toOption.get, policyId.value)
    assertEquals(
      receipt.get[String]("data_sha256").toOption.get,
      Checksum.ofText(payload.dataJson).hex
    )
    assertEquals(
      receipt.get[String]("table_sha256").toOption.get,
      Checksum.ofText(payload.tableCsv).hex
    )
    assertEquals(
      receipt.get[String]("text_sha256").toOption.get,
      Checksum.ofText(payload.accessibleText).hex
    )
    assertEquals(receipt.get[Vector[String]]("units").toOption.get, Vector("u0"))
    assert(
      payload.tableCsv.contains("0x3fe3333333333333")
    ) // supplied 0.6, never source-renormalized
    assert(payload.tableCsv.contains("ext:Intrusion"))
    assert(payload.tableCsv.contains("NotSupplied"))
    assert(payload.accessibleText.contains("Two men walked."))
    assert(payload.accessibleText.contains("A canoe arrived."))
    assert(!payload.accessibleText.contains("A note was omitted."))
    assertEquals(
      WorkspaceSubsetCodec.selected(workspace, policyId, Set(address)).toOption.get.dataJson,
      payload.dataJson
    )
  }

  test("source selection exports repeated references; empty selection does not export all") {
    val sourceSelection = Set(workspace.sourceAddress(target).get)
    val exported = WorkspaceSubsetCodec.selected(workspace, policyId, sourceSelection).toOption.get
    val receipt = Canonical.parse(exported.receiptJson).toOption.get.hcursor
    assertEquals(receipt.get[Vector[String]]("units").toOption.get, Vector("u0", "u1"))
    val empty = WorkspaceSubsetCodec.selected(workspace, policyId, Set.empty).toOption.get
    assertEquals(
      Canonical
        .parse(empty.dataJson)
        .toOption
        .get
        .hcursor
        .get[Vector[Json]]("outcomes")
        .toOption
        .get,
      Vector.empty
    )
    assertEquals(empty.tableCsv.linesIterator.size, 1)
  }

  test("a source outside the mapping cut exports its exact evidence without invented rows") {
    val root = source.targets.find(_.level > 0).get.ref
    val exported = WorkspaceSubsetCodec
      .selected(workspace, policyId, Set(workspace.sourceAddress(root).get))
      .toOption
      .get
    assert(exported.accessibleText.contains("Exact source " + root.key))
    assertEquals(
      Canonical
        .parse(exported.dataJson)
        .toOption
        .get
        .hcursor
        .get[Vector[Json]]("outcomes")
        .toOption
        .get,
      Vector.empty
    )
    assertEquals(
      Canonical
        .parse(exported.dataJson)
        .toOption
        .get
        .hcursor
        .get[Vector[Json]]("source_evidence")
        .toOption
        .get
        .size,
      1
    )
  }

  test("foreign addresses and policy IDs cannot silently change an export") {
    val foreign = Address(
      ModuleTag.unsafe("workspace"),
      AddressKind.unsafe("recall-unit"),
      AddressKey.of("foreign")
    )
    assertEquals(
      WorkspaceSubsetCodec.selected(workspace, policyId, Set(foreign)),
      Left(WorkspaceRefusal.InvalidSelection)
    )
    assertEquals(
      WorkspaceSubsetCodec.selected(workspace, ArtifactId.unsafe("unknown"), Set.empty),
      Left(WorkspaceRefusal.IncompatiblePolicy)
    )
  }

  test(
    "failed outcomes remain in a table even when the declared dictionary and externals are empty"
  ) {
    val emptyUniverse = DeclaredUniverse.of(Vector.empty, grain).toOption.get
    val emptyPolicies = MappingPolicies
      .of(
        policies.inference,
        policies.context,
        policies.candidate,
        policies.referencePrior,
        policies.decision,
        emptyUniverse
      )
      .toOption
      .get
    val failures = recall.ordered.map(u =>
      UnitOutcome.failed(u.id, ProcessingFailure.InferenceRefused("synthetic refusal"))
    )
    val failed =
      MappingResult.checked(inventory, source, emptyPolicies, roles, ledger, failures).toOption.get
    val input = WorkspaceMappingInput(ArtifactId.unsafe("empty-dictionary"), failed, None)
    val opened = create(records = Vector(input)).toOption.get
    val exported =
      WorkspaceSubsetCodec.selected(opened, input.id, opened.recallAddresses.keySet).toOption.get
    assertEquals(exported.tableCsv.linesIterator.size, 5)
    assertEquals(
      Canonical
        .parse(exported.receiptJson)
        .toOption
        .get
        .hcursor
        .get[Vector[String]]("units")
        .toOption
        .get,
      Vector("u0", "u1", "u2", "u3")
    )
    assert(exported.tableCsv.contains("NotComputed"))
    assert(!exported.tableCsv.contains("0x0000000000000000"))
  }
