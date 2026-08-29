package storymodel4s.laws

import cats.syntax.all.*
import org.scalacheck.Gen
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*

/** Generators for small in-memory sources and recalls with table-driven semantic distances. */
object AlignGens:
  final case class Case(view: InMemorySourceView, recall: RecallGraph, semantic: SemanticDistance)

  def leaf(i: Int): SourceNodeRef = SourceNodeRef.Situation(SituationId.unsafe(s"e$i"))
  def scene(i: Int): SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe(s"s$i"))
  val root: SourceNodeRef = SourceNodeRef.Segment(SegmentId.unsafe("root"))

  private def summary(
      ref: SourceNodeRef,
      level: Int,
      parent: Option[SourceNodeRef],
      position: Int,
      support: SpanSet,
      predicate: Option[String],
      lemmas: Set[String]
  ): NodeSummary =
    NodeSummary(
      ref,
      level,
      parent,
      position,
      support,
      predicate,
      predicate.toVector.map(_ => ParticipantSummary(SketchRole.Agent, "anna", Set("she"))),
      ContextTag.NarratedWorld,
      PolarityTag.Positive,
      ModalityTag.Asserted,
      Vector.empty,
      lemmas
    )

  /** A chain of 3–6 leaves under two scenes under a root, with discourse, world-time, and one
    * causal edge.
    */
  val view: Gen[InMemorySourceView] =
    Gen.choose(3, 6).map { n =>
      val half = n / 2
      def span(a: Int, b: Int) = SpanSet.one(TextSpan.unsafe(a * 10, b * 10 + 8))
      val preds = Vector("go", "find", "hear", "see", "search", "enter")
      val leaves = (0 until n).toVector.map { i =>
        summary(
          leaf(i),
          0,
          Some(scene(if i < half then 0 else 1)),
          i,
          span(i, i),
          Some(preds(i)),
          Set(preds(i), "anna")
        )
      }
      val scenes = Vector(
        summary(scene(0), 1, Some(root), 0, span(0, half - 1), None, Set.empty),
        summary(scene(1), 1, Some(root), 1, span(half, n - 1), None, Set.empty)
      )
      val rootNode = summary(root, 2, None, 0, span(0, n - 1), None, Set.empty)
      val chain = (0 until n - 1).toVector.map(i => (leaf(i), leaf(i + 1), 1.0))
      InMemorySourceView(
        leaves ++ scenes :+ rootNode,
        Map(
          RelationLayer.DiscourseSuccession -> chain,
          RelationLayer.WorldTime -> chain,
          RelationLayer.Causal -> chain.take(1)
        ),
        Some((0 until n).map(i => leaf(i) -> i).toMap),
        n * 10
      )
    }

  val discourseFunction: Gen[DiscourseFunction] = Gen.oneOf(DiscourseFunction.values.toSeq)

  /** A recall of 1–5 units with random functions, predicates, polarities, and distances. */
  def recall(view: InMemorySourceView): Gen[(RecallGraph, SemanticDistance)] =
    for
      k <- Gen.choose(1, 5)
      functions <- Gen.listOfN(k, discourseFunction)
      preds <- Gen.listOfN(k, Gen.option(Gen.oneOf("go", "find", "hear", "see")))
      polarities <- Gen.listOfN(k, Gen.oneOf(PolarityTag.values.toSeq))
      dists <- Gen.listOfN(k * view.nodes.size, Gen.choose(0.0, 1.0))
    yield
      val text = (0 until k).map(i => s"Unit number $i happened.").mkString(" ")
      val src = StorySource.fromText(text).toOption.get
      val atlas = SurfaceAnalyzer.analyze(src)
      val units = (0 until k).toVector.map { i =>
        val s = atlas.sentences(i)
        RecallUnit(
          RecallUnitId.unsafe(s"u$i"),
          i,
          SpanSet.one(SpanRef(Some(s.id), s.span)),
          atlas.text(s),
          functions(i),
          ExpressedUncertainty.Unmarked,
          PropositionSketch.empty.copy(predicate = preds(i), polarity = polarities(i)),
          None
        )
      }
      val table = (for
        (u, i) <- units.zipWithIndex
        (n, j) <- view.nodes.zipWithIndex
      yield (u.id, n.ref) -> dists(i * view.nodes.size + j)).toMap
      (RecallGraph(src, atlas, units, RecallRelations.empty), SemanticDistance.fromTable(table))

  val alignCase: Gen[Case] =
    for
      v <- view
      (r, s) <- recall(v)
    yield Case(v, r, s)

  def infer(c: Case): HsmmResult = inferWith(c, DefaultLocalCostModel(semantic = c.semantic))

  /** Inference on a case with an explicit cost model (same candidates as [[infer]]). */
  def inferWith(c: Case, model: LocalCostModel): HsmmResult =
    val cands = CandidateGenerator(c.semantic, perLevel = 2).generate(c.recall.ordered, c.view)
    GraphHsmm
      .infer(c.recall, c.view, cands, model)
      .fold(e => throw new IllegalStateException(e.message), identity)

  // ---- forgeries for the gate-proof laws --------------------------------------------------

  /** Gated inference on a foil case (same construction as ModeGateLaws). */
  def inferFoil(f: FoilCase): HsmmResult =
    GraphHsmm
      .infer(f.recall, f.base.view, f.candidates, f.costModel, f.config)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** One forgery = the parts to re-validate: `(candidateAnchors, posterior, flow, viterbi, costs)`.
    */
  type Forgery = (
      Map[RecallUnitId, Vector[SourceNodeRef]],
      AlignmentMatrix,
      TransitionFlow,
      Vector[AlignState],
      Map[RecallUnitId, Map[AlignState, CostBreakdown]]
  )

  /** Re-validate a forgery on the original recall and view (no echo). */
  def revalidate(recall: RecallGraph, view: SourceView, r: HsmmResult, f: Forgery) =
    val (anchors, p, fl, v, cs) = f
    HsmmResult.validated(recall, view, anchors, p, fl, v, r.logLikelihood, cs, r.refinementPasses)

  /** Every single-edit forgery of an inferred result that puts a key (mass, cost entry, flow entry,
    * or Viterbi step) on an `(anchor, mode)` pair the gate does not admit, plus the chief's probe:
    * a key on an anchor of the view that the gate *would* admit faithfully but that was never
    * nominated for the unit (nomination is part of the proof). Built only through the public smart
    * constructors — `Admissibility` cannot be constructed here and is not an input. All must be
    * rejected by [[HsmmResult.validated]] on the original recall and view.
    */
  def forgeries(r: HsmmResult, view: SourceView): Vector[Forgery] =
    val rows = r.posterior.rows
    val spare = cats.data.NonEmptySet.one(Facet.Outcome)
    def inadmissible(unit: RecallUnitId): Vector[AlignState] =
      val admitted =
        r.admissibility.getOrElse(unit, Map.empty).toVector.sortBy(_._1.key).flatMap { (ref, a) =>
          val faithful = if a.faithful then Vector.empty else Vector(AlignState.Source(ref))
          val distorted =
            if a.distortion.contains(spare) then Vector.empty
            else Vector(AlignState.Distorted(ref, spare))
          faithful ++ distorted
        }
      // the chief's probe: nodes of the view never nominated for this unit
      val nominated = r.candidateAnchors.getOrElse(unit, Vector.empty).toSet
      val unnominated =
        view.nodes.map(_.ref).filterNot(nominated.contains).sorted.map(AlignState.Source(_))
      admitted ++ unnominated
    def rowWith(row: AlignmentRow, s: AlignState, m: Double): AlignmentRow =
      // keep the row normalized: give `s` mass `m` and rescale the rest
      val rest = row.mass.toVector.sortBy(_._1.key)
      val total = rest.map(_._2).sum
      val scaled = rest.map { case (k, v) => k -> (if total > 0 then v * (1 - m) / total else v) }
      AlignmentRow
        .of(row.unit, (scaled :+ (s -> m)).toMap)
        .fold(e => throw new IllegalStateException(e.message), identity)
    def matrixWith(i: Int, row: AlignmentRow): AlignmentMatrix =
      AlignmentMatrix
        .of(rows.updated(i, row))
        .fold(e => throw new IllegalStateException(e.message), identity)
    val anchors = r.candidateAnchors
    rows.zipWithIndex.flatMap { (row, i) =>
      inadmissible(row.unit).flatMap { bad =>
        val onPosterior =
          (anchors, matrixWith(i, rowWith(row, bad, 0.0)), r.flow, r.viterbi, r.costs)
        val onPath = (anchors, r.posterior, r.flow, r.viterbi.updated(i, bad), r.costs)
        val onCosts =
          (
            anchors,
            r.posterior,
            r.flow,
            r.viterbi,
            r.costs.updated(
              row.unit,
              r.costs.getOrElse(row.unit, Map.empty).updated(bad, CostBreakdown.unreachable)
            )
          )
        val onFlow = r.flow.steps.zipWithIndex.collect {
          case (st, j) if st.from == row.unit =>
            val other = rows(j + 1).mass.keys.toVector
              .sortBy(_.key)
              .headOption
              .getOrElse(AlignState.unranked)
            val steps = r.flow.steps.updated(j, st.copy(mass = st.mass.updated((bad, other), 0.0)))
            (anchors, r.posterior, TransitionFlow(steps), r.viterbi, r.costs)
        }
        Vector(onPosterior, onPath, onCosts) ++ onFlow
      }
    } ++ recordForgeries(r)

  /** Cross-record forgeries: a cost record whose own mode or exclusion disagrees with the key it
    * sits under — an External key carrying a source-mode record, an anchored key carrying the
    * mode-less external record, a Distorted key carrying a Faithful record, and an admitted key
    * carrying an excluded record. All must be rejected as `MalformedRecord`.
    */
  def recordForgeries(r: HsmmResult): Vector[Forgery] =
    val anchors = r.candidateAnchors
    def withCosts(cs: Map[RecallUnitId, Map[AlignState, CostBreakdown]]): Forgery =
      (anchors, r.posterior, r.flow, r.viterbi, cs)
    r.costs.toVector.sortBy(_._1.value).flatMap { (u, m) =>
      val entries = m.toVector.sortBy(_._1.key)
      val external = entries.collectFirst { case (s @ AlignState.External(_), b) => (s, b) }
      val faithful = entries.collectFirst {
        case (s @ AlignState.Source(_), b) if b.mode.exists(_.isFaithful) => (s, b)
      }
      val distorted = entries.collectFirst { case (s @ AlignState.Distorted(_, _), b) => (s, b) }
      val swapExternal = (external, faithful).mapN { case ((e, _), (_, fb)) =>
        withCosts(r.costs.updated(u, m.updated(e, fb)))
      }
      val swapSource = (external, faithful).mapN { case ((_, eb), (s, _)) =>
        withCosts(r.costs.updated(u, m.updated(s, eb)))
      }
      val swapDistorted = (distorted, faithful).mapN { case ((d, _), (_, fb)) =>
        withCosts(r.costs.updated(u, m.updated(d, fb)))
      }
      val excluded = faithful.map { (s, _) =>
        withCosts(r.costs.updated(u, m.updated(s, CostBreakdown.unreachable)))
      }
      Vector(swapExternal, swapSource, swapDistorted, excluded).flatten
    }

  /** A small checked chart, for evidence toggles in the fingerprint laws. */
  lazy val evidence: storymodel4s.proposition.PropositionEvidence =
    import storymodel4s.proposition.*
    val p = ConceptId.unsafe("p")
    val a = ConceptId.unsafe("a")
    val b = ConceptId.unsafe("b")
    val unchecked = PropositionChart.unchecked(
      Some(p),
      Map(
        p -> Concept.predicate("find"),
        a -> Concept.entity("anna"),
        b -> Concept.entity("brother")
      ),
      Vector(
        PropositionRelation(p, RoleAssignment.arg(0), ConceptTarget.Node(a)),
        PropositionRelation(p, RoleAssignment.arg(1), ConceptTarget.Node(b))
      )
    )
    PropositionEvidence.hand(
      ChartValidator
        .check(unchecked)
        .fold(v => throw new IllegalStateException(v.toString), identity)
    )

  /** Views that must fingerprint equal to `v` (iteration order only) and views that must not (one
    * field of one node, one edge, the world order, or the text length changed).
    */
  def fingerprintVariants(
      v: InMemorySourceView
  ): (Vector[InMemorySourceView], Vector[(String, InMemorySourceView)]) =
    val same = Vector(
      InMemorySourceView(
        v.nodes.reverse,
        v.edges.map((l, es) => l -> es.reverse),
        v.worldOrder.map(_.toVector.reverse.toMap),
        v.textLength
      )
    )
    val target = v.leaves.head
    def withNode(f: NodeSummary => NodeSummary): InMemorySourceView =
      InMemorySourceView(
        v.nodes.map(n => if n.ref == target.ref then f(n) else n),
        v.edges,
        v.worldOrder,
        v.textLength
      )
    val different = Vector(
      "level" -> withNode(n => n.copy(level = n.level + 1)),
      "parent" -> withNode(_.copy(parent = None)),
      "discoursePosition" -> withNode(n => n.copy(discoursePosition = n.discoursePosition + 1)),
      "support" -> withNode(_.copy(support = SpanSet.one(TextSpan.unsafe(0, 1)))),
      "predicate" -> withNode(_.copy(predicate = Some("zzz"))),
      "participants" -> withNode(_.copy(participants = Vector.empty)),
      "context" -> withNode(_.copy(context = ContextTag.Belief)),
      "polarity" -> withNode(_.copy(polarity = PolarityTag.Negative)),
      "modality" -> withNode(_.copy(modality = ModalityTag.Intended)),
      "locations" -> withNode(_.copy(locations = Vector("zzz"))),
      "lemmas" -> withNode(n => n.copy(lemmas = n.lemmas + "zzz")),
      "outcome" -> withNode(_.copy(outcome = Some("zzz"))),
      "cause" -> withNode(_.copy(cause = Some("zzz"))),
      "importance" -> withNode(_.copy(importance = storymodel4s.features.Estimate.observed(0.25))),
      "evidence" -> withNode(_.copy(evidence = Some(evidence))),
      "outcome/cause re-bracketed A" -> withNode(
        _.copy(outcome = Some("o\u0000cause\u0000c"), cause = None)
      ),
      "outcome/cause re-bracketed B" -> withNode(
        _.copy(outcome = Some("o"), cause = Some("c\u0000cause\u0000"))
      ),
      "edge" -> InMemorySourceView(
        v.nodes,
        v.edges.updated(RelationLayer.Goal, Vector((target.ref, root, 1.0))),
        v.worldOrder,
        v.textLength
      ),
      "worldOrder" -> InMemorySourceView(v.nodes, v.edges, None, v.textLength),
      "textLength" -> InMemorySourceView(v.nodes, v.edges, v.worldOrder, v.textLength + 1)
    )
    (same, different)

  // ---- adversarial foil cases for the mode-gate laws (ADR 0001 rev 3 §D5) ------------------

  /** A recall unit built to contradict a chosen leaf on a chosen facet, plus an adversarial
    * configuration: the semantic distance is 0 (cosine 1) on the contradicted leaf, weights and
    * temperature are random, refinement passes are random. Law L1 must hold for all of them.
    */
  final case class FoilCase(
      base: Case,
      target: SourceNodeRef,
      contradiction: Contradiction,
      unit: RecallUnit,
      weights: CostWeights,
      temperature: Double,
      passes: Int
  ):
    def recall: RecallGraph =
      RecallGraph(base.recall.transcript, base.recall.atlas, Vector(unit), RecallRelations.empty)
    def semantic: SemanticDistance =
      SemanticDistance.of((u, n) => if u.id == unit.id && n.ref == target then 0.0 else 0.95)
    def costModel: LocalCostModel = DefaultLocalCostModel(weights = weights, semantic = semantic)
    def config: HsmmConfig = HsmmConfig.unsafe(temperature = temperature, refinementPasses = passes)
    def candidates: Candidates =
      CandidateGenerator(semantic, perLevel = 3).generate(Vector(unit), base.view)

  private val contradictionGen: Gen[Contradiction] = Gen.oneOf(
    Contradiction.RoleReversal,
    Contradiction.PolarityConflict,
    Contradiction.ContextConflict,
    Contradiction.ModalityConflict
  )

  val foilCase: Gen[FoilCase] =
    for
      c <- alignCase
      leaf <- Gen.oneOf(c.view.leaves)
      kind <- contradictionGen
      ws <- Gen.listOfN(6, Gen.choose(0.0, 3.0))
      tau <- Gen.choose(0.02, 2.0)
      passes <- Gen.choose(0, 2)
    yield
      val pred = leaf.predicate
      val anna = SketchParticipant(SketchRole.Agent, None, "anna", aliases = Set("she"))
      val (view, sketch) = kind match
        case Contradiction.RoleReversal =>
          // source: anna (agent) acts on the brother; recall: the brother acts on anna
          val withPatient = c.view.nodes.map(n =>
            if n.ref == leaf.ref then
              n.copy(participants =
                n.participants :+ ParticipantSummary(SketchRole.Patient, "brother", Set("him"))
              )
            else n
          )
          (
            InMemorySourceView(withPatient, c.view.edges, c.view.worldOrder, c.view.textLength),
            PropositionSketch.empty.copy(
              predicate = pred,
              participants = Vector(
                SketchParticipant(SketchRole.Agent, None, "brother", aliases = Set("him")),
                SketchParticipant(SketchRole.Patient, None, "anna", aliases = Set("she"))
              )
            )
          )
        case Contradiction.PolarityConflict =>
          (
            c.view,
            PropositionSketch.empty
              .copy(predicate = pred, participants = Vector(anna), polarity = PolarityTag.Negative)
          )
        case Contradiction.ContextConflict =>
          val speech = c.view.nodes.map(n =>
            if n.ref == leaf.ref then n.copy(context = ContextTag.Speech) else n
          )
          (
            InMemorySourceView(speech, c.view.edges, c.view.worldOrder, c.view.textLength),
            PropositionSketch.empty.copy(
              predicate = pred,
              participants = Vector(anna),
              modality = ModalityTag.Asserted
            )
          )
        case _ =>
          val intended = c.view.nodes.map(n =>
            if n.ref == leaf.ref then n.copy(modality = ModalityTag.Intended) else n
          )
          (
            InMemorySourceView(intended, c.view.edges, c.view.worldOrder, c.view.textLength),
            PropositionSketch.empty.copy(
              predicate = pred,
              participants = Vector(anna),
              modality = ModalityTag.Asserted
            )
          )
      val u0 = c.recall.ordered.head
      val unit = u0.copy(
        id = RecallUnitId.unsafe("foil"),
        function = DiscourseFunction.EpisodicAssertion,
        proposition = sketch.copy(lemmas = pred.toSet + "anna")
      )
      FoilCase(
        c.copy(view = view),
        leaf.ref,
        kind,
        unit,
        CostWeights.unsafe(ws(0), ws(1), ws(2), ws(3), ws(4), ws(5)),
        tau,
        passes
      )

  /** A cost model that records every `(unit, anchor, mode)` it was asked to price. */
  final class SpyCostModel(inner: LocalCostModel) extends LocalCostModel:
    val calls =
      scala.collection.mutable.ArrayBuffer.empty[(RecallUnitId, SourceNodeRef, FidelityMode)]
    def cost(
        unit: RecallUnit,
        node: NodeSummary,
        mode: FidelityMode,
        view: SourceView
    ): CostBreakdown =
      calls += ((unit.id, node.ref, mode))
      inner.cost(unit, node, mode, view)
    def externalFloor: Double = inner.externalFloor
    def externalCost(unit: RecallUnit, state: ExternalState): Double =
      inner.externalCost(unit, state)
