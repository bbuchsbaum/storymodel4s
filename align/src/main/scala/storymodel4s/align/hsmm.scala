package storymodel4s.align

import storymodel4s.core.Checksum
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Typed transition features between source states. Each has a weight θ. Source→source moves are a
  * softmax of `Σ_k θ_k φ_k(s, t)` over the source states available at the next unit; `ExternalIn`
  * and `ExternalStay` are logits of leaving / remaining outside the source, mixed in *before* that
  * normalization so the chance of going external does not depend on how many attractive source
  * moves exist. Features are computed on anchors, so a distorted state moves like its anchor.
  */
enum TransitionKind:
  case Stay, DiscourseSuccessor, WorldTimeSuccessor, CausalNeighbor, HierarchyUp, HierarchyDown,
    SameEntityThread, SemanticNeighbor, Backward, LongJump, ExternalIn, ExternalStay

final case class TransitionModel(theta: Map[TransitionKind, Double]):
  def apply(k: TransitionKind): Double = theta.getOrElse(k, 0.0)

  /** Probability of moving from a source state to an external state. */
  def pExternalIn: Double = Logistic.sigmoid(apply(TransitionKind.ExternalIn))

  /** Probability of remaining external once external. */
  def pExternalStay: Double = Logistic.sigmoid(apply(TransitionKind.ExternalStay))

object TransitionModel:
  /** Provisional defaults: forward-in-discourse and hierarchy moves are cheap, long backward jumps
    * are penalized but permitted, external excursions are entered with probability σ(−1.5) ≈ 0.18
    * and left with probability 1 − σ(−0.85) ≈ 0.7.
    */
  val default: TransitionModel = TransitionModel(
    Map(
      TransitionKind.Stay -> 0.5,
      TransitionKind.DiscourseSuccessor -> 1.5,
      TransitionKind.WorldTimeSuccessor -> 1.0,
      TransitionKind.CausalNeighbor -> 0.8,
      TransitionKind.HierarchyUp -> 0.6,
      TransitionKind.HierarchyDown -> 0.6,
      TransitionKind.SameEntityThread -> 0.2,
      TransitionKind.SemanticNeighbor -> 0.3,
      TransitionKind.Backward -> -0.5,
      TransitionKind.LongJump -> -1.0,
      TransitionKind.ExternalIn -> -1.5,
      TransitionKind.ExternalStay -> -0.85
    )
  )

/** Feature vector of a source→source move; exposed for diagnostics and learning. */
final case class TransitionFeatures(values: Map[TransitionKind, Double]):
  /** Deterministic summation order (review #30). */
  def score(model: TransitionModel): Double =
    TransitionKind.values.toVector.map(k => model(k) * values.getOrElse(k, 0.0)).sum

object TransitionFeatures:
  def between(view: SourceView, s: SourceNodeRef, t: SourceNodeRef): TransitionFeatures =
    val ps = view.relativePosition(s)
    val pt = view.relativePosition(t)
    def ind(b: Boolean): Double = if b then 1.0 else 0.0
    TransitionFeatures(
      Map(
        TransitionKind.Stay -> ind(s == t),
        TransitionKind.DiscourseSuccessor -> ind(
          view.hasEdge(RelationLayer.DiscourseSuccession, s, t)
        ),
        TransitionKind.WorldTimeSuccessor -> ind(view.hasEdge(RelationLayer.WorldTime, s, t)),
        TransitionKind.CausalNeighbor -> ind(
          view.hasEdge(RelationLayer.Causal, s, t) || view.hasEdge(RelationLayer.Causal, t, s)
        ),
        TransitionKind.HierarchyUp -> ind(view.isAncestor(t, s)),
        TransitionKind.HierarchyDown -> ind(view.isAncestor(s, t)),
        TransitionKind.SameEntityThread -> ind(
          view.hasEdge(RelationLayer.EntityContinuity, s, t) ||
            view.hasEdge(RelationLayer.EntityContinuity, t, s)
        ),
        TransitionKind.SemanticNeighbor -> math.max(
          view.weight(RelationLayer.Semantic, s, t),
          view.weight(RelationLayer.Semantic, t, s)
        ),
        TransitionKind.Backward -> ind(s != t && pt < ps && !view.isAncestor(t, s)),
        TransitionKind.LongJump -> math.abs(pt - ps)
      )
    )

/** Inference knobs. Not a case class: `fromProduct` would mint a non-positive temperature or
  * negative refinement weight that [[HsmmConfig.of]] refuses.
  */
final class HsmmConfig private (
    val temperature: Double,
    val transitions: TransitionModel,
    val refinementPasses: Int,
    val refinementWeight: Double
):
  override def equals(other: Any): Boolean = other match
    case that: HsmmConfig =>
      temperature == that.temperature && transitions == that.transitions &&
      refinementPasses == that.refinementPasses && refinementWeight == that.refinementWeight
    case _ => false

  override def hashCode(): Int =
    (temperature, transitions, refinementPasses, refinementWeight).hashCode

  override def toString: String =
    s"HsmmConfig(temperature=$temperature, refinementPasses=$refinementPasses, " +
      s"refinementWeight=$refinementWeight)"

