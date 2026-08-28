package storymodel4s.align

import cats.data.NonEmptySet
import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*

/** Additive terms of the local content cost `C_iv` (design record §7.1). `Distortion` is the
  * documented penalty of a [[FidelityMode.Distorted]] state — one unit of cost per contradicted
  * facet, scaled by the contradiction weight — kept separate from graded semantic cost so a
  * faithful anchor is preferred over a distorted one at equal content match.
  */
enum CostTerm:
  case Semantic, Propositional, Entity, Sensory, Granularity, Distortion

final case class CostWeights private (
    semantic: Double,
    propositional: Double,
    entity: Double,
    sensory: Double,
    granularity: Double,
    contradiction: Double
):
  def apply(term: CostTerm): Double = term match
    case CostTerm.Semantic      => semantic
    case CostTerm.Propositional => propositional
    case CostTerm.Entity        => entity
    case CostTerm.Sensory       => sensory
    case CostTerm.Granularity   => granularity
    case CostTerm.Distortion    => contradiction

object CostWeights:
  /** Weights must be finite and nonnegative. */
  def of(
      semantic: Double,
      propositional: Double,
      entity: Double,
      sensory: Double,
      granularity: Double,
      contradiction: Double
  ): Either[AlignError, CostWeights] =
    val all = Vector(semantic, propositional, entity, sensory, granularity, contradiction)
    if all.forall(w => w >= 0.0 && !w.isNaN && !w.isInfinite) then
      Right(new CostWeights(semantic, propositional, entity, sensory, granularity, contradiction))
    else Left(AlignError.InvalidConfig("CostWeights", "weights must be finite and nonnegative"))

  def unsafe(
      semantic: Double,
      propositional: Double,
      entity: Double,
      sensory: Double,
      granularity: Double,
      contradiction: Double
  ): CostWeights =
    of(semantic, propositional, entity, sensory, granularity, contradiction).fold(
      e => throw new IllegalArgumentException(e.message),
      identity
    )

  val default: CostWeights = unsafe(1.0, 0.5, 0.4, 0.15, 0.3, 1.0)

  /** Embedding-only weights for the baseline ablation: no structure. */
  val semanticOnly: CostWeights = unsafe(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

/** Structural incompatibilities that embeddings cannot see. Every contradiction is a *fidelity
  * facet* of the anchor: it makes `(anchor, Faithful)` inadmissible and `(anchor,
  * Distorted(facets))` admissible (ADR 0001 rev 3 §D5). None excludes the anchor itself.
  */
enum Contradiction:
  case RoleReversal, PolarityConflict, ContextConflict, ModalityConflict, OutcomeConflict

  def facet: Facet = this match
    case RoleReversal     => Facet.RoleReversal
    case PolarityConflict => Facet.Polarity
    case ContextConflict  => Facet.Context
    case ModalityConflict => Facet.Modality
    case OutcomeConflict  => Facet.Outcome

/** Why a candidate is not a source state for a unit. Only bookkeeping reasons remain: a recall
  * judgement never removes an anchor.
  */
enum Exclusion:
  /** The candidate reference is not a node of the view. */
  case Unreachable

/** The cost of one admissible `(anchor, mode)` state (or external state) for one unit. */
final case class CostBreakdown(
    terms: Map[CostTerm, Double],
    mode: Option[FidelityMode],
    exclusion: Option[Exclusion],
    total: Double
):
  def term(t: CostTerm): Double = terms.getOrElse(t, 0.0)

  /** Not a source state for this unit (bookkeeping only). */
  def excluded: Boolean = exclusion.nonEmpty

  def isDistorted: Boolean = mode.exists(!_.isFaithful)

  /** Facets contradicted by this state's mode. */
  def facets: Set[Facet] = mode.map(_.facetSet).getOrElse(Set.empty)

  /** Reported content asserted as fact (or vice versa): the `Context` facet. */
  def contextMismatch: Boolean = facets.contains(Facet.Context)

object CostBreakdown:
  def unreachable: CostBreakdown =
    CostBreakdown(Map.empty, None, Some(Exclusion.Unreachable), Double.MaxValue / 4)

/** Graded semantic distance in `[0, 1]` between a recall unit and a source node, or `Missing` when
  * the provider abstains (no embedding for the unit, out-of-domain text, budget exceeded). This is
  * the embedding hook: production supplies cosine distance over feature views; tests supply tables.
  * Missing is never coerced to zero: the cost model substitutes a declared neutral value and
  * candidate generation skips the dense ranking for that pair.
  */
trait SemanticDistance:
  def apply(unit: RecallUnit, node: NodeSummary): Estimate[Double]

  /** The distance, or `default` when the provider abstained. */
  def orElse(unit: RecallUnit, node: NodeSummary, default: Double): Double =
    apply(unit, node).toOption.getOrElse(default)

object SemanticDistance:
  /** Jaccard distance over content lemmas: a dependency-free fallback that never abstains. */
  val lexicalJaccard: SemanticDistance = SemanticDistance.of { (unit, node) =>
    val a = unit.proposition.lemmas
    val b = node.lemmas
    if a.isEmpty && b.isEmpty then 1.0
    else 1.0 - a.intersect(b).size.toDouble / a.union(b).size.toDouble
  }

  /** Table-driven distance with a default for unlisted pairs (simulates embedding output). */
  def fromTable(
      table: Map[(RecallUnitId, SourceNodeRef), Double],
      default: Double = 0.9
  ): SemanticDistance =
    SemanticDistance.of((unit, node) => table.getOrElse((unit.id, node.ref), default))

  /** Table-driven distance that abstains on unlisted pairs. */
  def fromTableOrAbstain(table: Map[(RecallUnitId, SourceNodeRef), Double]): SemanticDistance =
    (unit, node) =>
      table.get((unit.id, node.ref)) match
        case Some(d) => Estimate.observed(d)
        case None    => Estimate.missing(MissingReason.ProviderAbstained)

  /** A provider that always abstains. */
  val abstaining: SemanticDistance =
    (_, _) => Estimate.missing(MissingReason.ProviderAbstained)

  /** Lift a total distance function. */
  def of(f: (RecallUnit, NodeSummary) => Double): SemanticDistance =
    (u, n) => Estimate.observed(f(u, n))

  def apply(f: (RecallUnit, NodeSummary) => Estimate[Double]): SemanticDistance = (u, n) => f(u, n)

private[align] object Names:
  /** Token-level overlap: "young man" and "man" co-refer for the purpose of role checks. Labels are
    * compared after lowercasing and stopword removal.
    */
  def tokens(names: Set[String]): Set[String] =
    names.flatMap(n => Lexical.words(n).filterNot(Lexical.stopwords.contains))
  def overlap(a: Set[String], b: Set[String]): Boolean =
    a.exists(b.contains) || tokens(a).exists(tokens(b).contains)

/** Detects structural contradictions between a recall sketch and a source node summary. Rules are
  * conservative: each fires only when the compared slots are both specified.
  */
object ContradictionDetector:
  def detect(sketch: PropositionSketch, node: NodeSummary): Vector[Contradiction] =
    val predicateMatch = sketch.predicate.exists(p => node.predicate.exists(_ == p))
    val out = Vector.newBuilder[Contradiction]

    // Role reversal: the recalled patient is the source agent (and the recalled agent is not),
    // or the recalled agent is the source patient (and the recalled patient is not).
    val sAgent = sketch.agent
    val sPatient = sketch.patient
    val nAgent = node.agent
    val nPatient = node.patient
    val patientIsNodeAgent = (sPatient, nAgent) match
      case (Some(p), Some(a)) => Names.overlap(p.names, a.names)
      case _                  => false
    val agentIsNodeAgent = (sAgent, nAgent) match
      case (Some(x), Some(a)) => Names.overlap(x.names, a.names)
      case _                  => false
    val agentIsNodePatient = (sAgent, nPatient) match
      case (Some(x), Some(p)) => Names.overlap(x.names, p.names)
      case _                  => false
    val patientIsNodePatient = (sPatient, nPatient) match
      case (Some(x), Some(p)) => Names.overlap(x.names, p.names)
      case _                  => false
    val reversed =
      (patientIsNodeAgent && sAgent.nonEmpty && !agentIsNodeAgent) ||
        (agentIsNodePatient && sPatient.nonEmpty && !patientIsNodePatient)
    val bothInverted = patientIsNodeAgent && agentIsNodePatient
    if bothInverted || (reversed && predicateMatch) then out += Contradiction.RoleReversal

    if predicateMatch && sketch.polarity != PolarityTag.Unknown &&
      node.polarity != PolarityTag.Unknown && sketch.polarity != node.polarity
    then out += Contradiction.PolarityConflict

    if predicateMatch && sketch.modality == ModalityTag.Asserted &&
      node.context != ContextTag.NarratedWorld
    then out += Contradiction.ContextConflict

    val unrealized = Set(ModalityTag.Intended, ModalityTag.Desired, ModalityTag.Counterfactual)
    val modalityConflict =
      (sketch.modality == ModalityTag.Asserted && unrealized.contains(node.modality)) ||
        (unrealized.contains(sketch.modality) && node.modality == ModalityTag.Asserted)
    if predicateMatch && modalityConflict then out += Contradiction.ModalityConflict

    (sketch.outcome, node.outcome) match
      case (Some(a), Some(b)) if predicateMatch && Lexical.lower(a) != Lexical.lower(b) =>
        out += Contradiction.OutcomeConflict
      case _ => ()

    out.result()

  /** Whether the sketch engages the node structurally at all (shares its predicate or contradicts
    * it); used to decide which leaves count when a segment inherits contradictions.
    */
  def engages(sketch: PropositionSketch, node: NodeSummary): Boolean =
    sketch.predicate.exists(p => node.predicate.contains(p)) || detect(sketch, node).nonEmpty

/** Which `(anchor, mode)` pairs a unit may occupy on a candidate node. Decided by [[ModeGate]]
  * before any graded cost is evaluated; `faithful` is false exactly when a contradiction was
  * detected, in which case `distortion` names the contradicted facets and the distorted state is
  * the only admissible mode on that anchor.
  */
final case class Admissibility(
    contradictions: Vector[Contradiction],
    faithful: Boolean,
    distortion: Option[NonEmptySet[Facet]]
):
  /** The admissible modes on this anchor, in a deterministic order. */
  def modes: Vector[FidelityMode] =
    (if faithful then Vector(FidelityMode.Faithful) else Vector.empty) ++
      distortion.toVector.map(FidelityMode.Distorted(_))

  def facets: Set[Facet] = distortion.map(_.toSortedSet.toSet).getOrElse(Set.empty)

  /** The faithful mode was refused. */
  def gated: Boolean = !faithful

object Admissibility:
  val faithfulOnly: Admissibility = Admissibility(Vector.empty, faithful = true, None)

  def of(contradictions: Vector[Contradiction]): Admissibility =
    val distinct = contradictions.distinct
    if distinct.isEmpty then faithfulOnly
    else
      Admissibility(
        distinct,
        faithful = false,
        NonEmptySet.fromSet(
          scala.collection.immutable.SortedSet.from(distinct.map(_.facet))
        )
      )

/** The mode gate (ADR 0001 rev 3 §D5, law L1): decides, per unit and candidate anchor, whether the
  * faithful mode is admissible and which distorted mode replaces it. It is a typed prepass owned by
  * [[GraphHsmm]]; no [[LocalCostModel]] can widen it, and no distance, weight, temperature,
  * candidate channel, or refinement pass can reintroduce a refused mode.
  *
  * A segment refuses the faithful mode only when *every* leaf the sketch engages (same predicate or
  * contradicted) is contradicted; its distorted facets are the union over those leaves. Leaves the
  * sketch does not engage do not vote either way, so a scene stays a valid gist target while one
  * compatible leaf exists.
  */
object ModeGate:
  def assess(unit: RecallUnit, node: NodeSummary, view: SourceView): Admissibility =
    val sketch = unit.proposition
    if node.isLeaf then Admissibility.of(ContradictionDetector.detect(sketch, node))
    else
      val engaged = view
        .leavesUnder(node.ref)
        .flatMap(view.node)
        .filter(ContradictionDetector.engages(sketch, _))
      val reports = engaged.map(ContradictionDetector.detect(sketch, _))
      if engaged.nonEmpty && reports.forall(_.nonEmpty) then Admissibility.of(reports.flatten)
      else Admissibility.faithfulOnly

/** Prior cost added to every *source* candidate according to the unit's discourse function: an
  * association or a task comment is presumptively external, an episodic assertion is not.
  */
final case class FunctionPrior(costs: Map[DiscourseFunction, Double]):
  def apply(f: DiscourseFunction): Double = costs.getOrElse(f, 0.0)

object FunctionPrior:
  val default: FunctionPrior = FunctionPrior(
    Map(
      DiscourseFunction.EpisodicAssertion -> 0.0,
      DiscourseFunction.Summary -> 0.0,
      DiscourseFunction.Inference -> 0.1,
      DiscourseFunction.SourceMonitoring -> 0.3,
      DiscourseFunction.Association -> 0.5,
      DiscourseFunction.Evaluation -> 0.5,
      DiscourseFunction.TaskCommentary -> 0.6,
      DiscourseFunction.Uninterpretable -> 0.6
    )
  )
  val none: FunctionPrior = FunctionPrior(Map.empty)

/** Local cost model: graded content cost for an *admissible* `(anchor, mode)` state and floor cost
  * for external states. Implementations never see an inadmissible pair (law L3) and cannot refuse
  * or admit modes: admissibility is [[ModeGate]]'s alone.
  */
trait LocalCostModel:
  /** Cost of aligning `unit` to `node` in `mode`; `view` lets segment nodes consult their leaves.
    */
  def cost(unit: RecallUnit, node: NodeSummary, mode: FidelityMode, view: SourceView): CostBreakdown

  /** Cost of the external floor: a source candidate must beat this to be preferred. */
  def externalFloor: Double

  /** Cost of assigning `unit` to external state `state`; the state that is natural for the unit's
    * discourse function costs exactly the floor, others cost more.
    */
  def externalCost(unit: RecallUnit, state: ExternalState): Double

object ExternalStates:
  /** The external destination presupposed by a discourse function. */
  def natural(f: DiscourseFunction): ExternalState = f match
    case DiscourseFunction.Association       => ExternalState.Association
    case DiscourseFunction.Evaluation        => ExternalState.Commentary
    case DiscourseFunction.TaskCommentary    => ExternalState.Commentary
    case DiscourseFunction.SourceMonitoring  => ExternalState.Commentary
    case DiscourseFunction.Inference         => ExternalState.SourceConsistentInference
    case DiscourseFunction.Uninterpretable   => ExternalState.Uninterpretable
    case DiscourseFunction.EpisodicAssertion => ExternalState.Intrusion
    case DiscourseFunction.Summary           => ExternalState.Intrusion

final case class DefaultLocalCostModel(
    weights: CostWeights = CostWeights.default,
    semantic: SemanticDistance = SemanticDistance.lexicalJaccard,
    functionPrior: FunctionPrior = FunctionPrior.default,
    externalFloor: Double = 1.0,
    externalMismatch: Double = 0.5,
    missingSemantic: Double = 0.5,
    distortionPenalty: Double = 0.3
) extends LocalCostModel:

  def externalCost(unit: RecallUnit, state: ExternalState): Double =
    if state == ExternalState.Unranked then externalFloor
    else if ExternalStates.natural(unit.function) == state then externalFloor
    else externalFloor + externalMismatch

  def cost(
      unit: RecallUnit,
      node: NodeSummary,
      mode: FidelityMode,
      view: SourceView
  ): CostBreakdown =
    val sketch = unit.proposition
    val dSem = clamp(semantic.orElse(unit, node, missingSemantic))
    val dProp = (sketch.predicate, node.predicate) match
      case (Some(a), Some(b)) => if a == b then 0.0 else 1.0
      case _                  => 0.5
    val specified = sketch.participants.filter(_.specified)
    // Role-aware: a name found in the same role earns full credit, in another role half credit.
    val dEnt =
      if specified.isEmpty then 0.5
      else
        val credit = specified.map { p =>
          if node.byRole(p.role).exists(n => Names.overlap(p.names, n.names)) then 1.0
          else if Names.overlap(p.names, node.allNames) then 0.5
          else 0.0
        }.sum
        1.0 - credit / specified.size.toDouble
    val dSens =
      if sketch.sensoryTerms.isEmpty then 0.0
      else
        val hits = sketch.sensoryTerms.count(t => node.lemmas.contains(Lexical.stem(t)))
        1.0 - hits.toDouble / sketch.sensoryTerms.size.toDouble
    // Preferred abstraction level: summaries want a scene, thematic/evaluative remarks want the
    // global level, predicate-bearing assertions want a leaf, predicate-less ones a scene.
    val preferred = unit.function match
      case DiscourseFunction.Summary                                    => 1
      case DiscourseFunction.Association | DiscourseFunction.Evaluation => view.maxLevel
      case _ if sketch.predicate.isEmpty && sketch.participants.isEmpty => 1
      case _                                                            => 0
    val dGran = math.min(1.0, 0.5 * math.abs(node.level - preferred))
    // The distortion term: `distortionPenalty` per contradicted facet, weighted by
    // `contradiction`. It separates a distorted anchor from a faithful one at equal content
    // match and must stay below the external floor so a well-matched distorted anchor is
    // preferred to intrusion (design record §9). Provisional until W4 calibration.
    val dDist = distortionPenalty * mode.facetSet.size.toDouble
    val terms = Map(
      CostTerm.Semantic -> dSem,
      CostTerm.Propositional -> dProp,
      CostTerm.Entity -> dEnt,
      CostTerm.Sensory -> dSens,
      CostTerm.Granularity -> dGran,
      CostTerm.Distortion -> dDist
    )
    val weighted =
      CostTerm.values.toVector.map(t => weights(t) * terms(t)).sum + functionPrior(unit.function)
    CostBreakdown(terms, Some(mode), None, weighted)

  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))
