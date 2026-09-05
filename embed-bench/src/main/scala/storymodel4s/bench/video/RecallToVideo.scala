package storymodel4s.bench.video

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import storymodel4s.align.*
import storymodel4s.bench.BenchChannels
import storymodel4s.core.{
  PlaybackInstant,
  PlaybackInterval,
  PresentationAxis,
  SegmentId,
  SituationId,
  SpanSet,
  StorySource,
  TextSpan
}
import storymodel4s.embed.onnx.{OnnxSentenceArtifacts, OnnxSentenceEmbedder, OnnxSentenceModel}
import storymodel4s.recall.{
  Lexical,
  ModalityTag,
  PolarityTag,
  RecallSegmenter,
  RecallUnit,
  RecallUnitId
}

/** The general recall-to-video method: a recall transcript (normally with word timestamps) against
  * any video for which a time-aligned textual description exists, at any granularity, grouped or
  * flat. Nothing in this package knows a particular story or annotation format; adapters (e.g. the
  * Sherlock TSV parser) produce [[TimedSegment]]s and axes, and everything downstream - view
  * construction, nomination, HSMM inference, the report - is shared.
  *
  * The aligner never opens the video: it needs the segments' text and their exact playback
  * coordinates. When no human annotation exists, a machine perception channel producing the same
  * `TimedSegment` stream (with its provenance stated) slots in here unchanged.
  */

/** Where a source node lives in a playable video: one part, one exact playback coordinate. A
  * zero-duration description is an [[MediaLocus.Instant]], never a fabricated interval.
  */
enum MediaLocus:
  case Extent(partId: String, interval: PlaybackInterval)
  case Instant(partId: String, at: PlaybackInstant)

  def part: String = this match
    case Extent(p, _)  => p
    case Instant(p, _) => p

  def startTick: Long = this match
    case Extent(_, iv)  => iv.start
    case Instant(_, at) => at.at

  def endTick: Long = this match
    case Extent(_, iv)  => iv.endExclusive
    case Instant(_, at) => at.at

/** One time-aligned description of a video interval: the general input contract of the pipeline.
  *
  * `ordinal` is the segment's 1-based position in video time and must be unique; `locus` is its
  * playback coordinate when the adapter can supply one; `group` is optional coarse structure
  * (scenes, chapters) - a flat annotation simply leaves it empty and the built view has no level-1
  * nodes. `extraLemmas` carries adapter-known match material beyond the text itself (character
  * names, locations), already stemmed.
  */
final case class TimedSegment(
    ordinal: Int,
    text: String,
    locus: Option[MediaLocus],
    group: Option[TimedSegment.Group] = None,
    extraLemmas: Set[String] = Set.empty,
    locations: Vector[String] = Vector.empty,
    embedText: Option[String] = None,
    lexicalText: Option[String] = None
)

object TimedSegment:
  /** Coarse grouping of segments (a scene, a chapter). Ordinals are 1-based in video order. */
  /** Coarse grouping of segments. `embedText` is the rendering an embedding channel sees; a bare
    * scene label carries almost no content, so an adapter that can say more about a scene should.
    */
  /** `lexicalText` is appended for the lexical index only and is never seen by the encoder.
    *
    * The two channels fail on opposite inputs, which the study measured rather than assumed. A
    * mean-pooled embedding is diluted by vocabulary that recurs across the episode, while a
    * rarity-weighted lexical index prices exactly that vocabulary correctly. So a signal that is
    * long, or that repeats across neighbouring nodes, belongs here rather than in `embedText`.
    */
  final case class Group(
      ordinal: Int,
      label: String,
      embedText: Option[String] = None,
      lexicalText: Option[String] = None
  )

/** Builds the aligner's `SourceView` from timed segments: the general recipe extracted from the
  * Sherlock bridge. The view's text axis is a derived document (segment texts joined by newlines),
  * so lexical and semantic matching run description-language-to-recall-language; every node keeps
  * its exact media locus beside the view. Group hulls are minted on the part's own
  * `PresentationAxis` and only when every member locus sits in one part.
  */