object HsmmConfig:
  def of(
      temperature: Double = 0.15,
      transitions: TransitionModel = TransitionModel.default,
      refinementPasses: Int = 0,
      refinementWeight: Double = 0.3
  ): Either[AlignError, HsmmConfig] =
    if temperature <= 0.0 || temperature.isNaN || temperature.isInfinite then
      Left(AlignError.InvalidConfig("HsmmConfig.temperature", "must be positive and finite"))
    else if refinementPasses < 0 then
      Left(AlignError.InvalidConfig("HsmmConfig.refinementPasses", "must be nonnegative"))
    else if refinementWeight < 0.0 || refinementWeight.isNaN then
      Left(AlignError.InvalidConfig("HsmmConfig.refinementWeight", "must be nonnegative"))
    else if transitions.theta.values.exists(v => v.isNaN || v.isInfinite) then
      Left(AlignError.InvalidConfig("HsmmConfig.transitions", "weights must be finite"))
    else Right(new HsmmConfig(temperature, transitions, refinementPasses, refinementWeight))

  val default: HsmmConfig = of().fold(e => throw new IllegalStateException(e.message), identity)

  def unsafe(
      temperature: Double = 0.15,
      transitions: TransitionModel = TransitionModel.default,
      refinementPasses: Int = 0,
      refinementWeight: Double = 0.3
  ): HsmmConfig =
    of(temperature, transitions, refinementPasses, refinementWeight).fold(
      e => throw new IllegalArgumentException(e.message),
      identity
    )

/** Result of gated inference — a **proof of mode-gate admission**, not a record (ADR 0001 rev 3 L1;
  * forward-review P0). `costs` is keyed by the *admissible* states of each unit (plus its external
  * states); `admissibility` records, per candidate anchor, which modes the gate allowed and which
  * contradictions it found — including anchors whose only admissible mode is distorted.
  *
  * The constructor is `private[align]` and the class has no `copy`: the only ways to obtain a value
  * are [[GraphHsmm.infer]] (which itself goes through [[HsmmResult.validated]]) and
  * [[HsmmResult.validated]], which takes the recall and the source view and proves that every
  * anchored state appearing in the posterior, the flow, the costs, or the Viterbi path is admitted
  * in exactly that mode by an admissibility record that the [[ModeGate]] itself reproduces on that
  * unit and node. A forged row on an inadmissible `(anchor, mode)` — or a forged admissibility
  * record — therefore cannot inhabit this type, and every consumer that requires it
  * ([[RecallSignature]], [[PopulationAggregate]], [[SupportDensity]]) is guaranteed a gated result.
  * [[AblationResult]] is a separate type with no path here.
  *
  * The proof covers the **nominated candidate set**: `candidateAnchors` (canonical per-unit anchor
  * vectors, construction input) is the exact set over which `admissibility` is derived, and no
  * anchored key may fall outside it — so faithful mass on a never-nominated, uncontradicted node is
  * refused as "not nominated", not admitted by silence. `admissibility`, `viewFingerprint`, and
  * `recallChecksum` are derived inside [[HsmmResult.validated]], never supplied; a wire record
  * carries them as mandatory match fields (see [[AlignWire.matched]]) and an [[AdmissibilityEcho]]
  * for drift detection only.
  *
  * Residual (stated, not hidden): nomination *provenance* (which channel nominated an anchor) is
  * not proven; the candidate-anchor set is construction data like the posterior itself.
  */
final class HsmmResult private[align] (
    val posterior: AlignmentMatrix,
    val flow: TransitionFlow,
    val viterbi: Vector[AlignState],
    val logLikelihood: Double,
    val costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]],
    val candidateAnchors: Map[RecallUnitId, Vector[SourceNodeRef]],
    val admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]],
    val viewFingerprint: ViewFingerprint,
    val recallChecksum: Checksum,
    val refinementPasses: Int
):
  /** Digest of the derived admissibility, for the wire's drift check. */
  def admissibilityEcho: AdmissibilityEcho = AdmissibilityEcho.of(admissibility)

  private def parts =
    (
      posterior,
      flow,
      viterbi,
      logLikelihood,
      costs,
      candidateAnchors,
      admissibility,
      viewFingerprint,
      recallChecksum,
      refinementPasses
    )

  override def equals(o: Any): Boolean = o match
    case that: HsmmResult => this.parts == that.parts
    case _                => false
  override def hashCode: Int = parts.hashCode
  override def toString: String =
    s"HsmmResult(units=${posterior.size}, steps=${flow.size}, logLikelihood=$logLikelihood, " +
      s"passes=$refinementPasses, view=${viewFingerprint.checksum.short()})"

