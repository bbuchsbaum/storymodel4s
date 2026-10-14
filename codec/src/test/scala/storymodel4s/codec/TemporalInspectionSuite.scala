package storymodel4s.codec {

import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json
import io.circe.syntax.*
import storymodel4s.codec.OutputCodecs.given
import munit.FunSuite
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

/** Ordinary imported claims share arithmetic with live executions but retain separate authority. */
class TemporalInspectionSuite extends FunSuite:
  private def right[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private val csv = TimedSourceArtifactCodec.Header.mkString(",") +
    "\n1,Anna arrived.,a,10,20,1,Scene,,Anna\n2,Bob left.,a,30,40,1,Scene,,Bob\n"
  private val parts = """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  private val artifact = right(TimedSourceArtifactCodec.encode(csv.getBytes(UTF_8), parts.getBytes(UTF_8)))
  private val view = right(TimedSourceArtifactCodec.decode(artifact))
  private val recall = MappingCodecFixture.recall
  private val inventory = MappingCodecFixture.inventory
  private val config = right(StrictCandidateConfig.of(2, TieBudgetRequest.AtMost(4), false, None))
  private val candidates = right(StrictCandidateGenerator.canonicalLevel(StrictSemanticChannel.Lexical,
    config, recall, view, 0))
  private val cost = right(StrictCostModel.of(StrictSemanticChannel.Lexical, weights = CostWeights.semanticOnly,
    functionPrior = FunctionPrior.none, externalMismatch = 0.0, distortionPenalty = 0.0))
  private val evidence = right(LocalEvidence.compute(recall, view, candidates, cost))
  private val universe = right(DeclaredUniverse.of(view.nodes.filter(_.level == 0).map(_.ref), TargetGrain.SingleLevel(0)))
  private val grouping = right(StructuredReconstruction.Grouping.of(view,
    view.nodes.filter(_.parent.isEmpty).sortBy(_.discoursePosition).zipWithIndex.map((n,i) => n.ref -> i)))
  private val execution = right(MappingRun.compute(recall, view, inventory, view.representation, evidence,
    right(LocalReference.Profile.of(universe, 1.0)), StructuredReconstruction.Profile.sherlock(), grouping))
  private val reference = right(execution.referenceRecord)
  private val reconstruction = right(execution.reconstructionRecord)
  private val disclosure = Canonical.print(Json.obj(
    "schemaVersion" -> Json.fromString("recall-disclosure-receipt/v1"),
    "scope" -> Json.fromString("local-workspace"),
    "textDigest" -> Json.fromString(Checksum.ofText(recall.transcript.rawText).hex),
    "basis" -> Json.obj("kind" -> Json.fromString("public-corpus-admitted"))))
  private val workspace = right(SurfaceWorkspaceCodecs.create(artifact,
    SurfaceWorkspaceCodecs.SourceKind.TimedAnnotations, recall, inventory, reference, reconstruction,
    disclosure, "0" * 40, WorkspaceContentGrant.Granted, WorkspaceContentGrant.Granted))
  private val bundle = view.representation.bundles.head.asInstanceOf[BundleEntry.Media].bundle
  private val request = TemporalProjection.Request(TemporalSupport.Selection.Part(bundle.identity),
    view.nodes.map(n => TemporalQuery.Declaration(n.ref, TemporalQuery.Assumption.SuppliedSupportContainsReferent,
      TemporalQuery.Allocation.UniformIntervals)), Vector(10L -> 15L, 15L -> 20L))
  private val referenceId = ArtifactId.unsafe("reference")
  private val measure = TemporalQuery.Measure.NormalizedScoreMass
  private def session(timing: Option[RecallTiming] = None) = right(TemporalInspectionCodecs.checked(workspace, timing))

  test("imported grids agree with actual live paired execution and preserve full original rows") {
    val imported = right(session().compile(referenceId, measure, request))
    val live = right(TemporalProjection.compile(reference, measure, request, None))
    assertEquals(imported.rows.size, inventory.units.size)
    imported.rows.zip(live.rows).foreach { (actual, expected) =>
      assertEquals(actual.processing, expected.processing)
      assertEquals(right(actual.grid).allocated, right(expected.grid).allocated)
      assertEquals(right(actual.grid).bounds, right(expected.grid).bounds)
      assertEquals(right(actual.grid).allocatedOutside, right(expected.grid).allocatedOutside)
      assertEquals(right(actual.grid).allocationDiscrepancy, right(expected.grid).allocationDiscrepancy)
      assertNotEquals(right(actual.grid).digest, right(expected.grid).digest)
    }
    assertEquals(imported.policy.bytes, workspace.policy(referenceId).get.bytes)
    assertEquals(imported.timingState, TemporalProjection.TimingState.NotRequested(false))
    val full = right(session().compile(referenceId, measure, request.copy(bins = Vector(0L -> 100L))))
    val ranked = right(right(full.query(inventory.units.head.id, full.regions.head)).topK(0))
    assertEqualsDouble(ranked.omittedWeight, 1.0, 1e-12)
    assertNotEquals(full.digest, imported.digest)
    val posterior = right(session().compile(ArtifactId.unsafe("reconstruction"), TemporalQuery.Measure.ModelPosterior, request))
    assert(right(posterior.rows.head.grid).prepared.components.forall(_.alternative.isInstanceOf[TemporalQuery.Alternative.Posterior]))
    val unavailable = right(session().compile(referenceId, TemporalQuery.Measure.ModelPosterior, request))
    assertEquals(unavailable.rows.head.grid, Left(TemporalInspectionCodecs.Error.Query(TemporalQuery.Refusal.MissingMeasure)))
  }

  private val clock = RecallTiming.Clock.declared(Checksum.ofText("synthetic-clock"), RecallTiming.ClockKey.unsafe("seconds"),
    Checksum.ofText("synthetic-descriptor"), RecallTiming.RecordingIdentity.Unestablished, RecallTiming.Origin.Unestablished)
  private def sec(n: Long) = right(ExactRational.of(n, 1))
  private def timing(inv: RecallInventory = inventory): RecallTiming = right(RecallTiming.checked(inv, clock,
    inv.words.zipWithIndex.map { (w, i) =>
      val observation = if i == 0 then RecallTiming.Observation.Interval(sec(0), sec(2), RecallTiming.Basis.SourceReported(Checksum.ofText("fixture")))
        else if i == 1 then RecallTiming.Observation.OnsetOnly(sec(3), RecallTiming.Basis.SourceReported(Checksum.ofText("fixture")))
        else RecallTiming.Observation.Missing(RecallTiming.MissingReason.NotProvided)
      RecallTiming.Entry(w.id, clock, observation)
    }, RecallTiming.Provenance(Checksum.ofText("correspondence"), RecallTiming.RecordingLink.NotEstablished)))

  test("original timing attachment and saved view replay bind exact inventory clock and request") {
    val s = session(Some(timing()))
    val bytes = right(TemporalInspectionCodecs.encode(s))
    val reopened = right(TemporalInspectionCodecs.decode(bytes))
    assertEquals(reopened, s)
    val r = request.copy(recallWindow = Some(TemporalProjection.RecallWindow(clock.digest, sec(0), sec(4))))
    val p = right(reopened.compile(referenceId, measure, r))
    assertEquals(p.exposure.get.units.head.duration, BigInt(2) -> BigInt(1))
    assertEquals(p.exposure.get.units.head.onsetEvents, Vector(sec(3)))
    assertEquals(p.exposure.get.units.head.missingWords, 0)
    assertEquals(p.exposure.get.units(1).missingWords, inventory.units(1).words.size)
    val saved = right(TemporalInspectionCodecs.saveView(p))
    assertEquals(TemporalInspectionCodecs.restoreView(saved, reopened), Right(p))
    assertEquals(TemporalInspectionCodecs.restoreView(saved, session()), Left(TemporalInspectionCodecs.Error.ForeignView))
    val stale = saved.replace(p.digest.hex, "0" * 64)
    assertEquals(TemporalInspectionCodecs.restoreView(stale, reopened), Left(TemporalInspectionCodecs.Error.ForeignView))
    assertEquals(reopened.compile(referenceId, measure, r.copy(recallWindow = Some(r.recallWindow.get.copy(clock = Checksum.ofText("foreign"))))),
      Left(TemporalInspectionCodecs.Error.ForeignTiming))
    val foreign = right(RecallInventory.of(recall, inventory.words.map(_.span), WordIdPolicy.inputArtifact(Checksum.ofText("foreign parser"))))
    assertEquals(TemporalInspectionCodecs.checked(workspace, Some(timing(foreign))), Left(TemporalInspectionCodecs.Error.ForeignTiming))
    val absent = right(session().compile(referenceId, measure, r))
    assertEquals(absent.timingState, TemporalProjection.TimingState.Missing)
    assertEquals(absent.exposure, None)
  }

  test("malformed requests refuse even when the requested measure is unavailable") {
    val s = session()
    assert(s.compile(referenceId, TemporalQuery.Measure.ModelPosterior, request.copy(bins = Vector(20L -> 10L))).isLeft)
    assert(s.compile(referenceId, measure, request.copy(bins = Vector(10L -> 20L, 15L -> 30L))).isLeft)
    assert(s.compile(referenceId, measure, request.copy(declarations = request.declarations ++ request.declarations)).isLeft)
    assert(s.compile(referenceId, measure, request.copy(declarations = request.declarations.map(_.copy(allocation = TemporalQuery.Allocation.Mixture(Double.NaN))))).isLeft)
    assertEquals(s.compile(ArtifactId.unsafe("foreign"), measure, request), Left(TemporalInspectionCodecs.Error.ForeignPolicy))
  }

  private def repack(changes: Map[WorkspaceRole, String], exportGrant: WorkspaceContentGrant = WorkspaceContentGrant.Granted): String =
    val manifest = workspace.archive.manifest
    val original = manifest.entries.map(e => e.role -> new String(manifest.bytes(e.role).get.toArray, UTF_8)).toMap
    val content0 = original ++ changes
    val receipt = right(Canonical.parse(content0(WorkspaceRole.Receipt)))
    val members = workspace.policies.map { p =>
      val bytes = content0(WorkspaceRole.Mapping(p.id))
      val record = right(Canonical.parse(bytes))
      Json.obj("id" -> Json.fromString(p.id.value), "bytes" -> Json.fromString(Checksum.ofText(bytes).hex),
        "recordClaim" -> record.hcursor.get[Json]("record_digest").toOption.get)
    }
    val receiptText = Canonical.print(receipt.mapObject(_.add("members", Json.fromValues(members))))
    val caps = WorkspaceArchiveCodec.encodeCapabilities(workspace.archive.capabilities.copy(
      declaration = Checksum.ofText(receiptText), exportPermission = exportGrant))
    val content = content0 ++ Map(WorkspaceRole.Receipt -> receiptText, WorkspaceRole.Capabilities -> caps)
    val entries = manifest.entries.map { e =>
      val old = manifest.artifact(e.role).get
      val ref = ArtifactRef.fromBytes(old.id, old.role, old.mediaType, old.schemaVersion, content(e.role).getBytes(UTF_8))
      e.copy(disposition = WorkspaceDisposition.Supplied(ref))
    }
    val rebuilt = right(WorkspaceManifest.of(manifest.schemaVersion, entries,
      entries.map(e => e.path -> content(e.role).getBytes(UTF_8).toVector).toMap))
    // A consumer can receive denied-export bytes; production encoders correctly refuse to create them.
    val json = right(Canonical.parse(right(workspace.exportBytes)))
    val changedEntries = json.hcursor.get[Vector[Json]]("entries").toOption.get.map { e =>
      val path = e.hcursor.get[String]("path").toOption.get
      val entry = entries.find(_.path.value == path).get
      e.mapObject(_.add("disposition", Json.obj("status" -> Json.fromString("Supplied"),
        "artifact" -> rebuilt.artifact(entry.role).get.asJson)))
    }
    val changedFiles = json.hcursor.get[Vector[Json]]("files").toOption.get.map { f =>
      val path = f.hcursor.get[String]("path").toOption.get
      val entry = entries.find(_.path.value == path).get
      f.mapObject(_.add("utf8", Json.fromString(content(entry.role))))
    }
    Canonical.print(json.mapObject(_.add("entries", Json.fromValues(changedEntries)).add("files", Json.fromValues(changedFiles))))

  test("changed supplied weights with the same claimed record digest cannot reuse a saved view") {
    val policy = workspace.policy(referenceId).get
    val outcomes = policy.outcomes.map { outcome =>
      val links = outcome.hcursor.get[Vector[Json]]("mapping_links").toOption.get
      val values = links.map { link =>
        val key = link.hcursor.get[String]("destination").toOption.get
        val old = CanonicalPrimitives.parseHexDouble(link.hcursor.get[String]("raw_value").toOption.get).get
        key -> ((old + 1.0 / links.size) / 2.0)
      }.toMap
      val changedLinks = links.map(link => link.mapObject(_.add("raw_value", Json.fromString(
        storymodel4s.features.CanonicalDouble.render(values(link.hcursor.get[String]("destination").toOption.get))))))
      val decision = outcome.hcursor.downField("decision").get[Json]("value").toOption.get
      val raw = decision.hcursor.downField("raw_argmax").get[Json]("value").toOption.get
      val chosen = decision.hcursor.downField("decoded_target_id").get[String]("value").toOption.get
      val changedRaw = raw.mapObject(_.add("value", Json.fromString(storymodel4s.features.CanonicalDouble.render(
        values(raw.hcursor.get[String]("destination").toOption.get)))))
      val changedDecision = decision.mapObject(_.add("raw_argmax", Json.obj("status" -> Json.fromString("present"), "value" -> changedRaw))
        .add("decoded_target_mass", decision.hcursor.get[Json]("decoded_target_mass").toOption.get.mapObject(
          _.add("value", Json.fromString(storymodel4s.features.CanonicalDouble.render(values.getOrElse(chosen, 0.0)))))))
      outcome.mapObject(_.add("mapping_links", Json.fromValues(changedLinks))
        .add("decision", Json.obj("status" -> Json.fromString("present"), "value" -> changedDecision)))
    }
    val changedBytes = Canonical.print(policy.document.mapObject(_.add("outcomes", Json.fromValues(outcomes))))
    assertNotEquals(changedBytes, policy.bytes)
    val changedWorkspace = right(SurfaceWorkspaceCodecs.decode(repack(Map(WorkspaceRole.Mapping(referenceId) -> changedBytes))))
    assertEquals(changedWorkspace.policy(referenceId).get.claimedRecordDigest, policy.claimedRecordDigest)
    val original = right(session().compile(referenceId, measure, request))
    val changedSession = right(TemporalInspectionCodecs.checked(changedWorkspace, None))
    val changed = right(changedSession.compile(referenceId, measure, request))
    assertNotEquals(changed.digest, original.digest)
    assertNotEquals(right(changed.rows.head.grid).allocated, right(original.rows.head.grid).allocated)
    assertEquals(TemporalInspectionCodecs.restoreView(right(TemporalInspectionCodecs.saveView(original)), changedSession),
      Left(TemporalInspectionCodecs.Error.ForeignView))
  }

  test("timing attachment cannot widen a workspace export grant") {
    val denied = right(SurfaceWorkspaceCodecs.decode(repack(Map.empty, WorkspaceContentGrant.Denied)))
    val s = right(TemporalInspectionCodecs.checked(denied, Some(timing())))
    val p = right(s.compile(referenceId, measure, request))
    assertEquals(TemporalInspectionCodecs.encode(s), Left(TemporalInspectionCodecs.Error.PermissionDenied))
    assertEquals(TemporalInspectionCodecs.saveView(p), Left(TemporalInspectionCodecs.Error.PermissionDenied))
  }

}

package inspectionprobe {
import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

class TemporalInspectionCodecBoundarySuite extends FunSuite:
  test("TemporalInspectionCodecs.Session refuses constructor outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: SurfaceWorkspaceCodecs.Investigation, v1: Option[RecallTiming], v2: Checksum); new Control(??? : SurfaceWorkspaceCodecs.Investigation, ??? : Option[RecallTiming], ??? : Checksum)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; new TemporalInspectionCodecs.Session(??? : SurfaceWorkspaceCodecs.Investigation, ??? : Option[RecallTiming], ??? : Checksum)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Session refuses apply outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: SurfaceWorkspaceCodecs.Investigation, v1: Option[RecallTiming], v2: Checksum); Control(??? : SurfaceWorkspaceCodecs.Investigation, ??? : Option[RecallTiming], ??? : Checksum)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Session(??? : SurfaceWorkspaceCodecs.Investigation, ??? : Option[RecallTiming], ??? : Checksum)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Session refuses copy outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: SurfaceWorkspaceCodecs.Investigation, v1: Option[RecallTiming], v2: Checksum); (??? : Control).copy()"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; (??? : TemporalInspectionCodecs.Session).copy()""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Session refuses fromProduct outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: SurfaceWorkspaceCodecs.Investigation, v1: Option[RecallTiming], v2: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Session.fromProduct(??? : Product)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Session refuses Mirror outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: SurfaceWorkspaceCodecs.Investigation, v1: Option[RecallTiming], v2: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[TemporalInspectionCodecs.Session]]""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Row refuses constructor outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: RecallUnitId, v1: ProcessingStatus, v2: Json, v3: Either[TemporalInspectionCodecs.Error, TemporalInspection], v4: Option[TemporalExposure.UnitReadout]); new Control(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Json, ??? : Either[TemporalInspectionCodecs.Error, TemporalInspection], ??? : Option[TemporalExposure.UnitReadout])"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; new TemporalInspectionCodecs.Row(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Json, ??? : Either[TemporalInspectionCodecs.Error, TemporalInspection], ??? : Option[TemporalExposure.UnitReadout])""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Row refuses apply outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: RecallUnitId, v1: ProcessingStatus, v2: Json, v3: Either[TemporalInspectionCodecs.Error, TemporalInspection], v4: Option[TemporalExposure.UnitReadout]); Control(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Json, ??? : Either[TemporalInspectionCodecs.Error, TemporalInspection], ??? : Option[TemporalExposure.UnitReadout])"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Row(??? : RecallUnitId, ??? : ProcessingStatus, ??? : Json, ??? : Either[TemporalInspectionCodecs.Error, TemporalInspection], ??? : Option[TemporalExposure.UnitReadout])""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Row refuses copy outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: RecallUnitId, v1: ProcessingStatus, v2: Json, v3: Either[TemporalInspectionCodecs.Error, TemporalInspection], v4: Option[TemporalExposure.UnitReadout]); (??? : Control).copy()"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; (??? : TemporalInspectionCodecs.Row).copy()""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Row refuses fromProduct outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: RecallUnitId, v1: ProcessingStatus, v2: Json, v3: Either[TemporalInspectionCodecs.Error, TemporalInspection], v4: Option[TemporalExposure.UnitReadout]); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Row.fromProduct(??? : Product)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Row refuses Mirror outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: RecallUnitId, v1: ProcessingStatus, v2: Json, v3: Either[TemporalInspectionCodecs.Error, TemporalInspection], v4: Option[TemporalExposure.UnitReadout]); summon[scala.deriving.Mirror.ProductOf[Control]]"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[TemporalInspectionCodecs.Row]]""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Projection refuses constructor outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: TemporalInspectionCodecs.Session, v1: SurfaceWorkspaceCodecs.Policy, v2: TemporalQuery.Measure, v3: TemporalProjection.Request, v4: TemporalSupport.Coordinate, v5: Vector[TemporalQuery.Region], v6: Vector[TemporalInspectionCodecs.Row], v7: Option[TemporalExposure.InspectionReadout], v8: TemporalProjection.TimingState, v9: Checksum); new Control(??? : TemporalInspectionCodecs.Session, ??? : SurfaceWorkspaceCodecs.Policy, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Vector[TemporalInspectionCodecs.Row], ??? : Option[TemporalExposure.InspectionReadout], ??? : TemporalProjection.TimingState, ??? : Checksum)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; new TemporalInspectionCodecs.Projection(??? : TemporalInspectionCodecs.Session, ??? : SurfaceWorkspaceCodecs.Policy, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Vector[TemporalInspectionCodecs.Row], ??? : Option[TemporalExposure.InspectionReadout], ??? : TemporalProjection.TimingState, ??? : Checksum)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Projection refuses apply outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: TemporalInspectionCodecs.Session, v1: SurfaceWorkspaceCodecs.Policy, v2: TemporalQuery.Measure, v3: TemporalProjection.Request, v4: TemporalSupport.Coordinate, v5: Vector[TemporalQuery.Region], v6: Vector[TemporalInspectionCodecs.Row], v7: Option[TemporalExposure.InspectionReadout], v8: TemporalProjection.TimingState, v9: Checksum); Control(??? : TemporalInspectionCodecs.Session, ??? : SurfaceWorkspaceCodecs.Policy, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Vector[TemporalInspectionCodecs.Row], ??? : Option[TemporalExposure.InspectionReadout], ??? : TemporalProjection.TimingState, ??? : Checksum)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Projection(??? : TemporalInspectionCodecs.Session, ??? : SurfaceWorkspaceCodecs.Policy, ??? : TemporalQuery.Measure, ??? : TemporalProjection.Request, ??? : TemporalSupport.Coordinate, ??? : Vector[TemporalQuery.Region], ??? : Vector[TemporalInspectionCodecs.Row], ??? : Option[TemporalExposure.InspectionReadout], ??? : TemporalProjection.TimingState, ??? : Checksum)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Projection refuses copy outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: TemporalInspectionCodecs.Session, v1: SurfaceWorkspaceCodecs.Policy, v2: TemporalQuery.Measure, v3: TemporalProjection.Request, v4: TemporalSupport.Coordinate, v5: Vector[TemporalQuery.Region], v6: Vector[TemporalInspectionCodecs.Row], v7: Option[TemporalExposure.InspectionReadout], v8: TemporalProjection.TimingState, v9: Checksum); (??? : Control).copy()"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; (??? : TemporalInspectionCodecs.Projection).copy()""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Projection refuses fromProduct outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: TemporalInspectionCodecs.Session, v1: SurfaceWorkspaceCodecs.Policy, v2: TemporalQuery.Measure, v3: TemporalProjection.Request, v4: TemporalSupport.Coordinate, v5: Vector[TemporalQuery.Region], v6: Vector[TemporalInspectionCodecs.Row], v7: Option[TemporalExposure.InspectionReadout], v8: TemporalProjection.TimingState, v9: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(??? : Product)"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; TemporalInspectionCodecs.Projection.fromProduct(??? : Product)""").nonEmpty)
  }
  test("TemporalInspectionCodecs.Projection refuses Mirror outside its defining package") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; case class Control(v0: TemporalInspectionCodecs.Session, v1: SurfaceWorkspaceCodecs.Policy, v2: TemporalQuery.Measure, v3: TemporalProjection.Request, v4: TemporalSupport.Coordinate, v5: Vector[TemporalQuery.Region], v6: Vector[TemporalInspectionCodecs.Row], v7: Option[TemporalExposure.InspectionReadout], v8: TemporalProjection.TimingState, v9: Checksum); summon[scala.deriving.Mirror.ProductOf[Control]]"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.core.*; import storymodel4s.recall.*; import storymodel4s.codec.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[TemporalInspectionCodecs.Projection]]""").nonEmpty)
  }
  test("imported queries cannot become live execution or mapping witnesses") {
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; val x: TemporalQuery.Prepared = ???; val m: MappingResult = x.mapping"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; val x: TemporalQuery.Inspection = ???; val m: MappingResult = x.mapping""").nonEmpty)
    assertEquals(typeCheckErrors("""import storymodel4s.align.*; val x: MappingRun.Execution = ???; val y: MappingRun.Execution = x"""), Nil)
    assert(typeCheckErrors("""import storymodel4s.align.*; import storymodel4s.codec.*; val x: TemporalInspectionCodecs.Projection = ???; val y: MappingRun.Execution = x""").nonEmpty)
  }
}