object TimedSourceView:

  /** Node-id rendering, owned by the adapter so IDs stay deterministic and diffable per corpus. */
  final case class Naming(leaf: Int => String, group: Int => String)

  object Naming:
    val default: Naming =
      Naming(n => f"video:seg:$n%05d", n => f"video:group:$n%03d")

  /** The built view plus everything the view's text axis cannot carry.
    *
    * `nodeTexts` is the text an embedding channel should encode per node: the segment's `embedText`
    * when the adapter supplies one, else its `text`; the group label for a group. The builder
    * decides this, not the channel, so every semantic provider sees the same rendering of the same
    * node.
    */
  final case class Built(
      view: InMemorySourceView,
      media: Map[SourceNodeRef, MediaLocus],
      document: String,
      segmentByRef: Map[SourceNodeRef, TimedSegment],
      groupByRef: Map[SourceNodeRef, TimedSegment.Group],
      nodeTexts: Vector[(SourceNodeRef, String)],
      lexicalTexts: Vector[(SourceNodeRef, String)],
      worldOrder: WorldOrderInput
  )

  /** The world-time layer and world order a declaration yields: both present or both absent. */
  private final case class WorldClock(
      edges: Option[Vector[(SourceNodeRef, SourceNodeRef, Double)]],
      order: Option[Map[SourceNodeRef, Int]]
  )

  /** Build the view. `worldOrder` is required: the world clock is whatever the caller declares,
    * never the discourse clock by default (ADR 0013). `Left` only for an `Explicit` rank that does
    * not cover exactly the segments' ordinals.
    */
  def build(
      segments: Vector[TimedSegment],
      worldOrder: WorldOrderInput,
      axes: Map[String, PresentationAxis] = Map.empty,
      naming: Naming = Naming.default
  ): Either[WorldOrderRefusal, Built] =
    val texts = segments.map(_.text)
    val offsets = texts.scanLeft(0)((acc, t) => acc + t.length + 1).init
    val document = texts.mkString("\n")

    def leafRef(ordinal: Int): SourceNodeRef =
      SourceNodeRef.Situation(SituationId.unsafe(naming.leaf(ordinal)))
    def groupRef(ordinal: Int): SourceNodeRef =
      SourceNodeRef.Segment(SegmentId.unsafe(naming.group(ordinal)))

    val leaves = segments.zip(offsets).zipWithIndex.map { case ((seg, offset), i) =>
      val span = TextSpan.unsafe(offset, offset + seg.text.length)
      NodeSummary(
        ref = leafRef(seg.ordinal),
        level = 0,
        parent = seg.group.map(g => groupRef(g.ordinal)),
        discoursePosition = i,
        support = SpanSet.one(span),
        predicate = None,
        participants = Vector.empty,
        context = ContextTag.NarratedWorld,
        polarity = PolarityTag.Unknown,
        modality = ModalityTag.Unknown,
        locations = seg.locations,
        lemmas = Lexical.stemSet(seg.text) ++ seg.extraLemmas
      )
    }

    val segLeaf = segments.zip(leaves)
    val groupsInOrder: Vector[TimedSegment.Group] = segments.flatMap(_.group).distinct
    val groupNodes = groupsInOrder.zipWithIndex.map { case (g, gi) =>
      val members = segLeaf.collect { case (s, n) if s.group.contains(g) => n }
      val start = members.map(_.support.minSpan.start).min
      val end = members.map(_.support.minSpan.endExclusive).max
      NodeSummary(
        ref = groupRef(g.ordinal),
        level = 1,
        parent = None,
        discoursePosition = gi,
        support = SpanSet.one(TextSpan.unsafe(start, end)),
        predicate = None,
        participants = Vector.empty,
        context = ContextTag.NarratedWorld,
        polarity = PolarityTag.Unknown,
        modality = ModalityTag.Unknown,
        locations = members.flatMap(_.locations).distinct,
        lemmas = Lexical.stemSet(g.label) ++ members.flatMap(_.lemmas)
      )
    }

    val succession =
      leaves.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector ++
        groupNodes.sliding(2).collect { case Vector(a, b) => (a.ref, b.ref, 1.0) }.toVector
    // The world clock is what the caller declared. Under `SameAsPresentation` the two clocks
    // coincide by declaration and the WorldTime layer is the discourse succession, exactly as the
    // builder used to assume silently; under `Unknown` the layer and the order are both absent,
    // which every consumer already reads as absence rather than zero; under `Explicit` both come
    // from the supplied rank and from nothing else.
    val worldClock: Either[WorldOrderRefusal, WorldClock] = worldOrder match
      case WorldOrderInput.SameAsPresentation(_) =>
        val presentation: Map[SourceNodeRef, Int] =
          leaves.map(n => n.ref -> n.discoursePosition).toMap ++
            groupsInOrder.map { g =>
              val firstMember = segLeaf.collectFirst {
                case (s, n) if s.group.contains(g) => n.discoursePosition
              }
              groupRef(g.ordinal) -> firstMember.getOrElse(0)
            }.toMap
        Right(WorldClock(Some(succession), Some(presentation)))
      case WorldOrderInput.Unknown(_)             => Right(WorldClock(None, None))
      case WorldOrderInput.Explicit(byOrdinal, _) =>
        explicitClock(byOrdinal, segLeaf, groupsInOrder, groupRef)

    val leafMedia: Map[SourceNodeRef, MediaLocus] =
      segments.flatMap(s => s.locus.map(leafRef(s.ordinal) -> _)).toMap
    val groupMedia: Map[SourceNodeRef, MediaLocus] =
      groupsInOrder.flatMap { g =>
        val loci = segments.filter(_.group.contains(g)).flatMap(_.locus)
        loci.map(_.part).distinct match
          case Vector(partId) =>
            axes.get(partId).flatMap { axis =>
              val start = loci.map(_.startTick).min
              val end = loci.map(_.endTick).max
              if end > start then
                PlaybackInterval
                  .on(axis, start, end)
                  .toOption
                  .map(iv => groupRef(g.ordinal) -> MediaLocus.Extent(partId, iv))
              else None
            }
          case _ => None
      }.toMap

    val segmentByRef = segments.map(s => leafRef(s.ordinal) -> s).toMap
    val groupByRef = groupsInOrder.map(g => groupRef(g.ordinal) -> g).toMap
    // The builder decides the embedded rendering, not the channel. An adapter may supply a richer
    // one than the human-readable `text` when the source carries structured fields the prose omits;
    // `text` still drives the document and the report, so the two never drift apart.
    val nodeTexts: Vector[(SourceNodeRef, String)] =
      segments.map(s => leafRef(s.ordinal) -> s.embedText.getOrElse(s.text)) ++
        groupsInOrder.map(g => groupRef(g.ordinal) -> g.embedText.getOrElse(g.label))
    // What the lexical index reads: the embedded rendering plus anything routed to this channel
    // alone. Equal to `nodeTexts` unless an adapter supplies a lexical-only rendering.
    val lexicalTexts: Vector[(SourceNodeRef, String)] =
      segments.map { s =>
        val base = s.embedText.getOrElse(s.text)
        leafRef(s.ordinal) -> s.lexicalText.fold(base)(extra => s"$base. $extra")
      } ++ groupsInOrder.map { g =>
        val base = g.embedText.getOrElse(g.label)
        groupRef(g.ordinal) -> g.lexicalText.fold(base)(extra => s"$base. $extra")
      }
    worldClock.map { clock =>
      val edges =
        Map(RelationLayer.DiscourseSuccession -> succession) ++
          clock.edges.map(RelationLayer.WorldTime -> _)
      val view = InMemorySourceView(
        nodes = leaves ++ groupNodes,
        edges = edges,
        worldOrder = clock.order,
        textLength = document.length
      )
      Built(
        view,
        leafMedia ++ groupMedia,
        document,
        segmentByRef,
        groupByRef,
        nodeTexts,
        lexicalTexts,
        worldOrder
      )
    }

  /** The world clock an explicit rank yields. Every leaf must be ranked and every ranked ordinal
    * must be a leaf; a group sits at its earliest member's rank (the minimum, not the first
    * presented member, so a group whose opening shot is a flash-forward is not dated by it).
    */
  private def explicitClock(
      byOrdinal: Map[Int, Int],
      segLeaf: Vector[(TimedSegment, NodeSummary)],
      groupsInOrder: Vector[TimedSegment.Group],
      groupRef: Int => SourceNodeRef
  ): Either[WorldOrderRefusal, WorldClock] =
    val ordinals = segLeaf.map(_._1.ordinal)
    val missing = ordinals.filterNot(byOrdinal.contains)
    val unknown = byOrdinal.keys.toVector.filterNot(ordinals.toSet).sorted
    if byOrdinal.isEmpty then Left(WorldOrderRefusal.EmptyRank)
    else if missing.nonEmpty then Left(WorldOrderRefusal.RankMissingLeaves(missing))
    else if unknown.nonEmpty then Left(WorldOrderRefusal.RankNamesUnknownLeaves(unknown))
    else
      val leafRank: Map[SourceNodeRef, Int] =
        segLeaf.map((s, n) => n.ref -> byOrdinal(s.ordinal)).toMap
      val groupRank: Map[SourceNodeRef, Int] = groupsInOrder.map { g =>
        val members = segLeaf.collect { case (s, _) if s.group.contains(g) => byOrdinal(s.ordinal) }
        groupRef(g.ordinal) -> members.min
      }.toMap
      Right(
        WorldClock(
          Some(rankSuccession(leafRank) ++ rankSuccession(groupRank)),
          Some(leafRank ++ groupRank)
        )
      )

  /** Succession over distinct ranks: every node at one rank precedes every node at the next. A tie
    * is a tie; it is never broken by presentation order.
    */
  private def rankSuccession(
      rank: Map[SourceNodeRef, Int]
  ): Vector[(SourceNodeRef, SourceNodeRef, Double)] =
    val tiers = rank
      .groupMap(_._2)(_._1)
      .toVector
      .sortBy(_._1)
      .map((_, refs) => refs.toVector.sortBy(_.key))
    tiers
      .sliding(2)
      .collect { case Vector(a, b) => for x <- a; y <- b yield (x, y, 1.0) }
      .flatten
      .toVector