object HsmmResult:

  /** Tolerance for row normalization and flow-marginal consistency.
    *
    * '''Defends float64 accumulation across a row''' — a posterior row is a sum of per-state masses
    * produced by `logsumexp`, and the naive summation error grows with the number of states: about
    * `n * 2.22e-16`, so roughly `2e-14` at 100 states and `2e-13` at 1000. `1e-9` is therefore four
    * to five orders ABOVE the phenomenon.
    *
    * '''That margin is wide and is not derived.''' It is a round number chosen to sit comfortably
    * above accumulation error, not a bound computed from a state count. Recorded plainly so nobody
    * re-derives it as tight, and so that a future row two orders larger is understood to eat into
    * the margin rather than to break an exact bound.
    *
    * It is used ONLY for comparisons here (`close`, row-sum and marginal checks), never to admit a
    * value that is then stored unabsorbed — which is the failure this project found in `MassRatio`,
    * `ExternalMassReport` and `PlacementResolution` on 2026-08-29.
    */
  val Tolerance: Double = 1e-9

  /** The sole external constructor: proves the gate invariant over the supplied parts, **anchored
    * in the [[ModeGate]]** and **bounded by the nominated candidates**.
    *
    * Checks, in order: (a) structure — rows well-formed, normalized (`|Σ − 1| ≤ Tolerance`), and
    * exactly the recall's units in recall order; `flow` has one step fewer than the rows with
    * matching consecutive endpoints, finite nonnegative mass, and step marginals equal to the
    * adjacent rows within `Tolerance`; `viterbi` has one state per row; `logLikelihood` is not NaN;
    * (b) nomination — `candidateAnchors` names every recall unit (possibly with no anchors), each
    * vector in canonical order (ascending reference key, unique), every anchor present in the view;
    * (c) derivation — `admissibility(unit)(ref) = ModeGate.assess(unit, view.node(ref), view)` for
    * exactly the nominated anchors; (d) drift — a supplied echo must equal the digest of that
    * derivation ([[AlignError.GateDrift]] otherwise); (e) the gate — every anchored state that
    * appears as a key of a posterior row, of a flow step (either endpoint), of a cost map, or as a
    * Viterbi step (key presence, not positive mass) has its anchor nominated for that unit and is
    * admitted in exactly that mode by the derived record. External states are never gated.
    *
    * `viewFingerprint` and `recallChecksum` are set here from the view and recall in hand, so a
    * serialized result can only be decoded with both, and the decoder compares them to the wire's
    * copies through [[AlignWire.matched]].
    */
  def validated(
      recall: RecallGraph[Checked],
      view: SourceView,
      candidateAnchors: Map[RecallUnitId, Vector[SourceNodeRef]],
      posterior: AlignmentMatrix,
      flow: TransitionFlow,
      viterbi: Vector[AlignState],
      logLikelihood: Double,
      costs: Map[RecallUnitId, Map[AlignState, CostBreakdown]],
      refinementPasses: Int,
      admissibilityEcho: Option[AdmissibilityEcho] = None
  ): Either[AlignError, HsmmResult] =
    val rows = posterior.rows
    def close(a: Double, b: Double): Boolean = math.abs(a - b) <= Tolerance
    val structural: Either[AlignError, Unit] =
      AlignmentMatrix.of(rows).flatMap { _ =>
        if flow.size != math.max(0, rows.size - 1) then
          Left(AlignError.InconsistentResult(s"flow has ${flow.size} steps for ${rows.size} rows"))
        else if viterbi.size != rows.size then
          Left(
            AlignError
              .InconsistentResult(s"viterbi has ${viterbi.size} states for ${rows.size} rows")
          )
        else if logLikelihood.isNaN then Left(AlignError.InconsistentResult("logLikelihood is NaN"))
        else if refinementPasses < 0 then
          Left(AlignError.InconsistentResult("refinementPasses must be nonnegative"))
        else
          // Rows must be exactly the recall's units in recall order: a reordered, truncated, or
          // extended matrix (with flow and path rebuilt consistently) would otherwise validate and
          // fabricate the signature's forward/backward ordering metrics.
          val expectedOrder = recall.ordered.map(_.id)
          val unknownUnit =
            if rows.map(_.unit) == expectedOrder then None
            else
              rows
                .collectFirst {
                  case r if !recall.byId.contains(r.unit) =>
                    AlignError.InconsistentResult(s"row unit ${r.unit.value} is not in the recall")
                }
                .orElse(
                  Some(
                    AlignError.InconsistentResult(
                      s"rows do not follow the recall's unit order (${rows.size} rows for " +
                        s"${expectedOrder.size} units; reordered, truncated, or extended)"
                    )
                  )
                )
          val unnormalized = rows.collectFirst {
            case r if !close(r.total, 1.0) =>
              AlignError.InconsistentResult(
                s"row ${r.unit.value} sums to ${r.total}, not 1 within $Tolerance"
              )
          }
          val endpoints = flow.steps.zipWithIndex.collectFirst {
            case (st, i) if st.from != rows(i).unit || st.to != rows(i + 1).unit =>
              AlignError.InconsistentResult(s"flow step $i does not join rows $i and ${i + 1}")
          }
          val stepMass = flow.steps.collectFirst {
            case st if st.mass.values.exists(m => m < 0.0 || m.isNaN || m.isInfinite) =>
              AlignError.InconsistentResult(
                s"flow step ${st.from.value}→${st.to.value} has malformed mass"
              )
          }
          val marginals = flow.steps.zipWithIndex.collectFirst {
            case (st, i) if !marginalsMatch(st, rows(i), rows(i + 1)) =>
              AlignError.InconsistentResult(
                s"flow step $i marginals do not match rows $i and ${i + 1} within $Tolerance"
              )
          }
          unknownUnit
            .orElse(unnormalized)
            .orElse(endpoints)
            .orElse(stepMass)
            .orElse(marginals)
            .toLeft(())
      }
    // Nomination: the candidate-anchor map names exactly the recall's units, each vector canonical,
    // every anchor a node of the view.
    val nomination: Either[AlignError, Unit] =
      val units = recall.ordered.map(_.id)
      val strayCosts = costs.keys.filterNot(recall.byId.contains).map(_.value).toVector.sorted
      if strayCosts.nonEmpty then
        Left(AlignError.InconsistentResult(s"costs recorded for unknown unit ${strayCosts.head}"))
      else if candidateAnchors.keySet != units.toSet then
        val unknown = candidateAnchors.keys.filterNot(recall.byId.contains).map(_.value)
        val missing = units.filterNot(candidateAnchors.contains).map(_.value)
        Left(
          AlignError.InconsistentResult(
            "candidateAnchors must name exactly the recall's units" +
              (if unknown.nonEmpty then s" (unknown: ${unknown.toVector.sorted.mkString(", ")})"
               else "") +
              (if missing.nonEmpty then s" (missing: ${missing.mkString(", ")})" else "")
          )
        )
      else
        units.iterator
          .map { u =>
            val anchors = candidateAnchors(u)
            val keys = anchors.map(_.key)
            if keys != keys.distinct.sorted then
              Left(
                AlignError.InconsistentResult(
                  s"candidate anchors of unit ${u.value} are not in canonical order (sorted, unique)"
                )
              )
            else
              anchors.find(ref => view.node(ref).isEmpty) match
                case Some(ref) =>
                  Left(
                    AlignError.GateViolation(
                      u,
                      AlignState.Source(ref),
                      "nominated anchor absent from the source view"
                    )
                  )
                case None => Right(())
          }
          .collectFirst { case l @ Left(_) => l }
          .getOrElse(Right(()))
    // Derivation: the gate over exactly the nominated anchors — never all view nodes, never the
    // anchors that happen to appear in the parts.
    def derive: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]] =
      // The nomination pass has already refused absent anchors; derivation still goes through
      // `view.node` totally (an anchor without a node simply has no record, and would then fail
      // the gate below rather than throw).
      recall.ordered.iterator.map { unit =>
        unit.id -> candidateAnchors(unit.id).iterator.flatMap { ref =>
          view.node(ref).map(node => ref -> ModeGate.assess(unit, node, view))
        }.toMap
      }.toMap
    def drift(derived: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]) =
      admissibilityEcho match
        case Some(echo) =>
          val actual = AdmissibilityEcho.of(derived)
          if echo == actual then Right(()) else Left(AlignError.GateDrift(echo, actual))
        case None => Right(())
    def gate(admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]) =
      def nominated(unit: RecallUnitId, s: AlignState): Boolean =
        s.anchor.forall(ref => candidateAnchors.getOrElse(unit, Vector.empty).contains(ref))
      def admitted(unit: RecallUnitId, s: AlignState): Boolean = s match
        case AlignState.External(_) => true
        case AlignState.Source(ref) =>
          admissibility.get(unit).flatMap(_.get(ref)).exists(_.faithful)
        case AlignState.Distorted(ref, fs) =>
          admissibility.get(unit).flatMap(_.get(ref)).exists(_.distortion.contains(fs))
      def check(unit: RecallUnitId, s: AlignState, where: String): Option[AlignError] =
        if !nominated(unit, s) then
          Some(
            AlignError.GateViolation(unit, s, s"$where on an anchor not nominated for this unit")
          )
        else if !admitted(unit, s) then
          Some(
            AlignError
              .GateViolation(unit, s, s"$where on a state the gate did not admit in this mode")
          )
        else None
      // Key presence, not positive mass: a zero-mass key on an inadmissible state is still a
      // state a consumer can read (argmax, population.sourceRefs).
      val posteriorV = rows.iterator.flatMap { r =>
        r.mass.keys.toVector.sortBy(_.key).iterator.flatMap(s => check(r.unit, s, "posterior key"))
      }
      val flowV = flow.steps.iterator.flatMap { st =>
        st.mass.keys.toVector.sortBy { case (a, b) => (a.key, b.key) }.iterator.flatMap { (a, b) =>
          check(st.from, a, "flow key").orElse(check(st.to, b, "flow key"))
        }
      }
      // A cost record's own mode and exclusion must agree with the key it sits under: consumers
      // read `breakdown.isDistorted`/`facets` and must never receive a record that contradicts
      // its state (cross-record invariant, upheld amendment to the wire checkpoint).
      def coherent(u: RecallUnitId, s: AlignState, b: CostBreakdown): Option[AlignError] =
        val expectedMode = s.mode
        if b.exclusion.nonEmpty then
          Some(
            AlignError.MalformedRecord(
              "CostBreakdown",
              s"unit ${u.value}, state ${s.key}: an admitted state's record cannot be excluded"
            )
          )
        else if b.mode != expectedMode then
          Some(
            AlignError.MalformedRecord(
              "CostBreakdown",
              s"unit ${u.value}, state ${s.key}: record mode " +
                s"${b.mode.map(_.render).getOrElse("none")} does not agree with its key " +
                s"(${expectedMode.map(_.render).getOrElse("none")})"
            )
          )
        else None
      val costV = costs.toVector.sortBy(_._1.value).iterator.flatMap { (u, m) =>
        m.toVector.sortBy(_._1.key).iterator.flatMap { (s, b) =>
          check(u, s, "cost entry").orElse(coherent(u, s, b))
        }
      }
      val pathV = rows.zip(viterbi).iterator.flatMap { (r, s) => check(r.unit, s, "viterbi step") }
      (posteriorV ++ flowV ++ costV ++ pathV).nextOption().toLeft(())
    for
      _ <- structural
      _ <- nomination
      admissibility = derive
      _ <- drift(admissibility)
      _ <- gate(admissibility)
    yield new HsmmResult(
      posterior,
      flow,
      viterbi,
      logLikelihood,
      costs,
      candidateAnchors,
      admissibility,
      ViewFingerprint.of(view),
      AlignWire.recallChecksum(recall),
      refinementPasses
    )

  /** Row-marginal consistency of a flow step with its adjacent rows (deterministic key order). */
  private def marginalsMatch(st: FlowStep, from: AlignmentRow, to: AlignmentRow): Boolean =
    val byFrom = st.mass.toVector.sortBy { case ((a, b), _) => (a.key, b.key) }
    val outMass = byFrom.groupMapReduce(_._1._1)(_._2)(_ + _)
    val inMass = byFrom.groupMapReduce(_._1._2)(_._2)(_ + _)
    val fromKeys = (from.mass.keySet ++ outMass.keySet).toVector.sortBy(_.key)
    val toKeys = (to.mass.keySet ++ inMass.keySet).toVector.sortBy(_.key)
    fromKeys.forall(s => math.abs(outMass.getOrElse(s, 0.0) - from(s)) <= Tolerance) &&
    toKeys.forall(t => math.abs(inMass.getOrElse(t, 0.0) - to(t)) <= Tolerance)

