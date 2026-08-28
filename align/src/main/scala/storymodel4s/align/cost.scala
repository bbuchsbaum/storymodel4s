package storymodel4s.align

import storymodel4s.features.{Estimate, MissingReason}
import storymodel4s.recall.*

/** Additive terms of the local content cost `C_iv` (design record §7.1). */
enum CostTerm:
  case Semantic, Propositional, Entity, Sensory, Granularity, Contradiction

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
    case CostTerm.Contradiction => contradiction

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

  /** Embedding-only weights for the baseline ablation: no structure, no gating. */
  val semanticOnly: CostWeights = unsafe(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)

/** Structural incompatibilities that embeddings cannot see. The gating ones (`RoleReversal`,
  * `PolarityConflict`, `ModalityConflict`, `OutcomeConflict`) exclude the candidate from the unit's
  * source states: a fully contradicted unit is fully external however many candidates it has.
  * `ContextConflict` is deliberately *not* a gate (review #10): recalling reported content as fact
  * is the canonical Bartlett distortion and must be measured as an aligned unit with a context
  * facet error, not hidden as an intrusion.
  */
enum Contradiction:
  case RoleReversal, PolarityConflict, ContextConflict, ModalityConflict, OutcomeConflict

  def gates: Boolean = this != Contradiction.ContextConflict

/** Why a candidate is not a source state for a unit. */
enum Exclusion:
  /** A gating contradiction was detected. */
  case Contradicted

  /** The candidate reference is not a node of the view (bookkeeping, never a recall judgement). */
  case Unreachable

final case class CostBreakdown(
    terms: Map[CostTerm, Double],
    contradictions: Vector[Contradiction],
    exclusion: Option[Exclusion],
    total: Double
):
  def term(t: CostTerm): Double = terms.getOrElse(t, 0.0)

  /** Excluded because of a gating contradiction. */
  def gated: Boolean = exclusion.contains(Exclusion.Contradicted)

  /** Not a source state for this unit, for any reason. */
  def excluded: Boolean = exclusion.nonEmpty

  /** Reported content asserted as fact (or vice versa); a fidelity facet, not a gate. */
  def contextMismatch: Boolean = contradictions.contains(Contradiction.ContextConflict)

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

/** Local cost model: content cost for source candidates and floor cost for external states. */
trait LocalCostModel:
  /** Cost of aligning `unit` to `node`; `view` lets segment nodes consult their leaves. */
  def cost(unit: RecallUnit, node: NodeSummary, view: SourceView): CostBreakdown

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
    gating: Boolean = true,
    missingSemantic: Double = 0.5
) extends LocalCostModel:

  def externalCost(unit: RecallUnit, state: ExternalState): Double =
    if state == ExternalState.Unranked then externalFloor
    else if ExternalStates.natural(unit.function) == state then externalFloor
    else externalFloor + externalMismatch

  def cost(unit: RecallUnit, node: NodeSummary, view: SourceView): CostBreakdown =
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
    // A segment is a valid gist target as long as some leaf under it is compatible: it inherits a
    // contradiction only when every leaf the sketch engages (same predicate or contradicted) is
    // contradicted (review #9). Leaves the sketch does not engage do not vote either way.
    val contradictions =
      if !gating then Vector.empty
      else if node.isLeaf then ContradictionDetector.detect(sketch, node)
      else
        val engaged = view
          .leavesUnder(node.ref)
          .flatMap(view.node)
          .filter(ContradictionDetector.engages(sketch, _))
        val reports = engaged.map(ContradictionDetector.detect(sketch, _))
        if engaged.nonEmpty && reports.forall(_.exists(_.gates)) then reports.flatten.distinct
        else reports.flatten.filterNot(_.gates).distinct
    val cContra = contradictions.count(_.gates).toDouble
    val terms = Map(
      CostTerm.Semantic -> dSem,
      CostTerm.Propositional -> dProp,
      CostTerm.Entity -> dEnt,
      CostTerm.Sensory -> dSens,
      CostTerm.Granularity -> dGran,
      CostTerm.Contradiction -> cContra
    )
    val weighted =
      CostTerm.values.toVector.map(t => weights(t) * terms(t)).sum + functionPrior(unit.function)
    val gated = contradictions.exists(_.gates)
    val exclusion = if gated then Some(Exclusion.Contradicted) else None
    CostBreakdown(terms, contradictions, exclusion, weighted)

  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))
