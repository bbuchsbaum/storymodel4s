package storymodel4s.bench

import cats.data.NonEmptyVector

import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.StorySource
import storymodel4s.fixtures.wog.{WarOfTheGhostsExpectations, WarOfTheGhostsModel}
import storymodel4s.recall.*
import storymodel4s.story.NarrativeNodeId

/** *The War of the Ghosts* wired as **diagnostic** cases only.
  *
  * Why diagnostic: WOG is the narrative acceptance fixture (AGENTS.md §14c) and the manifest
  * excludes it from every partition — it never selects a default and never calibrates anything. Its
  * recall paraphrases carry hand-declared targets, which makes it the right regression material for
  * the metric code paths (source anchors, distortions, blends, externals, inference).
  */
object WogDiagnostic:
  import WarOfTheGhostsExpectations.*

  /** Forces the story model before anything touches the fixture's nested id objects: initializing
    * `WarOfTheGhostsExpectations` first enters `WarOfTheGhostsModel.S`/`G` before the outer object,
    * whose `meta` the nested ids call back into, and the JVM's recursive-init rule then yields null
    * ids ("2 claims share id … null"). Touching `model` first breaks the cycle; the fixture itself
    * should be made order-independent (flagged on the board, outside this bead's paths).
    */
  val story: StorySource = { val _ = WarOfTheGhostsModel.model; WarOfTheGhostsModel.source }

  val storyId: String = "wog-boas-1901"
  val family: String = "F1 folktale/oral (regression only)"
  val origin: Origin =
    Origin.Diagnostic("fixtures:wog", "narrative acceptance fixture; excluded from every partition")

  private def ref(n: NarrativeNodeId): SourceNodeRef = n match
    case NarrativeNodeId.Situation(id) => SourceNodeRef.Situation(id)
    case NarrativeNodeId.Segment(id)   => SourceNodeRef.Segment(id)

  val view: StorySourceView = StorySourceView.validated(WarOfTheGhostsModel.model)

  private def target(view: SourceView, n: NarrativeNodeId): Option[GoldTarget] =
    val r = ref(n)
    view.node(r).map(s => GoldTarget(r, s.level))

  /** The reversed telling events the role-swapped foil contradicts (the alignment suite's
    * `tellingEvents`), declared as a distorted gold with the `RoleReversal` facet: distortion is
    * anchored recall, not omission (ADR 0001 §D5).
    */
  private def tellingEvents(view: SourceView): Vector[GoldTarget] =
    Vector(
      WarOfTheGhostsModel.S.theySaidShot,
      WarOfTheGhostsModel.S.warriorsSayGoHome,
      WarOfTheGhostsModel.S.warriorsSpeak
    ).flatMap(id => target(view, NarrativeNodeId.Situation(id)))

  /** Gold for one paraphrase, in the vocabulary of the adjudication protocol §3. */
  private def goldFor(view: SourceView, unit: RecallUnitId, p: RecallParaphrase): GoldUnit =
    val targets = p.targets.flatMap(target(view, _))
    p.kind match
      case ParaphraseKind.RoleSwappedFoil =>
        GoldUnit.anchored(
          unit,
          NonEmptyVector.fromVectorUnsafe(tellingEvents(view)),
          facets = Set(Facet.RoleReversal)
        )
      case ParaphraseKind.NegatedFoil =>
        GoldUnit.anchored(
          unit,
          NonEmptyVector.fromVectorUnsafe(
            target(view, NarrativeNodeId.Situation(WarOfTheGhostsModel.S.notFeelSick)).toVector
          ),
          facets = Set(Facet.Polarity)
        )
      case ParaphraseKind.ExternalAssociation =>
        GoldUnit.external(
          unit,
          Groundedness.Association,
          Some(ExternalState.Association),
          DiscourseFunction.Association
        )
      case ParaphraseKind.Inference =>
        GoldUnit(
          unit,
          targets,
          targets.headOption,
          Set.empty,
          Groundedness.Inference,
          Some(ExternalState.SourceConsistentInference),
          DiscourseFunction.Inference,
          blend = false
        )
      case ParaphraseKind.Blended =>
        GoldUnit.anchored(unit, NonEmptyVector.fromVectorUnsafe(targets), blend = true)
      case ParaphraseKind.Summary =>
        GoldUnit.anchored(unit, NonEmptyVector.fromVectorUnsafe(targets), DiscourseFunction.Summary)
      case _ =>
        GoldUnit.anchored(unit, NonEmptyVector.fromVectorUnsafe(targets))

  /** One idea unit per paraphrase: the baseline segmenter may split on connectives, and the
    * expectations are stated per statement (as the alignment suite does).
    */
  private def oneUnit(kind: ParaphraseKind, text: String): RecallGraph =
    val transcript = StorySource.fromText(text, Some(kind.toString)).toOption.get
    val recall = RecallSegmenter.segment(transcript)
    if recall.units.size == 1 then recall
    else
      val all = recall.ordered
      val first = all.head
      val unit = first.copy(
        span = all.map(_.span).reduce(_ ++ _),
        text = all.map(_.text).mkString(" "),
        proposition = all.map(_.proposition).reduce(mergeSketch)
      )
      RecallGraph(recall.transcript, recall.atlas, Vector(unit), RecallRelations.empty)

  private def mergeSketch(a: PropositionSketch, b: PropositionSketch): PropositionSketch =
    PropositionSketch(
      a.predicate.orElse(b.predicate),
      (a.participants ++ b.participants).distinct,
      if a.polarity == PolarityTag.Unknown then b.polarity else a.polarity,
      if a.modality == ModalityTag.Unknown then b.modality else a.modality,
      (a.locations ++ b.locations).distinct,
      (a.times ++ b.times).distinct,
      (a.sensoryTerms ++ b.sensoryTerms).distinct,
      a.lemmas ++ b.lemmas,
      a.outcome.orElse(b.outcome),
      a.cause.orElse(b.cause)
    )

  /** One single-unit case per paraphrase. */
  lazy val paraphraseCases: Vector[BenchCase] =
    recallParaphrases.map { p =>
      val recall = oneUnit(p.kind, p.text)
      val unit = recall.ordered.head
      val gold = Gold
        .validated(Vector(goldFor(view, unit.id, p)), view)
        .fold(e => throw new IllegalStateException(e.message), identity)
      BenchCase(
        s"wog:${p.kind}",
        storyId,
        family,
        origin,
        story,
        view,
        recall,
        gold
      )
    }

  /** All paraphrases as one recall, in their declared order: a multi-unit trajectory for the
    * three-clock panels. Each segmented unit inherits the gold of the paraphrase whose text
    * contains its span (a paraphrase the segmenter splits yields two units with the same gold).
    */
  lazy val fullRecallCase: BenchCase =
    val text = recallParaphrases.map(_.text).mkString(" ")
    val transcript = StorySource.fromText(text, Some("wog-recall")).toOption.get
    val recall = RecallSegmenter.segment(transcript)
    val offsets = recallParaphrases
      .foldLeft((Vector.empty[(RecallParaphrase, Int, Int)], 0)) { case ((acc, pos), p) =>
        val start = transcript.canonicalText.indexOf(p.text, pos)
        val s = if start < 0 then pos else start
        (acc :+ (p, s, s + p.text.length), s + p.text.length)
      }
      ._1
    val golds = recall.ordered.flatMap { u =>
      val mid = (u.minSpan.start + u.minSpan.endExclusive) / 2
      offsets.find { case (_, s, e) => mid >= s && mid < e }.map { case (p, _, _) =>
        goldFor(view, u.id, p)
      }
    }
    val gold =
      Gold.validated(golds, view).fold(e => throw new IllegalStateException(e.message), identity)
    BenchCase("wog:full-recall", storyId, family, origin, story, view, recall, gold)

  lazy val cases: Vector[BenchCase] = paraphraseCases :+ fullRecallCase

  /** Node texts for a semantic channel, sliced from the story's canonical text. */
  def nodeTexts(c: BenchCase): Vector[(SourceNodeRef, String)] =
    c.view.nodes.flatMap(n => c.nodeText(n.ref).map(t => (n.ref, t)))

  /** The free channels over one case: hashed n-gram and TF-IDF, each with grakern `d_wl` when the
    * view carries charts (WOG carries none, so the structural side reports itself absent).
    */
  def factories(dimension: Int = 512, seed: Long = 0L): Vector[Bench.ChannelFactory] =
    Vector(
      Bench.ChannelFactory(
        "hashed-ngram",
        c =>
          for
            structural <- BenchChannels.grakern(c.view.nodes)
            ch <- BenchChannels
              .hashedNgram(c.recall.ordered, nodeTexts(c), dimension, seed, structural)
          yield ch
      ),
      Bench.ChannelFactory(
        "tfidf",
        c =>
          val nodes = nodeTexts(c)
          for
            structural <- BenchChannels.grakern(c.view.nodes)
            ch <- BenchChannels.tfIdf(
              c.recall.ordered,
              nodes,
              nodes.map(_._2) ++ c.recall.ordered.map(_.text),
              structural
            )
          yield ch
      )
    )