/** Result of *ungated* inference, for ablations only. It deliberately has no `AlignmentMatrix`:
  * nothing that consumes a posterior ([[RecallSignature]], densities, population aggregates) can be
  * fed an ungated result, so the ablation cannot masquerade as a scientific alignment.
  */
final case class AblationResult(
    rows: Vector[(RecallUnitId, Map[AlignState, Double])],
    viterbi: Vector[AlignState],
    logLikelihood: Double
):
  def massOn(unit: RecallUnitId, state: AlignState): Double =
    rows.find(_._1 == unit).flatMap(_._2.get(state)).getOrElse(0.0)

/** Stage 3: sparse graph-structured HSMM over the recall sequence.
  *
  * States at unit `i` are the `(anchor, mode)` pairs the [[ModeGate]] admits on that unit's
  * candidates plus the external states. The gate runs first and is non-bypassable: a contradicted
  * anchor is present only in its distorted mode, so the faithful mode of a role-reversed or negated
  * event can never receive mass, while the event itself is still recalled (ADR 0001 rev 3 §D5). A
  * unit the aligner could not rank has the single state `Unranked`. Emissions are `exp(−cost/τ)`
  * where the cost model sees only admissible pairs (law L3); external states emit at the floor.
  * Transitions are typed by source structure on anchors. Forward–backward in log space yields `P`
  * and `F` as posteriors; Viterbi gives the MAP path on the same (possibly refined) costs as the
  * posterior. Refinement passes re-weight emissions by relation preservation over the admissible
  * states only and never widen the state space.
  */
