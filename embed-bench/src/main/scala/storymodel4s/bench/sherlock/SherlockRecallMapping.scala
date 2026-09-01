package storymodel4s.bench.sherlock

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import storymodel4s.acquire.SherlockAnnotations
import storymodel4s.acquire.SherlockAnnotations.{Atlas, MediaLocus, Scene}
import storymodel4s.align.*
import storymodel4s.bench.BenchChannels
import storymodel4s.core.{SegmentId, SituationId, SpanSet, StorySource, TextSpan}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}
import storymodel4s.recall.{Lexical, ModalityTag, PolarityTag, RecallSegmenter}

/** Diagnostic bridge from the checked Sherlock annotation atlas to the aligner's `SourceView`.
  *
  * The view's text axis is a *derived annotation document* (the 1000 scene-detail descriptions
  * joined by newlines), not the film: lexical matching runs coder-language-to-recall-language.
  * Every node keeps its exact media locus from the crosswalk beside the view, so an aligned unit
  * reports a playable part/tick/second coordinate. Polarity and modality stay `Unknown` on source
  * nodes: the annotation table asserts what is on screen but was never coded for either, and the
  * cost model's conflict rules fire only between two known tags.
  */
object SherlockAnnotationView:

  /** The built view plus the media coordinates the view's text axis cannot carry.
    *
    * `nodeTexts` is the text an embedding channel should encode per node: the row description for a
    * leaf, the scene label for a scene. The bridge decides this, not the channel, so every semantic
    * provider sees the same rendering of the same node.
    */
  final case class Built(
      view: InMemorySourceView,
      media: Map[SourceNodeRef, MediaLocus],
      document: String,
      rowByRef: Map[SourceNodeRef, Int],
      sceneByRef: Map[SourceNodeRef, Scene],
      nodeTexts: Vector[(SourceNodeRef, String)]
  )

  private def leafRef(row: Int): SourceNodeRef =
    SourceNodeRef.Situation(SituationId.unsafe(f"sherlock:row:$row%04d"))

  private def sceneRef(ordinal: Int): SourceNodeRef =
    SourceNodeRef.Segment(SegmentId.unsafe(f"sherlock:scene:$ordinal%02d"))

  private def stemsOf(parts: Iterable[String]): Set[String] =
    parts.iterator.flatMap(Lexical.stems).toSet

  def build(atlas: Atlas): Built =
    val descriptions = atlas.rows.map(_.description)
    val offsets = descriptions
      .scanLeft(0)((acc, d) => acc + d.length + 1)
      .init
    val document = descriptions.mkString("\n")

    val sceneOf: Map[Int, Scene] =
      atlas.rows
        .map(r => r.row -> atlas.sceneOf(r.row))
        .collect { case (row, Some(s)) =>
          row -> s
        }
        .toMap

    val leaves = atlas.rows.zip(offsets).map { case (row, offset) =>
      val span = TextSpan.unsafe(offset, offset + row.description.length)
      val lemmas =
        Lexical.stemSet(row.description) ++
          stemsOf(row.namesAll) ++
          stemsOf(row.namesSpeaking) ++
          row.location.map(Lexical.stems(_).toSet).getOrElse(Set.empty)
      NodeSummary(
        ref = leafRef(row.row),
        level = 0,
        parent = sceneOf.get(row.row).map(s => sceneRef(s.ordinal)),
        discoursePosition = row.row - 1,
        support = SpanSet.one(span),
        predicate = None,
        participants = Vector.empty,
        context = ContextTag.NarratedWorld,
        polarity = PolarityTag.Unknown,
        modality = ModalityTag.Unknown,
        locations = row.location.map(Lexical.words).getOrElse(Vector.empty),
        lemmas = lemmas
      )
    }
    val leafByRow: Map[Int, NodeSummary] = leaves.map(n => atlasRowOf(n) -> n).toMap

    val sceneNodes = atlas.scenes.map { scene =>
      val members = (scene.firstRow to scene.lastRow).flatMap(leafByRow.get).toVector
      val start = members.map(_.support.minSpan.start).min
      val end = members.map(_.support.minSpan.endExclusive).max
      NodeSummary(
        ref = sceneRef(scene.ordinal),
        level = 1,
        parent = None,
        discoursePosition = scene.ordinal - 1,
        support = SpanSet.one(TextSpan.unsafe(start, end)),
        predicate = None,
        participants = Vector.empty,
        context = ContextTag.NarratedWorld,
        polarity = PolarityTag.Unknown,
        modality = ModalityTag.Unknown,
        locations = members.flatMap(_.locations).distinct,
        lemmas = Lexical.stemSet(scene.label) ++ members.flatMap(_.lemmas)
      )
    }

    val succession =
      leaves.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector ++
        sceneNodes.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector
    val edges = Map(
      RelationLayer.DiscourseSuccession -> succession,
      RelationLayer.WorldTime -> succession
    )
    val worldOrder: Map[SourceNodeRef, Int] =
      leaves.map(n => n.ref -> n.discoursePosition).toMap ++
        atlas.scenes.map(s => sceneRef(s.ordinal) -> (s.firstRow - 1)).toMap

    val view = InMemorySourceView(
      nodes = leaves ++ sceneNodes,
      edges = edges,
      worldOrder = Some(worldOrder),
      textLength = document.length
    )

    val leafMedia: Map[SourceNodeRef, MediaLocus] =
      atlas.rows.flatMap(r => atlas.mediaByRow.get(r.row).map(leafRef(r.row) -> _)).toMap
    val sceneMedia: Map[SourceNodeRef, MediaLocus] =
      atlas.scenes.flatMap { scene =>
        val loci = (scene.firstRow to scene.lastRow).flatMap(atlas.mediaByRow.get).toVector
        val parts = loci.map(_.part).distinct
        parts match
          case Vector(partId) =>
            val bundle =
              if partId == atlas.manifest.partA.partId then atlas.partABundle
              else atlas.partBBundle
            val start = loci.map(_.startTick).min
            val end = loci.map(_.endTick).max
            PlaybackIntervalFor(bundle, start, end).map(sceneRef(scene.ordinal) -> _)
          case _ => None
      }.toMap

    val rowByRef = atlas.rows.map(r => leafRef(r.row) -> r.row).toMap
    val sceneByRef = atlas.scenes.map(s => sceneRef(s.ordinal) -> s).toMap
    val nodeTexts: Vector[(SourceNodeRef, String)] =
      atlas.rows.map(r => leafRef(r.row) -> r.description) ++
        atlas.scenes.map(s => sceneRef(s.ordinal) -> s.label)
    Built(view, leafMedia ++ sceneMedia, document, rowByRef, sceneByRef, nodeTexts)

  private def atlasRowOf(n: NodeSummary): Int = n.discoursePosition + 1

  private def PlaybackIntervalFor(
      bundle: storymodel4s.core.SourceBundle,
      start: Long,
      end: Long
  ): Option[MediaLocus] =
    if end > start then
      storymodel4s.core.PlaybackInterval
        .on(bundle.primaryAxis, start, end)
        .toOption
        .map(iv => MediaLocus.Extent(partIdOf(bundle), iv))
    else None

  private def partIdOf(bundle: storymodel4s.core.SourceBundle): String =
    bundle.edition.map(_.value.stripPrefix("sherlock-nn2017-")).getOrElse("unknown")

