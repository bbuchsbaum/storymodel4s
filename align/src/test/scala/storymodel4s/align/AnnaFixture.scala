package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.recall.*

/** The worked example of design record §13: a five-event source and a four-unit recall, plus
  * adversarial foils. Semantic distances are injected from a table that stands in for an embedding
  * model, so the tests exercise the structural machinery, not a lexical heuristic.
  */
object AnnaFixture:
  val storyText: String =
    "Anna arrives at an isolated house. She hears a scream from the cellar. " +
      "She searches upstairs. She enters the cellar. She finds her injured brother."

  val source: StorySource = StorySource.fromText(storyText, Some("Anna")).toOption.get
  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)
  private val sentences = atlas.sentences
  require(sentences.size == 5, s"expected 5 sentences, got ${sentences.size}")

  def sit(n: String): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(n))
  def seg(n: String): SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe(n))

  val e1: SourceNodeRef = sit("e1-arrive")
  val e2: SourceNodeRef = sit("e2-hear")
  val e3: SourceNodeRef = sit("e3-search")
  val e4: SourceNodeRef = sit("e4-enter")
  val e5: SourceNodeRef = sit("e5-find")
  val sc1: SourceNodeRef = seg("sc1-arrival")
  val sc2: SourceNodeRef = seg("sc2-discovery")
  val root: SourceNodeRef = seg("root")

  private def support(i: Int): SpanSet =
    SpanSet.one(SpanRef(Some(sentences(i).id), sentences(i).span))
  private def supportRange(a: Int, b: Int): SpanSet =
    SpanSet.unsafe((a to b).map(i => SpanRef(Some(sentences(i).id), sentences(i).span))*)

  val anna: ParticipantSummary =
    ParticipantSummary(SketchRole.Agent, "Anna", Set("she", "woman", "girl", "her"))
  val brother: ParticipantSummary =
    ParticipantSummary(SketchRole.Patient, "brother", Set("him", "man", "sibling"))

  import ContextTag.NarratedWorld
  import PolarityTag.Positive
  import ModalityTag.Asserted

  val nodes: Vector[NodeSummary] = Vector(
    NodeSummary(
      e1,
      0,
      Some(sc1),
      0,
      support(0),
      Some("arrive"),
      Vector(anna, ParticipantSummary(SketchRole.Destination, "house", Set("house"))),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("house"),
      Set("anna", "arrive", "isolated", "house", "woman", "go", "enter")
    ),
    NodeSummary(
      e2,
      0,
      Some(sc1),
      1,
      support(1),
      Some("hear"),
      Vector(anna, ParticipantSummary(SketchRole.Theme, "scream", Set("noise", "sound"))),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("cellar"),
      Set("anna", "hear", "scream", "cellar", "noise", "sound")
    ),
    NodeSummary(
      e3,
      0,
      Some(sc2),
      2,
      support(2),
      Some("search"),
      Vector(anna),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("upstairs"),
      Set("anna", "search", "upstairs", "look")
    ),
    NodeSummary(
      e4,
      0,
      Some(sc2),
      3,
      support(3),
      Some("enter"),
      Vector(
        anna,
        ParticipantSummary(SketchRole.Destination, "cellar", Set("basement", "downstairs"))
      ),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("cellar", "downstairs"),
      Set("anna", "enter", "cellar", "go", "downstairs", "basement")
    ),
    NodeSummary(
      e5,
      0,
      Some(sc2),
      4,
      support(4),
      Some("find"),
      Vector(anna, brother),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("cellar", "downstairs"),
      Set("anna", "find", "brother", "injured", "cellar", "downstairs"),
      outcome = Some("brother found")
    ),
    NodeSummary(
      sc1,
      1,
      Some(root),
      0,
      supportRange(0, 1),
      None,
      Vector(anna),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("house", "cellar"),
      Set("anna", "arrive", "house", "hear", "scream", "cellar", "noise")
    ),
    NodeSummary(
      sc2,
      1,
      Some(root),
      1,
      supportRange(2, 4),
      None,
      Vector(anna, brother),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("upstairs", "cellar", "house"),
      Set("anna", "search", "enter", "find", "brother", "cellar", "upstairs", "house")
    ),
    NodeSummary(
      root,
      2,
      None,
      0,
      supportRange(0, 4),
      None,
      Vector(anna, brother),
      NarratedWorld,
      Positive,
      Asserted,
      Vector("house"),
      Set("anna", "house", "brother", "cellar", "story")
    )
  )

  private val chain = Vector(e1, e2, e3, e4, e5)
  private def succ(xs: Vector[SourceNodeRef]) =
    xs.sliding(2).collect { case Vector(a, b) => (a, b, 1.0) }.toVector

  val view: InMemorySourceView = InMemorySourceView(
    nodes,
    Map(
      RelationLayer.DiscourseSuccession -> (succ(chain) :+ (sc1, sc2, 1.0)),
      RelationLayer.WorldTime -> (succ(chain) :+ (sc1, sc2, 1.0)),
      RelationLayer.Causal -> Vector((e2, e3, 1.0), (e2, e4, 1.0), (e4, e5, 1.0)),
      RelationLayer.EntityContinuity ->
        (succ(chain) ++ succ(chain).map { case (a, b, w) => (b, a, w) }),
      RelationLayer.Semantic -> Vector((e3, e4, 0.5), (e4, e3, 0.5))
    ),
    Some(Map(e1 -> 0, e2 -> 1, e3 -> 2, e4 -> 3, e5 -> 4, sc1 -> 0, sc2 -> 2, root -> 0)),
    storyText.length
  )

  // ---- the recall ------------------------------------------------------------------------

  val recallText: String =
    "A woman goes into this creepy old house. It felt kind of like a Stephen King story. " +
      "She eventually finds somebody downstairs. Before that there was some kind of noise, I think."

  val transcript: StorySource = StorySource.fromText(recallText).toOption.get
  val rAtlas: SurfaceAtlas = SurfaceAnalyzer.analyze(transcript)
  require(rAtlas.sentences.size == 4, s"expected 4 recall sentences, got ${rAtlas.sentences.size}")

  private def unit(
      i: Int,
      function: DiscourseFunction,
      uncertainty: ExpressedUncertainty,
      sketch: PropositionSketch
  ): RecallUnit =
    val s = rAtlas.sentences(i)
    RecallUnit(
      RecallUnitId.unsafe(s"u$i"),
      i,
      SpanSet.one(SpanRef(Some(s.id), s.span)),
      rAtlas.text(s),
      function,
      uncertainty,
      sketch,
      None
    )

  def she: SketchParticipant =
    SketchParticipant(SketchRole.Agent, None, "she", aliases = Set("anna"))

  val u0: RecallUnit = unit(
    0,
    DiscourseFunction.EpisodicAssertion,
    ExpressedUncertainty.Unmarked,
    PropositionSketch(
      Some("go"),
      Vector(SketchParticipant(SketchRole.Agent, None, "woman")),
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector("house"),
      Vector.empty,
      Vector("creepy"),
      Set("woman", "go", "creepy", "old", "house")
    )
  )
  private val kindOf =
    TextSpan.unsafe(recallText.indexOf("kind of"), recallText.indexOf("kind of") + 7)
  val u1: RecallUnit = unit(
    1,
    DiscourseFunction.Association,
    ExpressedUncertainty.Hedged(SpanSet.one(kindOf)),
    PropositionSketch.empty.copy(lemmas = Set("felt", "stephen", "king", "story"))
  )
  val u2: RecallUnit = unit(
    2,
    DiscourseFunction.EpisodicAssertion,
    ExpressedUncertainty.Unmarked,
    PropositionSketch(
      Some("find"),
      Vector(she, SketchParticipant(SketchRole.Patient, None, "somebody", specified = false)),
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector("downstairs"),
      Vector.empty,
      Vector.empty,
      Set("eventually", "find", "somebody", "downstairs")
    )
  )
  private val iThink =
    TextSpan.unsafe(recallText.indexOf("I think"), recallText.indexOf("I think") + 7)
  val u3: RecallUnit = unit(
    3,
    DiscourseFunction.EpisodicAssertion,
    ExpressedUncertainty.Hedged(SpanSet.one(iThink)),
    PropositionSketch(
      None,
      Vector.empty,
      PolarityTag.Unknown,
      ModalityTag.Possible,
      Vector.empty,
      Vector.empty,
      Vector("noise"),
      Set("noise")
    )
  )

  val recall: RecallGraph = RecallGraph(
    transcript,
    rAtlas,
    Vector(u0, u1, u2, u3),
    RecallRelations(
      Vector(RecallTemporalEdge(u3.id, RecallTemporalRelation.Before, u2.id, None)),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
  )

  /** Stand-in for embedding cosine distances. */
  val table: Map[(RecallUnitId, SourceNodeRef), Double] = Map(
    (u0.id, e1) -> 0.2,
    (u0.id, sc1) -> 0.35,
    (u0.id, e4) -> 0.6,
    (u0.id, root) -> 0.5,
    (u1.id, e1) -> 0.7,
    (u1.id, e2) -> 0.7,
    (u1.id, e3) -> 0.7,
    (u1.id, e4) -> 0.7,
    (u1.id, e5) -> 0.7,
    (u1.id, sc1) -> 0.7,
    (u1.id, sc2) -> 0.7,
    (u1.id, root) -> 0.65,
    (u2.id, e5) -> 0.15,
    (u2.id, e4) -> 0.4,
    (u2.id, sc2) -> 0.35,
    (u2.id, e3) -> 0.6,
    (u3.id, e2) -> 0.2,
    (u3.id, sc1) -> 0.45
  )
  val semantic: SemanticDistance = SemanticDistance.fromTable(table)
  val costModel: DefaultLocalCostModel = DefaultLocalCostModel(semantic = semantic)
  val candidates: Candidates =
    CandidateGenerator(semantic, perLevel = 2).generate(recall.ordered, view)

  // ---- foils ------------------------------------------------------------------------------

  final case class Foil(name: String, recall: RecallGraph, semantic: SemanticDistance):
    val unit: RecallUnit = recall.ordered.head
    def candidates: Candidates =
      CandidateGenerator(semantic, perLevel = 2).generate(recall.ordered, view)
    def costModel: DefaultLocalCostModel = DefaultLocalCostModel(semantic = semantic)

  private def single(
      name: String,
      text: String,
      function: DiscourseFunction,
      sketch: PropositionSketch,
      distances: Map[SourceNodeRef, Double]
  ): Foil =
    val src = StorySource.fromText(text).toOption.get
    val at = SurfaceAnalyzer.analyze(src)
    val s = at.sentences.head
    val id = RecallUnitId.unsafe(s"$name-0")
    val u = RecallUnit(
      id,
      0,
      SpanSet.one(SpanRef(Some(s.id), s.span)),
      text,
      function,
      ExpressedUncertainty.Unmarked,
      sketch,
      None
    )
    val table = distances.map { case (ref, d) => (id, ref) -> d }
    Foil(
      name,
      RecallGraph(src, at, Vector(u), RecallRelations.empty),
      SemanticDistance.fromTable(table)
    )

  val roleReversed: Foil = single(
    "reversed",
    "Somebody found her downstairs.",
    DiscourseFunction.EpisodicAssertion,
    PropositionSketch(
      Some("find"),
      Vector(
        SketchParticipant(SketchRole.Agent, None, "somebody", specified = false),
        SketchParticipant(SketchRole.Patient, None, "her", aliases = Set("anna"))
      ),
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector("downstairs"),
      Vector.empty,
      Vector.empty,
      Set("somebody", "find", "downstairs")
    ),
    Map(e5 -> 0.15, e4 -> 0.6, sc2 -> 0.4)
  )

  val negated: Foil = single(
    "negated",
    "She didn't find anyone downstairs.",
    DiscourseFunction.EpisodicAssertion,
    PropositionSketch(
      Some("find"),
      Vector(she, SketchParticipant(SketchRole.Patient, None, "anyone", specified = false)),
      PolarityTag.Negative,
      ModalityTag.Asserted,
      Vector("downstairs"),
      Vector.empty,
      Vector.empty,
      Set("find", "anyone", "downstairs")
    ),
    Map(e5 -> 0.2, e4 -> 0.7, sc2 -> 0.45)
  )

  val blended: Foil = single(
    "blended",
    "She heard a scream and found her brother in the cellar.",
    DiscourseFunction.EpisodicAssertion,
    PropositionSketch(
      None,
      Vector(she, SketchParticipant(SketchRole.Patient, None, "brother")),
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector("cellar"),
      Vector.empty,
      Vector("scream"),
      Set("hear", "scream", "find", "brother", "cellar")
    ),
    Map(e2 -> 0.25, e5 -> 0.25, sc1 -> 0.5, sc2 -> 0.5)
  )

  val summary: Foil = single(
    "summary",
    "She searched the house and found him.",
    DiscourseFunction.Summary,
    PropositionSketch(
      Some("search"),
      Vector(she, SketchParticipant(SketchRole.Patient, None, "him", aliases = Set("brother"))),
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector("house"),
      Vector.empty,
      Vector.empty,
      Set("search", "house", "find", "him")
    ),
    Map(sc2 -> 0.2, e3 -> 0.4, e5 -> 0.4, root -> 0.5)
  )