object GraphHsmm:

  def infer(
      recall: RecallGraph[Checked],
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig = HsmmConfig.default
  ): Either[AlignError, HsmmResult] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      val (post, flow, path, logZ, costs, adm, passes) =
        run(units, recall, view, candidates, costModel, config, gate = true)
      // The engine proves its own output: a gate violation here would be a bug, and it surfaces
      // as a typed error rather than an unproven result (law: every infer output validates). The
      // anchors it used are handed over as the nominated set — candidates that are not nodes of
      // the view are dropped here and from the cost keys (`run`), never nominated: the proof
      // refuses absent anchors, and an unreachable candidate is a nomination error, not a state.
      HsmmResult.validated(
        recall,
        view,
        candidates
          .anchorsByUnit(units.map(_.id))
          .map((u, refs) => u -> refs.filter(ref => view.node(ref).nonEmpty)),
        post,
        flow,
        path,
        logZ,
        costs,
        passes,
        Some(AdmissibilityEcho.of(adm))
      )

  /** Ungated inference for ablations: every candidate is admitted in the faithful mode and no
    * distorted state exists. Returns an [[AblationResult]], never an `HsmmResult`.
    */
  def ablationUngated(
      recall: RecallGraph[Checked],
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig = HsmmConfig.default
  ): Either[AlignError, AblationResult] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      val (post, _, path, logZ, _, _, _) =
        run(units, recall, view, candidates, costModel, config, gate = false)
      Right(AblationResult(post.rows.map(r => r.unit -> r.mass), path, logZ))

  private type Costs = Map[RecallUnitId, Map[AlignState, CostBreakdown]]
  private type Adm = Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]

  private def run(
      units: Vector[RecallUnit],
      recall: RecallGraph[Checked],
      view: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      config: HsmmConfig,
      gate: Boolean
  ): (AlignmentMatrix, TransitionFlow, Vector[AlignState], Double, Costs, Adm, Int) =
    val tau = config.temperature

    // 1. The mode gate (prepass): which (anchor, mode) pairs exist for each unit. The cost model
    //    is consulted only for those pairs.
    val admissibility: Vector[Map[SourceNodeRef, Admissibility]] = units.map { u =>
      candidates
        .set(u.id)
        .ranked
        .flatMap { ref =>
          view.node(ref).map { n =>
            ref -> (if gate then ModeGate.assess(u, n, view) else Admissibility.faithfulOnly)
          }
        }
        .toMap
    }
    val breakdowns: Vector[Map[AlignState, CostBreakdown]] =
      units.zip(admissibility).map { (u, adm) =>
        val set = candidates.set(u.id)
        // Candidates absent from the view are dropped (not priced, not nominated): the gated
        // result carries only anchors the proof can re-derive on this view.
        val sources = set.ranked.flatMap { ref =>
          view.node(ref).toVector.flatMap { n =>
            adm(ref).modes.map(m => AlignState.anchored(ref, m) -> costModel.cost(u, n, m, view))
          }
        }
        val externals =
          if set.abstained && set.ranked.isEmpty then Vector(AlignState.unranked)
          else AlignState.externals
        val ext = externals.map {
          case s @ AlignState.External(x) =>
            s -> CostBreakdown(Map.empty, None, None, costModel.externalCost(u, x))
          case s => s -> CostBreakdown.unreachable
        }
        (sources ++ ext).toMap
      }
    val states: Vector[Vector[AlignState]] = breakdowns.map { m =>
      m.toVector.collect { case (s, b) if !b.excluded => s }.sortBy(_.key)
    }
    val baseCost: Vector[Map[AlignState, Double]] =
      breakdowns.zip(states).map { (m, ss) => ss.map(s => s -> m(s).total).toMap }

    // log transition matrices A_i(s, t): a mixture of "go/stay external" and a feature softmax
    // over the source states available at i+1
    val logA: Vector[Map[AlignState, Map[AlignState, Double]]] =
      (0 until units.size - 1).toVector.map { i =>
        val from = states(i)
        val to = states(i + 1)
        from.map(s => s -> transitionRow(view, config.transitions, s, to)).toMap
      }

    def forwardBackward(
        costs: Vector[Map[AlignState, Double]]
    ): (AlignmentMatrix, TransitionFlow, Double) =
      val logE = costs.map(_.view.mapValues(c => -c / tau).toMap)
      val n = units.size
      val logAlpha = Array.ofDim[Map[AlignState, Double]](n)
      val logPi = -math.log(states(0).size.toDouble)
      logAlpha(0) = states(0).map(s => s -> (logPi + logE(0)(s))).toMap
      for i <- 1 until n do
        logAlpha(i) = states(i).map { t =>
          val acc = states(i - 1).map(s => logAlpha(i - 1)(s) + logA(i - 1)(s)(t))
          t -> (logE(i)(t) + logSumExp(acc))
        }.toMap
      val logBeta = Array.ofDim[Map[AlignState, Double]](n)
      logBeta(n - 1) = states(n - 1).map(s => s -> 0.0).toMap
      for i <- (n - 2) to 0 by -1 do
        logBeta(i) = states(i).map { s =>
          val acc = states(i + 1).map(t => logA(i)(s)(t) + logE(i + 1)(t) + logBeta(i + 1)(t))
          s -> logSumExp(acc)
        }.toMap
      // deterministic summation order: iterate states, not a map (review #30)
      val logZ = logSumExp(states(n - 1).map(s => logAlpha(n - 1)(s)))
      val rows = (0 until n).toVector.map { i =>
        AlignmentRow(
          units(i).id,
          states(i).map(s => s -> math.exp(logAlpha(i)(s) + logBeta(i)(s) - logZ)).toMap
        )
      }
      val steps = (0 until n - 1).toVector.map { i =>
        val mass = for
          s <- states(i)
          t <- states(i + 1)
        yield (s, t) -> math.exp(
          logAlpha(i)(s) + logA(i)(s)(t) + logE(i + 1)(t) + logBeta(i + 1)(t) - logZ
        )
        FlowStep(units(i).id, units(i + 1).id, mass.toMap)
      }
      (AlignmentMatrix(rows), TransitionFlow(steps), logZ)

    var costs = baseCost
    var (posterior, flow, logZ) = forwardBackward(costs)
    var pass = 0
    while pass < config.refinementPasses do
      costs = RelationPreservation.reweight(
        baseCost,
        units,
        posterior,
        recall,
        view,
        config.refinementWeight
      )
      val r = forwardBackward(costs)
      posterior = r._1
      flow = r._2
      logZ = r._3
      pass += 1

    val path = viterbi(units, states, costs.map(_.view.mapValues(c => -c / tau).toMap), logA)
    (
      posterior,
      flow,
      path,
      logZ,
      units.map(_.id).zip(breakdowns).toMap,
      units.map(_.id).zip(admissibility).toMap,
      pass
    )

  /** Log transition distribution from `s` over the states `to` available at the next unit. */
  private[align] def transitionRow(
      view: SourceView,
      model: TransitionModel,
      s: AlignState,
      to: Vector[AlignState]
  ): Map[AlignState, Double] =
    val sources = to.filter(_.isSource)
    val externals = to.filter(_.isExternal)
    val nExt = math.max(1, externals.size)
    s.anchor match
      case Some(a) =>
        val pIn = if sources.isEmpty then 1.0 else model.pExternalIn
        val scores =
          sources.map(t => t -> TransitionFeatures.between(view, a, t.anchor.get).score(model))
        val z = logSumExp(scores.map(_._2))
        val src = scores.map { case (t, sc) => t -> (math.log(1.0 - pIn) + sc - z) }
        val ext = externals.map(t => t -> (math.log(pIn) - math.log(nExt.toDouble)))
        (src ++ ext).toMap
      case None =>
        val pStay = if sources.isEmpty then 1.0 else model.pExternalStay
        val src = sources.map(t => t -> (math.log(1.0 - pStay) - math.log(sources.size.toDouble)))
        val ext = externals.map(t => t -> (math.log(pStay) - math.log(nExt.toDouble)))
        (src ++ ext).toMap

  private def viterbi(
      units: Vector[RecallUnit],
      states: Vector[Vector[AlignState]],
      logE: Vector[Map[AlignState, Double]],
      logA: Vector[Map[AlignState, Map[AlignState, Double]]]
  ): Vector[AlignState] =
    val n = units.size
    val delta = Array.ofDim[Map[AlignState, Double]](n)
    val back = Array.ofDim[Map[AlignState, AlignState]](n)
    val logPi = -math.log(states(0).size.toDouble)
    delta(0) = states(0).map(s => s -> (logPi + logE(0)(s))).toMap
    for i <- 1 until n do
      val pairs = states(i).map { t =>
        val best = states(i - 1)
          .map(s => (s, delta(i - 1)(s) + logA(i - 1)(s)(t)))
          .maxBy { case (s, v) => (v, s.key) }
        (t, best._1, best._2 + logE(i)(t))
      }
      delta(i) = pairs.map(p => p._1 -> p._3).toMap
      back(i) = pairs.map(p => p._1 -> p._2).toMap
    val last = states(n - 1).map(s => (s, delta(n - 1)(s))).maxBy { case (s, v) => (v, s.key) }._1
    val path = Array.ofDim[AlignState](n)
    path(n - 1) = last
    for i <- (n - 1) until 0 by -1 do path(i - 1) = back(i)(path(i))
    path.toVector

  private[align] def logSumExp(xs: Vector[Double]): Double =
    if xs.isEmpty then Double.NegativeInfinity
    else
      val m = xs.max
      if m.isNegInfinity then m else m + math.log(xs.map(x => math.exp(x - m)).sum)

