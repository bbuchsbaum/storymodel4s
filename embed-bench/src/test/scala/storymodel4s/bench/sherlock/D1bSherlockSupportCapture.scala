package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import io.circe.Json
import io.circe.syntax.*
import storymodel4s.align.*
import storymodel4s.codec.CoreCodecs.given
import storymodel4s.core.*
import storymodel4s.corpus.intake.{SherlockAnnotations, SherlockSourceAtlas, TimebaseRepair}
import storymodel4s.recall.RecallSegmenter

/** Content-free exact support inventory. The one-unit synthetic inference tests retention only;
  * it is neither a participant replay nor a scientific alignment result.
  */
object D1bSherlockSupportCapture:
  private def checked[A](value: Either[DomainError, A]): A =
    value.fold(e => throw new IllegalArgumentException(e.message), identity)

  def main(args: Array[String]): Unit =
    require(args.length == 2, "annotation.tsv support-inventory.json")
    val output = Paths.get(args(1))
    require(!Files.exists(output), "refuse to overwrite an earlier capture")
    val annotation = Files.readAllBytes(Paths.get(args(0)))
    val record = checked(TimebaseRepair.loadCommitted())
    val input = checked(SherlockAnnotations.parse(annotation, record))
    val built = checked(SherlockAnnotationView.build(input))
    val library = checked(SherlockSourceAtlas.of(input))
    require(built.sourceAtlas.exists(a => a.bundle.identity == library.atlas.bundle.identity &&
      a.units == library.atlas.units), "view and library proposal atlas differ")
    val bundle = library.atlas.bundle
    val primary = bundle.primaryAxis
    val rowRefs = built.segmentByRef.map((ref, row) => row.ordinal -> ref)
    val sceneRefs = built.groupByRef.map((ref, group) => group.ordinal -> ref)
    val recall = RecallSegmenter.segment(checked(StorySource.fromText("An event occurred.")))
    val nominated = rowRefs(input.rows.head.row)
    val candidates = Candidates(recall.ordered.map(unit => unit.id -> CandidateSet.of(Vector(nominated))).toMap)
    val result = GraphHsmm.infer(recall, built.view, candidates,
      DefaultLocalCostModel(semantic = SemanticDistance.lexicalJaccard))
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    require(result.sourceSupport.keySet == built.view.nodes.map(_.ref).toSet)
    require(library.rows.keySet == input.rows.map(_.row).toSet)
    require(library.scenes.keySet == input.scenes.map(_.ordinal).toSet)

    def capture(unit: NarrativeProposalUnit, ref: SourceNodeRef): Json =
      val node = built.view.node(ref).getOrElse(throw new IllegalStateException("missing node"))
      val support = TypedSupport.Anchored(unit.support)
      require(node.support == support && result.sourceSupport.get(ref).contains(support))
      val projection = checked(unit.support.playbackOn(primary.id))
      val feature = node.scoringPosition match
        case Some(ScoringPosition.LegacyAnnotationText(spans)) => Json.obj(
          "kind" -> "LegacyAnnotationText".asJson,
          "refs" -> spans.refs.toVector.map(ref => Json.obj(
            "unit" -> ref.unit.map(_.value).asJson,
            "start" -> ref.span.start.asJson,
            "endExclusive" -> ref.span.endExclusive.asJson)).asJson)
        case _ => throw new IllegalStateException("missing declared legacy scoring feature")
      Json.obj(
        "ref" -> ref.key.asJson,
        "unit" -> unit.id.value.asJson,
        "physicalIdentity" -> support.identity.hex.asJson,
        "physicalSupport" -> support.asJson,
        "primaryIntervals" -> projection.intervals.map(i =>
          Vector(i.start.toString, i.endExclusive.toString)).asJson,
        "primaryPoints" -> projection.points.map(_.at.toString).asJson,
        "scoringFeature" -> feature,
        "relativeSpan" -> built.view.relativeSpan(ref).asJson,
        "measuredPosition" -> built.view.measuredPosition(ref).asJson,
        "retainedInResult" -> true.asJson,
        "nominated" -> result.candidateAnchors.valuesIterator.flatten.contains(ref).asJson)

    val rows = library.rows.toVector.sortBy(_._1).map((row, unit) =>
      capture(unit, rowRefs(row)).mapObject(_.add("row", row.asJson)))
    val scenes = library.scenes.toVector.sortBy(_._1).map((scene, unit) =>
      capture(unit, sceneRefs(scene)).mapObject(_.add("scene", scene.asJson)))
    val axes = Vector(input.manifest.partA -> input.partABundle,
      input.manifest.partB -> input.partBBundle).map((part, source) => Json.obj(
      "part" -> part.partId.asJson,
      "ordinal" -> part.presentationOrdinal.asJson,
      "axis" -> source.primaryAxis.id.value.asJson,
      "ticksPerSecond" -> part.ticksPerSecond.toString.asJson,
      "durationTicks" -> part.durationTicks.toString.asJson))
    val document = Json.obj(
      "schema" -> "d1b/sherlock-support-inventory/v1".asJson,
      "annotationSha256" -> Checksum.ofBytes(annotation).hex.asJson,
      "repairRecordSha256" -> record.checksum.hex.asJson,
      "bundleIdentity" -> bundle.identity.hex.asJson,
      "viewFingerprint" -> built.view.contentFingerprint.checksum.hex.asJson,
      "scoringLength" -> built.view.scoringLength.asJson,
      "primaryAxis" -> primary.id.value.asJson,
      "primaryTicksPerSecond" -> primary.timebase.get.scale.denominator.toString.asJson,
      "nativeAxes" -> axes.asJson,
      "rows" -> rows.asJson,
      "scenes" -> scenes.asJson,
      "resultInventorySize" -> result.sourceSupport.size.asJson,
      "inferencePurpose" -> "synthetic one-unit retention control; only first row nominated".asJson,
      "runtime" -> Vector("java.version", "java.vendor", "os.name", "os.arch", "os.version")
        .map(key => key -> System.getProperty(key)).toMap.asJson)
    val _ = Files.write(output, document.noSpaces.getBytes(StandardCharsets.UTF_8))