/** Word-column reader for timestamped recall transcripts (`Words` plus onset columns). */
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

/** Recall-audio timing of each unit, derived from the word onsets the transcript measures.
  *
  * The transcript the segmenter runs on is the words joined by single spaces, so every word owns a
  * computable character span. A unit's `onsetSeconds` is the first measured word onset inside its
  * span, in text order, and `lastWordOnsetSeconds` the last: onsets are all these transcripts
  * measure, so no offset or duration is fabricated, and a unit whose words all lack onsets carries
  * `None`, never a zero that would read as the start of the session.
  */
object RecallTiming:

  final case class UnitTiming(onsetSeconds: Option[Double], lastWordOnsetSeconds: Option[Double])

  /** Character span of each word on the joined transcript; `spans(i)` slices back to `words(i)`. */
  def wordSpans(words: Vector[RecallWordsCsv.RecallWord]): Vector[TextSpan] =
    val starts = words.scanLeft(0)((acc, w) => acc + w.word.length + 1).init
    words.zip(starts).map { case (w, s) => TextSpan.unsafe(s, s + w.word.length) }

  def unitTimings(
      words: Vector[RecallWordsCsv.RecallWord],
      units: Vector[RecallUnit]
  ): Map[RecallUnitId, UnitTiming] =
    val spans = wordSpans(words)
    units.map { unit =>
      val u = unit.minSpan
      val measured = words.zip(spans).collect {
        case (w, s)
            if s.start < u.endExclusive && s.endExclusive > u.start && w.onsetSeconds.nonEmpty =>
          w.onsetSeconds.get
      }
      unit.id -> UnitTiming(measured.headOption, measured.lastOption)
    }.toMap