/** Relation-preservation term `D_r(B^{(r)}, P A^{(r)} P^T)` restricted to explicit recall
  * relations. Used as a diagnostic and as an optional corrective on emissions.
  */
/** Preservation of one relation layer, with the support behind it: how many of the recall's stated
  * relations of that layer could actually be evaluated against the alignment.
  */
final class LayerPreservation private (
    val mean: Option[Double],
    val evaluated: Int,
    val stated: Int
):
  def render: String =
    val m = mean.map(x => f"$x%.4f").getOrElse("n/a")
    s"$m (over $evaluated/$stated evaluable relations)"

  override def equals(other: Any): Boolean = other match
    case that: LayerPreservation =>
      mean == that.mean && evaluated == that.evaluated && stated == that.stated
    case _ => false

  override def hashCode: Int = (mean, evaluated, stated).hashCode
  override def toString: String = s"LayerPreservation(${render})"

object LayerPreservation:
  /** Trusted construction from inside `align`. */
  private[align] def unsafe(mean: Option[Double], evaluated: Int, stated: Int): LayerPreservation =
    new LayerPreservation(mean, evaluated, stated)

  /** Checked construction: a mean is present exactly when something was evaluable, it is a
    * fraction, and the evaluated relations are a sub-count of those stated.
    */
  def of(
      mean: Option[Double],
      evaluated: Int,
      stated: Int
  ): Either[AlignError, LayerPreservation] =
    if evaluated < 0 || stated < 0 || evaluated > stated then
      Left(
        AlignError
          .MalformedRecord(
            "layerPreservation",
            s"evaluated $evaluated is not a sub-count of $stated"
          )
      )
    else if mean.exists(m => m.isNaN || m.isInfinite || m < 0.0 || m > 1.0) then
      Left(AlignError.MalformedRecord("layerPreservation", "mean must be a fraction"))
    else if mean.isDefined != (evaluated > 0) then
      Left(
        AlignError.MalformedRecord(
          "layerPreservation",
          "a mean exists exactly when at least one relation was evaluable"
        )
      )
    else Right(new LayerPreservation(mean, evaluated, stated))