/** Word-column reader for the Zenodo Sherlock recall exports (`Words` plus onset columns). */
object RecallWordsCsv:

  final case class RecallWord(word: String, onsetSeconds: Option[Double])

  def parse(content: String): Either[String, Vector[RecallWord]] =
    val lines = content.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines match
      case header +: rows if header.split(',').headOption.exists(_.trim == "Words") =>
        val words = rows.flatMap { line =>
          val cells = line.split(',')
          val w = cells.headOption.map(_.trim).getOrElse("")
          if w.isEmpty then None
          else Some(RecallWord(w, cells.lift(1).flatMap(_.trim.toDoubleOption)))
        }
        if words.isEmpty then Left("no recall words found") else Right(words)
      case _ => Left("expected a header row starting with 'Words'")

  def transcriptText(words: Vector[RecallWord]): String =
    words.map(_.word).mkString(" ")

/** Terminal diagnostic: map one Sherlock recall transcript onto the two media parts.
  *
  * Usage: `sherlockRecallMap <annotation.tsv> <recall.csv> <report.tsv>`. Inputs stay outside Git;
  * the report contains recall text and is therefore local-sensitive (fixture policy 14(d)): it is
  * written wherever the caller points, normally the ignored `tmp/` directory.
  */
@main def sherlockRecallMap(annotationTsv: String, recallCsv: String, outPath: String): Unit =
  val t0 = System.nanoTime()
  val bytes = Files.readAllBytes(Paths.get(annotationTsv))
  val atlas = SherlockAnnotations
    .parse(bytes)
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  val built = SherlockAnnotationView.build(atlas)

  val csv = new String(Files.readAllBytes(Paths.get(recallCsv)), StandardCharsets.UTF_8)
  val words = RecallWordsCsv.parse(csv).fold(e => throw new IllegalArgumentException(e), identity)
  val transcript = StorySource
    .fromText(RecallWordsCsv.transcriptText(words), Some("sherlock-recall"))
    .fold(e => throw new IllegalArgumentException(e.message), identity)
  val recall = RecallSegmenter.segment(transcript)

  // Semantic channel selection is stated, never inferred: with both artifact variables set the
  // pinned MiniLM encoder runs (checksums verified at open) and the summary prints its identity;
  // otherwise the free lexical baseline runs and says so. The same distance feeds nomination and
  // the cost model, so both see one geometry.
  val neuralArtifacts = for
    model <- sys.env.get("STORYMODEL4S_ONNX_MODEL")
    tokenizer <- sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
  yield OnnxSentenceArtifacts(Paths.get(model), Paths.get(tokenizer))
  val (semantic, channelLabel, embedderToClose) = neuralArtifacts match
    case Some(artifacts) =>
      val embedder = OnnxSentenceEmbedder
        .open(OnnxSentenceModel.AllMiniLmL6V2, artifacts)
        .fold(e => throw new IllegalStateException(e.message), identity)
      val channel = BenchChannels
        .neural(embedder, recall.ordered, built.nodeTexts)
        .fold(e => throw new IllegalStateException(e.message), identity)
      (channel.semantic, channel.render, Some(embedder))
    case None =>
      (
        SemanticDistance.lexicalJaccard,
        "lexical-jaccard [semantic=lexical-baseline; free fallback]",
        None
      )
  // The lexical-overlap channel is disabled: with 1000 microsegments and recurring character
  // names it nominates hundreds of anchors per unit, which is intractable for the HSMM and adds
  // no ranking information. Top-k semantic nomination per level keeps the state space sparse.
  val candidates = CandidateGenerator(semantic, perLevel = 8, lexicalOverlap = false)
    .generate(recall.ordered, built.view)
  val result = GraphHsmm
    .infer(recall, built.view, candidates, DefaultLocalCostModel(semantic = semantic))
    .fold(e => throw new IllegalStateException(e.message), identity)
  embedderToClose.foreach(_.close())
  val signature = RecallSignature
    .compute(result, recall, built.view)
    .fold(e => throw new IllegalStateException(e.message), identity)

  def timecode(ticks: Long, tps: Long): String =
    val totalMs = ticks * 1000L / tps
    val h = totalMs / 3600000L
    val m = totalMs % 3600000L / 60000L
    val s = totalMs % 60000L / 1000L
    val ms = totalMs % 1000L
    f"$h%d:$m%02d:$s%02d.$ms%03d"

  def clean(s: String): String = s.replaceAll("[\\t\\n\\r]+", " ").trim

  val tps = atlas.manifest.partA.ticksPerSecond
  val header = Vector(
    "unit",
    "function",
    "recallText",
    "mapAnchor",
    "mapMode",
    "sourceMass",
    "externalMass",
    "mediaPart",
    "startSeconds",
    "endSeconds",
    "startTimecode",
    "endTimecode",
    "scene",
    "annotationDescription"
  ).mkString("\t")

  val lines = recall.ordered.zip(result.posterior.rows).map { case (unit, row) =>
    val anchor = row.mapSource
    val media = anchor.flatMap(built.media.get)
    val sceneLabel = anchor
      .flatMap { ref =>
        built.sceneByRef
          .get(ref)
          .map(_.label)
          .orElse(built.rowByRef.get(ref).flatMap(atlas.sceneOf).map(_.label))
      }
      .getOrElse("")
    val description = anchor
      .flatMap(built.rowByRef.get)
      .flatMap(r => atlas.rows.find(_.row == r))
      .map(r => clean(r.description))
      .getOrElse("")
    Vector(
      unit.ordinal.toString,
      unit.function.toString,
      clean(unit.text),
      anchor.map(_.key).getOrElse(row.argmax.map(_.key).getOrElse("none")),
      row.mapMode.map(_.toString).getOrElse(""),
      f"${row.sourceMass}%.4f",
      f"${row.externalMass}%.4f",
      media.map(_.part).getOrElse(""),
      media.map(l => f"${l.startTick.toDouble / tps}%.1f").getOrElse(""),
      media.map(l => f"${l.endTick.toDouble / tps}%.1f").getOrElse(""),
      media.map(l => timecode(l.startTick, tps)).getOrElse(""),
      media.map(l => timecode(l.endTick, tps)).getOrElse(""),
      clean(sceneLabel),
      description
    ).mkString("\t")
  }

  val out: Path = Paths.get(outPath)
  Files.write(out, (header +: lines).mkString("\n").getBytes(StandardCharsets.UTF_8))

  val elapsedMs = (System.nanoTime() - t0) / 1000000L
  println(s"semantic channel: $channelLabel")
  println(s"annotation rows: ${atlas.rows.size}; scenes: ${atlas.scenes.size}")
  println(
    s"view nodes: ${built.view.nodes.size} (${built.view.leaves.size} microsegments, " +
      s"${atlas.scenes.size} scenes)"
  )
  println(s"recall words: ${words.size}; recall units: ${recall.ordered.size}")
  println(s"sparse candidates: ${candidates.totalSize}")
  val anchored = result.posterior.rows.count(_.mapSource.nonEmpty)
  println(s"units with a source anchor: $anchored / ${result.posterior.rows.size}")
  println(f"uniform coverage: ${signature.uniformCoverage}%.4f")
  println(s"specificity: ${signature.specificityMass.render}")
  println(s"external mass: ${signature.externalMass.render}")
  println(s"report: $out ($elapsedMs ms)")