/** Raw anchor-concentration quantities of one posterior row.
  *
  * All quantities are published raw; no thresholded "confident" boolean exists here, because any
  * cutoff is the consumer's decision, not a measurement. `localizability` keeps
  * [[AlignmentRow.localizability]]'s absence contract: `None` when the unit carries no source mass,
  * never an imputed value.
  */
object AnchorConfidence:

  final case class Row(
      mapAnchorMass: Option[Double],
      runnerUpAnchor: Option[SourceNodeRef],
      runnerUpMass: Option[Double],
      localizability: Option[Double]
  )

  def of(row: AlignmentRow, sourceNodeCount: Int): Row =
    val ranked = row.anchorMass.toVector
      .filter(_._2 > 0.0)
      .sortBy { case (r, m) => (-m, r.key) }
    Row(
      ranked.headOption.map(_._2),
      ranked.lift(1).map(_._1),
      ranked.lift(1).map(_._2),
      row.localizability(sourceNodeCount)
    )

/** The shared orchestration and report: segment the transcript, choose the semantic channel,
  * nominate, infer, and write one TSV row per recall unit pairing recall-audio seconds with
  * playback seconds, anchor confidence beside each anchor.
  */
object RecallToVideo:

  /** The run configuration as rendered into voyage provenance (hashed into `ViewProvenance` by
    * [[VoyageExport.document]]). The world-order declaration is part of it: a run under `Unknown`
    * and a run under `SameAsPresentation` are different derivations even when every number
    * coincides, and the declaration is provenance, not view content, so the view fingerprint does
    * not carry it.
    */
  def provenanceConfig(
      channelLabel: String,
      perLevel: Int,
      lexicalOverlap: Boolean,
      run: RecallOrderControl.LadderRun,
      worldOrder: WorldOrderInput
  ): String =
    // The scale, rung and model come from one `LadderRun`, so the label rendered here cannot
    // disagree with the configuration that produced the numbers. Until ADR 0016 the scale was
    // rendered as 1.0 for a run that used 1.5, so every default run's provenance misdescribed its
    // own prior.
    s"channel=$channelLabel perLevel=$perLevel lexicalOverlap=$lexicalOverlap " +
      s"priorScale=${run.scale} monotone=${MonotoneScene.enabled} " +
      s"fill=${MonotoneScene.fillEnabled} backward=${MonotoneScene.backwardPenalty} " +
      s"forward=${MonotoneScene.forwardPenalty} worldOrder=${worldOrder.render} " +
      s"rung=${run.ladder.label} theta=${run.config.fingerprint.hex} ${run.config.layerUse.render}"

  def run(
      built: TimedSourceView.Built,
      words: Vector[RecallWordsCsv.RecallWord],
      axes: Map[String, PresentationAxis],
      transcriptLabel: String,
      outPath: Path,
      coding: Option[storymodel4s.view.IndependentCoding] = None
  ): Unit =
    val t0 = System.nanoTime()
    // The shuffle control. With a seed set, the recall's sentences are permuted before segmentation
    // and the run measures how much ordering the pipeline produces from a scrambled transcript.
    // Whatever survives was the prior talking, not the recall.
    val rawTranscript = RecallWordsCsv.transcriptText(words)
    val shuffleSeed = sys.env.get("STORYMODEL4S_SHUFFLE_RECALL").flatMap(_.trim.toLongOption)
    def sourceOf(text: String) = StorySource
      .fromText(text, Some(transcriptLabel))
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val transcript = sourceOf(rawTranscript)
    // Under the control the transcript is segmented once, its units permuted, and the result
    // re-segmented, because the units are what the aligner anchors and what the report's rows are.
    val recall = shuffleSeed match
      case None       => RecallSegmenter.segment(transcript)
      case Some(seed) =>
        val asRecalled = RecallSegmenter.segment(transcript)
        RecallSegmenter.segment(
          sourceOf(RecallOrderControl.shuffleUnits(asRecalled.ordered.map(_.text), seed))
        )

    // Semantic channel selection is stated, never inferred: with both artifact variables set the
    // pinned MiniLM encoder runs (checksums verified at open) and the summary prints its
    // identity; otherwise the free lexical baseline runs and says so. The same distance feeds
    // nomination and the cost model, so both see one geometry.
    val neuralArtifacts = for
      model <- sys.env.get("STORYMODEL4S_ONNX_MODEL")
      tokenizer <- sys.env.get("STORYMODEL4S_ONNX_TOKENIZER")
    yield OnnxSentenceArtifacts(Paths.get(model), Paths.get(tokenizer))
    val (baseSemantic, baseChannelLabel, embedderToClose) = neuralArtifacts match
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
    // Lexical re-ranking of that channel, on by default at the weight development data chose.
    //
    // Why it is the default rather than a knob: measured over 11 development participants it is the
    // largest improvement found, +0.0723 Kendall tau against the unblended channel with 9 of 11
    // participants improving, and it survives restriction to units whose anchor granularity did not
    // change, which is where the scene-caption arm's apparent gain went. Concentration is unmoved,
    // as the permutation design requires. The weight is on the semantic side; 0.8 is the interior
    // peak of a broad plateau, and both 0.7 and 0.9 also improve on the unblended channel, so the
    // value is not a knife edge. `STORYMODEL4S_LEXICAL_BLEND=1.0` restores the unblended channel
    // exactly and is checked to reproduce it byte-for-byte.
    val blendAlpha = sys.env
      .get("STORYMODEL4S_LEXICAL_BLEND")
      .map(_.trim)
      .match
        case Some("off") | Some("none") => None
        case Some(raw)                  => raw.toDoubleOption.filter(a => a > 0.0 && a <= 1.0)
        case None                       => Some(0.8)
    val lexicalFields = LexicalBlend.LexicalFields.parse(sys.env.get("STORYMODEL4S_LEXICAL_FIELDS"))

    val (semantic, channelLabel) = blendAlpha match
      case Some(alpha) =>
        (
          LexicalBlend.blended(
            baseSemantic,
            recall.ordered,
            built.view,
            built.lexicalTexts,
            alpha,
            lexicalFields
          ),
          s"$baseChannelLabel + lexical-blend:bm25 alpha=$alpha fields=$lexicalFields"
        )
      case None => (baseSemantic, baseChannelLabel)

    // Candidate nomination. The defaults are the historical values and are what runs unless a
    // caller overrides them: top-8 semantic nominations per hierarchy level, lexical overlap off.
    // The lexical-overlap channel was disabled because with fine-grained segments and recurring
    // names it nominates hundreds of anchors per unit, which is costly for the HSMM; whether it
    // adds ranking information is an empirical question, so it is a knob rather than a constant.
    // Overrides exist for study sweeps on development data and change the report's identity: a
    // different candidate policy is a different derivation, not a tuning of the same one.
    val perLevel = sys.env.get("STORYMODEL4S_CANDIDATES_PER_LEVEL").flatMap(_.toIntOption) match
      case Some(n) if n > 0 => n
      case _                => 8
    val lexicalOverlap = sys.env.get("STORYMODEL4S_CANDIDATES_LEXICAL_OVERLAP").map(_.trim) match
      case Some("true")  => true
      case Some("false") => false
      case _             => false
    val candidates =
      CandidateGenerator(semantic, perLevel = perLevel, lexicalOverlap = lexicalOverlap)
        .generate(recall.ordered, built.view)
    // Strength of the ordering prior, over weights the transition model still marks provisional.
    //
    // 1.5 rather than the shipped 1.0, chosen on development against cross-participant agreement,
    // which is the one outcome a merely more confident model cannot win: two people describing the
    // same moment should be mapped to the same place in the film, and the pairing is computed from
    // recall text alone so no setting can change which units are compared. Agreement traces an
    // inverted U with its peak here, median gap 85.5s against 99.0s at 1.0 and 132.0s unblended,
    // and it is *worse than doing nothing* by scale 8. Concentration and localizability meanwhile
    // rise monotonically all the way out, which is precisely why they could not be trusted to
    // choose this: they measure how peaked the posterior is, not whether it is right.
    //
    // Honest status: development-only. The untouched participants were already spent confirming the
    // lexical blend, so this value has not been checked out of sample, and the sign test that most
    // resists confidence is suggestive rather than significant (116 pairs closer, 95 farther).
    val priorScale =
      sys.env.get("STORYMODEL4S_PRIOR_SCALE").flatMap(_.trim.toDoubleOption).filter(_ >= 0.0)
    val scale = priorScale.getOrElse(1.5)
    // Which rung of the ablation ladder this run is (ADR 0016): a declaration, refused rather
    // than defaulted when a name is unknown, rendered into provenance beside the model's own
    // fingerprint and ledger.
    val ladderRun = RecallOrderControl.Ladder.fromEnv
      .flatMap(ladder => RecallOrderControl.LadderRun.of(ladder, scale))
      .fold(e => throw new IllegalArgumentException(e), identity)
    val hsmmConfig = ladderRun.config
    val result = GraphHsmm
      .infer(recall, built.view, candidates, DefaultLocalCostModel(semantic = semantic), hsmmConfig)
      .fold(e => throw new IllegalStateException(e.message), identity)
    embedderToClose.foreach(_.close())
    val signature = RecallSignature
      .compute(result, recall, built.view)
      .fold(e => throw new IllegalStateException(e.message), identity)
    val timings = RecallTiming.unitTimings(words, recall.ordered)

    // Seconds and timecodes derive from each part's own rational timebase; a part whose axis
    // carries no timebase renders empty cells rather than inventing a scale.
    def scaleOf(part: String): Option[(Long, Long)] =
      axes.get(part).flatMap(_.timebase).map(tb => (tb.scale.numerator, tb.scale.denominator))
    def seconds(part: String, ticks: Long): Option[Double] =
      scaleOf(part).map((n, d) => ticks.toDouble * n / d)
    def timecode(part: String, ticks: Long): Option[String] =
      scaleOf(part).map { (n, d) =>
        val totalMs = ticks * 1000L * n / d
        val h = totalMs / 3600000L
        val m = totalMs % 3600000L / 60000L
        val s = totalMs % 60000L / 1000L
        val ms = totalMs % 1000L
        f"$h%d:$m%02d:$s%02d.$ms%03d"
      }

    def clean(s: String): String = s.replaceAll("[\\t\\n\\r]+", " ").trim

    val header = Vector(
      "unit",
      "function",
      "recallText",
      "recallOnsetSeconds",
      "recallLastWordOnsetSeconds",
      "mapAnchor",
      "mapMode",
      "sourceMass",
      "externalMass",
      "mapAnchorMass",
      "runnerUpAnchor",
      "runnerUpMass",
      "localizability",
      "mediaPart",
      "startSeconds",
      "endSeconds",
      "startTimecode",
      "endTimecode",
      "group",
      "sourceSegmentText"
    ).mkString("\t")

    // Scene-monotone decoding, when asked for: the per-unit argmax discards the fact that recall
    // walks forwards through the story, which the released scene coding puts at 97.9%.
    // Leaves only, so filling a unit never coarsens its anchor to a whole scene: anchoring at the
    // group level was measured earlier to raise ordering metrics for free without localising better.
    lazy val leavesByScene: Map[Int, Vector[SourceNodeRef]] =
      built.segmentByRef.keys.toVector
        .flatMap(ref => MonotoneScene.sceneOf(built, ref).map(_ -> ref))
        .groupMap(_._1)(_._2)
    val fill: (Int, Int) => Option[SourceNodeRef] =
      if !MonotoneScene.fillEnabled then (_, _) => None
      else
        (unitIndex, scene) =>
          val unit = recall.ordered(unitIndex)
          leavesByScene
            .getOrElse(scene, Vector.empty)
            .flatMap { ref =>
              built.view.node(ref).flatMap(n => semantic(unit, n).toOption.map(d => ref -> d))
            }
            .sortBy { case (ref, d) => (d, ref.key) }
            .headOption
            .map(_._1)
    val decisions =
      if MonotoneScene.enabled then MonotoneScene.decide(built, result.posterior.rows, fill)
      else Vector.empty
    val monotoneAnchors = decisions.map(_.anchor)
    val lines =
      recall.ordered.zip(result.posterior.rows).zipWithIndex.map { case ((unit, row), unitIndex) =>
        val anchor =
          if monotoneAnchors.isEmpty then row.mapSource else monotoneAnchors(unitIndex)
        val media = anchor.flatMap(built.media.get)
        val timing = timings.getOrElse(unit.id, RecallTiming.UnitTiming(None, None))
        val confidence = AnchorConfidence.of(row, built.view.nodes.size)
        val groupLabel = anchor
          .flatMap { ref =>
            built.groupByRef
              .get(ref)
              .map(_.label)
              .orElse(built.segmentByRef.get(ref).flatMap(_.group).map(_.label))
          }
          .getOrElse("")
        val description = anchor
          .flatMap(built.segmentByRef.get)
          .map(s => clean(s.text))
          .getOrElse("")
        Vector(
          unit.ordinal.toString,
          unit.function.toString,
          clean(unit.text),
          timing.onsetSeconds.map(_.toString).getOrElse(""),
          timing.lastWordOnsetSeconds.map(_.toString).getOrElse(""),
          anchor.map(_.key).getOrElse(row.argmax.map(_.key).getOrElse("none")),
          row.mapMode.map(_.toString).getOrElse(""),
          f"${row.sourceMass}%.4f",
          f"${row.externalMass}%.4f",
          confidence.mapAnchorMass.map(m => f"$m%.4f").getOrElse(""),
          confidence.runnerUpAnchor.map(_.key).getOrElse(""),
          confidence.runnerUpMass.map(m => f"$m%.4f").getOrElse(""),
          confidence.localizability.map(l => f"$l%.4f").getOrElse(""),
          media.map(_.part).getOrElse(""),
          media.flatMap(l => seconds(l.part, l.startTick)).map(s => f"$s%.1f").getOrElse(""),
          media.flatMap(l => seconds(l.part, l.endTick)).map(s => f"$s%.1f").getOrElse(""),
          media.flatMap(l => timecode(l.part, l.startTick)).getOrElse(""),
          media.flatMap(l => timecode(l.part, l.endTick)).getOrElse(""),
          clean(groupLabel),
          description
        ).mkString("\t")
      }

    Files.write(outPath, (header +: lines).mkString("\n").getBytes(StandardCharsets.UTF_8))

    // The posterior beside the report, content-free: see [[PosteriorSidecar]] for why the report's
    // own mass columns cannot carry it.
    val chosenAnchors = result.posterior.rows.zipWithIndex.map { case (row, i) =>
      if monotoneAnchors.isEmpty then row.mapSource else monotoneAnchors(i)
    }
    val sidecar = PosteriorSidecar.render(
      built,
      recall.ordered.map(_.ordinal),
      result.posterior.rows,
      decisions,
      chosenAnchors
    )
    Files.write(
      Paths.get(outPath.toString + ".posterior.json"),
      sidecar.getBytes(StandardCharsets.UTF_8)
    )

    // The Recall Voyage document (ADR 0002 §14): the proven join a viewer compiles itself.
    val configRendering =
      provenanceConfig(channelLabel, perLevel, lexicalOverlap, ladderRun, built.worldOrder)
    VoyageExport
      .document(
        built,
        recall.ordered,
        timings,
        result,
        chosenAnchors,
        decisions,
        seconds,
        coding,
        configRendering
      )
      .fold(
        e => println(s"voyage document not written: ${e.message}"),
        doc => println(s"voyage: ${VoyageExport.write(outPath, doc)}")
      )

    val elapsedMs = (System.nanoTime() - t0) / 1000000L
    val leafCount = built.view.leaves.size
    val groupCount = built.view.nodes.size - leafCount
    println(s"semantic channel: $channelLabel")
    println(s"candidate policy: perLevel=$perLevel lexicalOverlap=$lexicalOverlap")
    println(s"recall order: ${shuffleSeed.fold("as recalled")(s => s"shuffled seed=$s")}")
    println(s"ordering prior scale: $scale")
    println(s"ladder rung: ${ladderRun.ladder.label}; theta=${hsmmConfig.fingerprint.hex}")
    println(s"layer use: ${hsmmConfig.layerUse.render}")
    println(s"source segments: $leafCount; groups: $groupCount")
    println(s"recall words: ${words.size}; recall units: ${recall.ordered.size}")
    println(s"sparse candidates: ${candidates.totalSize}")
    val anchored = result.posterior.rows.count(_.mapSource.nonEmpty)
    println(s"units with a source anchor: $anchored / ${result.posterior.rows.size}")
    println(f"uniform coverage: ${signature.uniformCoverage}%.4f")
    println(s"specificity: ${signature.specificityMass.render}")
    println(s"external mass: ${signature.externalMass.render}")
    val localizabilities =
      result.posterior.rows
        .flatMap(r => AnchorConfidence.of(r, built.view.nodes.size).localizability)
    if localizabilities.nonEmpty then
      println(
        f"localizability: mean ${localizabilities.sum / localizabilities.size}%.4f over " +
          s"${localizabilities.size}/${result.posterior.rows.size} units with source mass"
      )
    val timed = recall.ordered.flatMap(u => timings.get(u.id).flatMap(_.onsetSeconds))
    println(s"units with a recall-audio onset: ${timed.size} / ${recall.ordered.size}")
    println(s"report: $outPath ($elapsedMs ms)")