object RelationPreservation:

  /** Mean induced source weight over the recall's explicit edges of a layer, with the support it
    * rests on: 1 means every EVALUABLE recalled relation is preserved in the source.
    *
    * The mean is `None` for a layer the recall stated no relations of — reporting 1.0 there said
    * "every recalled relation is preserved" about a recall that claimed no relations at all. A
    * relation whose endpoints carry no source mass is likewise not evidence of non-preservation: it
    * is excluded from the mean and counted in `stated` but not `evaluated`, rather than scored 0.0
    * and dragging the layer down for a unit we simply could not place.
    *
    * This is a diagnostic, not an inference input: `reweight` is the production path and it adds no
    * bonus when a recall has no relations. Said explicitly because I once claimed otherwise on the
    * board and the call graph disagreed.
    */
  def diagnostic(
      posterior: AlignmentMatrix,
      recall: RecallGraph[Checked],
      view: SourceView
  ): Map[RelationLayer, LayerPreservation] =
    def induced(layer: RelationLayer, from: RecallUnitId, to: RecallUnitId): Option[Double] =
      (posterior.row(from), posterior.row(to)) match
        case (Some(a), Some(b)) =>
          val pairs = for
            (s, ma) <- a.anchorMass.toVector.sortBy(_._1.key)
            (t, mb) <- b.anchorMass.toVector.sortBy(_._1.key)
            if ma > 0.0 && mb > 0.0
          yield ma * mb * (if view.reachable(layer, s, t) then 1.0 else 0.0)
          val z = a.sourceMass * b.sourceMass
          if z <= 0.0 then None else Some(pairs.sum / z)
        case _ => None
    val temporal = recall.relations.temporal.map { e =>
      e.relation match
        case RecallTemporalRelation.Before => induced(RelationLayer.WorldTime, e.from, e.to)
        case RecallTemporalRelation.After  => induced(RelationLayer.WorldTime, e.to, e.from)
        // A simultaneity claim has no direction to preserve in a world-time ordering, so there is
        // nothing to evaluate; it used to score 0.0, i.e. "not preserved".
        case RecallTemporalRelation.Simultaneous => None
    }
    val causal = recall.relations.causal.map(e => induced(RelationLayer.Causal, e.cause, e.effect))
    def layer(stated: Vector[Option[Double]]): LayerPreservation =
      val evaluated = stated.flatten
      // The support is always reported, so "you stated no relations of this layer" stays
      // distinguishable from "none of the three you stated could be evaluated" - collapsing both
      // to a bare None would lose the more informative of the two.
      val mean = if evaluated.isEmpty then None else Some(evaluated.sum / evaluated.size)
      LayerPreservation.unsafe(mean, evaluated.size, stated.size)
    Map(RelationLayer.WorldTime -> layer(temporal), RelationLayer.Causal -> layer(causal))

  /** Lower the cost of states that would preserve the recall's explicit relations given the current
    * posterior of the related units. Only states present in `base` (the admissible ones) are
    * touched, so the gate can never be undone (law L1).
    */
  private[align] def reweight(
      base: Vector[Map[AlignState, Double]],
      units: Vector[RecallUnit],
      posterior: AlignmentMatrix,
      recall: RecallGraph[Checked],
      view: SourceView,
      lambda: Double
  ): Vector[Map[AlignState, Double]] =
    val idx = units.map(_.id).zipWithIndex.toMap
    val bonus = Array.fill(units.size)(scala.collection.mutable.Map.empty[AlignState, Double])
    def add(i: Int, s: AlignState, v: Double): Unit =
      bonus(i).update(s, bonus(i).getOrElse(s, 0.0) + v)
    def support(layer: RelationLayer, a: RecallUnitId, b: RecallUnitId): Unit =
      for
        i <- idx.get(a)
        j <- idx.get(b)
        rowB <- posterior.row(b)
        rowA <- posterior.row(a)
      do
        base(i).keys.toVector.sortBy(_.key).foreach { s =>
          s.anchor.foreach { sr =>
            val v = rowB.anchorMass.toVector
              .sortBy(_._1.key)
              .collect { case (t, m) if view.reachable(layer, sr, t) => m }
              .sum
            add(i, s, v)
          }
        }
        base(j).keys.toVector.sortBy(_.key).foreach { t =>
          t.anchor.foreach { tr =>
            val v = rowA.anchorMass.toVector
              .sortBy(_._1.key)
              .collect { case (s, m) if view.reachable(layer, s, tr) => m }
              .sum
            add(j, t, v)
          }
        }
    recall.relations.temporal.foreach { e =>
      e.relation match
        case RecallTemporalRelation.Before => support(RelationLayer.WorldTime, e.from, e.to)
        case RecallTemporalRelation.After  => support(RelationLayer.WorldTime, e.to, e.from)
        case _                             => ()
    }
    recall.relations.causal.foreach(e => support(RelationLayer.Causal, e.cause, e.effect))
    base.zipWithIndex.map { case (m, i) =>
      m.map { case (s, c) => s -> (c - lambda * bonus(i).getOrElse(s, 0.0)) }
    }
