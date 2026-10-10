package storymodel4s.align

import storymodel4s.core.Checksum
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Explicit reconstruction preserves base evidence while exposing every post-inference choice. */
object StructuredReconstruction:
  /** Filling is an explicit request, never an implicit property of a new profile. */
  enum FillPolicy:
    case Disabled, Requested

  /** Decision origins distinguish posterior selection from a separately requested target. */
  enum Origin:
    case RawSourceMaximum, GroupConstrained, RequestedFill

  /** The scene decoder is a reconstruction assumption, not a reference-measurement licence. */
  enum OrderPolicy:
    case Unconstrained, Nondecreasing, PenalizedBackward

  /** Finite explicit knobs replace process-environment reads inside the decoder. */
  final class DecisionPolicy private[StructuredReconstruction] (
      val order: OrderPolicy,
      val backwardPenalty: Double,
      val forwardPenalty: Double,
      val fill: FillPolicy
  ):
    val fingerprint: Checksum = MappingRender.digest(
      Vector(
        "reconstruction/decision/v1",
        order.toString,
        CanonicalDouble.render(backwardPenalty),
        CanonicalDouble.render(forwardPenalty),
        fill.toString
      )
    )
    override def equals(other: Any): Boolean = other match
      case p: DecisionPolicy => fingerprint == p.fingerprint
      case _                 => false
    override def hashCode: Int = fingerprint.hashCode
    override def toString: String = s"DecisionPolicy($order, $fill)"

  object DecisionPolicy:
    def of(
        order: OrderPolicy = OrderPolicy.Unconstrained,
        backwardPenalty: Double = 0.0,
        forwardPenalty: Double = 0.0,
        fill: FillPolicy = FillPolicy.Disabled
    ): Either[AlignError, DecisionPolicy] =
      if !backwardPenalty.isFinite || backwardPenalty < 0.0 ||
        !forwardPenalty.isFinite || forwardPenalty < 0.0
      then Left(refused("decision penalties must be finite and nonnegative"))
      else if order != OrderPolicy.PenalizedBackward && backwardPenalty != 0.0 then
        Left(refused("backward penalty requires penalized-backward order"))
      else if order != OrderPolicy.Nondecreasing && forwardPenalty != 0.0 then
        Left(refused("historical forward penalty applies only to nondecreasing order"))
      else if order == OrderPolicy.Unconstrained && fill != FillPolicy.Disabled then
        Left(refused("fill requires a group assignment"))
      else Right(new DecisionPolicy(order, plain(backwardPenalty), plain(forwardPenalty), fill))

  /** All inference and decision parameters contribute to this versioned profile identity. */
  final class Profile private[StructuredReconstruction] (
      val inference: HsmmConfig,
      val decision: DecisionPolicy,
      val historicalSherlock: Boolean
  ):
    val fingerprint: Checksum = MappingRender.digest(
      Vector(
        "structured-reconstruction/profile/v1",
        inference.fingerprint.hex,
        decision.fingerprint.hex,
        historicalSherlock.toString
      )
    )
    val kind: MeasureKind = MeasureKind.ModelPosterior
    override def equals(other: Any): Boolean = other match
      case p: Profile => fingerprint == p.fingerprint
      case _          => false
    override def hashCode: Int = fingerprint.hashCode
    override def toString: String = s"Reconstruction.Profile(${fingerprint.short()})"

  object Profile:
    def of(inference: HsmmConfig, decision: DecisionPolicy): Either[AlignError, Profile] =
      if !inference.refinementWeight.isFinite then Left(refused("refinement weight must be finite"))
      else Right(new Profile(inference, decision, false))

    /** Frozen historical transition scaling; fill remains an explicit caller request. */
    def sherlock(fill: FillPolicy = FillPolicy.Disabled): Profile =
      val scaled = Set(
        TransitionKind.DiscourseSuccessor,
        TransitionKind.WorldTimeSuccessor,
        TransitionKind.Backward,
        TransitionKind.LongJump
      )
      val model = TransitionModel(TransitionModel.default.theta.map { (k, v) =>
        k -> (if scaled(k) then v * 1.5 else v)
      })
      val inference = HsmmConfig.of(transitions = model).toOption.get
      val decision = DecisionPolicy.of(OrderPolicy.Nondecreasing, fill = fill).toOption.get
      new Profile(inference, decision, true)

  /** Declared root ranks are bound to a source; membership is derived from its parent graph. */
  final class Grouping private[StructuredReconstruction] (
      private[StructuredReconstruction] val source: SourceView,
      val roots: Vector[(SourceNodeRef, Int)],
      val membership: Map[SourceNodeRef, Int]
  ):
    val fingerprint: Checksum = MappingRender.digest(
      Vector(
        "reconstruction/grouping/v1",
        ViewFingerprint.of(source).checksum.hex,
        MappingSourceRender.scope(source.nodes).hex,
        MappingRender.sequence(
          roots.map((ref, rank) => MappingRender.sequence(Vector(ref.key, rank.toString)))
        )
      )
    )
    override def equals(other: Any): Boolean = other match
      case g: Grouping => fingerprint == g.fingerprint
      case _           => false
    override def hashCode: Int = fingerprint.hashCode
    override def toString: String = s"Reconstruction.Grouping(roots=${roots.size})"

  object Grouping:
    def of(source: SourceView, roots: Vector[(SourceNodeRef, Int)]): Either[AlignError, Grouping] =
      val view = MappingBindingRender.snapshot(source)
      val byRef = view.nodes.map(n => n.ref -> n).toMap
      val declared = roots.toMap
      def memberships(ref: SourceNodeRef): Either[AlignError, Vector[Int]] =
        var cur = Option(ref)
        var seen = Set.empty[SourceNodeRef]
        var found = Vector.empty[Int]
        while cur.nonEmpty do
          val r = cur.get
          if seen(r) || !byRef.contains(r) then
            return Left(refused("cyclic or foreign group ancestry"))
          seen += r
          declared.get(r).foreach(rank => found :+= rank)
          cur = byRef(r).parent
        if found.size > 1 then Left(refused("declared groups overlap")) else Right(found)
      if byRef.size != view.nodes.size || roots.map(_._1).distinct.size != roots.size ||
        roots.map(_._2).distinct.size != roots.size || roots.exists((r, _) => !byRef.contains(r))
      then Left(refused("group roots and ranks must be unique source members"))
      else
        val mapped = view.nodes.map(n => memberships(n.ref).map(rs => n.ref -> rs.headOption))
        mapped.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None    =>
            Right(
              new Grouping(
                view,
                roots.sortBy(_._2),
                mapped.collect { case Right((ref, Some(rank))) => ref -> rank }.toMap
              )
            )

  /** Requested fill is a declared reconstruction selection, never an observed base score. */
  final class FillRequest private[StructuredReconstruction] (
      val grouping: Grouping,
      val recallBinding: Checksum,
      val targets: Map[(RecallUnitId, Int), SourceNodeRef]
  ):
    val fingerprint: Checksum = MappingRender.digest(
      Vector(
        "reconstruction/requested-fill/v1",
        grouping.fingerprint.hex,
        recallBinding.hex,
        MappingRender.sequence(
          targets.toVector
            .sortBy { case ((u, g), _) => (u.value, g) }
            .map { case ((u, g), ref) =>
              MappingRender.sequence(Vector(u.value, g.toString, ref.key))
            }
        )
      )
    )
    override def equals(other: Any): Boolean = other match
      case f: FillRequest => fingerprint == f.fingerprint
      case _              => false
    override def hashCode: Int = fingerprint.hashCode
    override def toString: String = s"Reconstruction.FillRequest(targets=${targets.size})"

  object FillRequest:
    def of(
        recall: RecallGraph[Checked],
        grouping: Grouping,
        targets: Vector[((RecallUnitId, Int), SourceNodeRef)]
    ): Either[AlignError, FillRequest] =
      if targets.exists { case ((unit, _), _) => !recall.ordered.exists(_.id == unit) } then
        Left(refused("foreign fill unit"))
      else if targets.map(_._1).distinct.size != targets.size then
        Left(refused("duplicate fill request"))
      else if targets.exists { case ((_, group), ref) =>
          !grouping.membership.get(ref).contains(group) || !grouping.source
            .node(ref)
            .exists(_.isLeaf)
        }
      then Left(refused("fill target must be a leaf in the requested group"))
      else Right(new FillRequest(grouping, recallIdentity(recall), targets.toMap))

  /** Raw state maximum and chosen anchor mass refer to their own destinations, never each other. */
  final class Decision private[StructuredReconstruction] (
      val unit: RecallUnitId,
      val rawArgmax: Option[AlignState],
      val rawArgmaxMass: Option[Double],
      val rawSourceMaximum: Option[SourceNodeRef],
      val anchor: Option[SourceNodeRef],
      val chosenMass: Option[Double],
      val assignedGroup: Option[Int],
      val origin: Origin
  ):
    def constrained: Boolean = origin != Origin.RawSourceMaximum
    private def parts =
      (unit, rawArgmax, rawArgmaxMass, rawSourceMaximum, anchor, chosenMass, assignedGroup, origin)
    override def equals(other: Any): Boolean = other match
      case d: Decision => parts == d.parts
      case _           => false
    override def hashCode: Int = parts.hashCode
    override def toString: String = s"Reconstruction.Decision($unit, $anchor, $origin)"

  /** Executing the producer binds a posterior and decisions to the original immutable evidence. */
  final class Result private[StructuredReconstruction] (
      val evidence: LocalEvidence,
      val profile: Profile,
      val grouping: Grouping,
      val fill: Option[FillRequest],
      val inference: HsmmResult,
      val decisions: Vector[Decision]
  ):
    def evidenceId: LocalEvidenceId = evidence.identity
    private def parts = (evidence, profile, grouping, fill, inference, decisions)
    override def equals(other: Any): Boolean = other match
      case r: Result => parts == r.parts
      case _         => false
    override def hashCode: Int = parts.hashCode
    override def toString: String = s"Reconstruction.Result(units=${decisions.size})"

  def compute(
      recall: RecallGraph[Checked],
      source: SourceView,
      evidence: LocalEvidence,
      profile: Profile,
      grouping: Grouping,
      fill: Option[FillRequest] = None
  ): Either[AlignError, Result] =
    val view = MappingBindingRender.snapshot(source)
    for
      _ <- LocalEvidence.bound(evidence, recall, view, gate = true)
      _ <- Either.cond(
        ViewFingerprint.of(view) == ViewFingerprint.of(grouping.source) &&
          MappingSourceRender.scope(view.nodes) == MappingSourceRender.scope(grouping.source.nodes),
        (),
        refused("grouping belongs to a different source")
      )
      _ <- Either.cond(
        fill.forall(_.recallBinding == recallIdentity(recall)),
        (),
        refused("fill belongs to different recall content")
      )
      result <- GraphHsmm.infer(recall, view, evidence, profile.inference)
      decisions <- decide(result.posterior.rows, grouping, profile.decision, fill)
    yield new Result(evidence, profile, grouping, fill, result, decisions)

  /** Pure post-inference operation; it cannot produce reference or provider execution authority. */
  def decide(
      rows: Vector[AlignmentRow],
      grouping: Grouping,
      policy: DecisionPolicy,
      fill: Option[FillRequest] = None
  ): Either[AlignError, Vector[Decision]] =
    val units = rows.map(_.unit).toSet
    if !rows.map(_.total).sum.isFinite || rows.map(_.unit).distinct.size != rows.size || rows
        .exists(r =>
          !r.isWellFormed || !r.total.isFinite || !r.anchorMass.values.forall(_.isFinite) ||
            r.mass.keys.flatMap(_.anchor).exists(ref => grouping.source.node(ref).isEmpty)
        )
    then Left(refused("rows contain duplicate units, invalid mass or foreign source targets"))
    else if fill.exists(f =>
        policy.fill == FillPolicy.Disabled || f.grouping != grouping ||
          f.targets.keys.exists((unit, _) => !units(unit))
      )
    then Left(refused("fill is disabled or belongs to different grouping/units"))
    else decode(rows, grouping, policy, fill)

  private def decision(
      row: AlignmentRow,
      anchor: Option[SourceNodeRef],
      group: Option[Int],
      origin: Origin
  ): Decision =
    new Decision(
      row.unit,
      row.argmax,
      row.argmax.map(row.apply),
      row.mapSource,
      anchor,
      anchor.map(row.sourceMassOn),
      group,
      origin
    )

  private def refused(detail: String): AlignError =
    AlignError.InvalidConfig("reconstruction", detail)
  private def recallIdentity(recall: RecallGraph[Checked]): Checksum =
    MappingRender.digest(
      Vector(AlignWire.recallChecksum(recall).hex, MappingBindingRender.recall(recall).hex)
    )
  private def plain(v: Double): Double = if v == 0.0 then 0.0 else v

  private def decode(
      rows: Vector[AlignmentRow],
      grouping: Grouping,
      policy: DecisionPolicy,
      fill: Option[FillRequest]
  ): Either[AlignError, Vector[Decision]] =
    val massByScene: Vector[Map[Int, Double]] = rows.map { r =>
      r.anchorMass.toVector
        .flatMap { case (ref, m) => grouping.membership.get(ref).filter(_ => m > 0.0).map(_ -> m) }
        .groupMapReduce(_._1)(_._2)(_ + _)
    }
    if !massByScene.forall(_.values.forall(_.isFinite)) then
      return Left(refused("group mass overflow"))
    val scenes = massByScene.flatMap(_.keys).distinct.sorted
    if rows.isEmpty || scenes.isEmpty || policy.order == OrderPolicy.Unconstrained then
      Right(rows.map(r => decision(r, r.mapSource, None, Origin.RawSourceMaximum)))
    else
      val n = rows.size
      val s = scenes.size
      val best = Array.ofDim[Double](n, s)
      val back = Array.ofDim[Int](n, s)
      var j = 0
      while j < s do
        best(0)(j) = massByScene(0).getOrElse(scenes(j), 0.0)
        j += 1
      val penalty =
        if policy.order == OrderPolicy.PenalizedBackward then Some(policy.backwardPenalty) else None
      var i = 1
      while i < n do
        penalty match
          case None =>
            // Forward only. A running maximum over earlier scene indices keeps the sweep linear in
            // the number of scenes rather than quadratic.
            val fwd = policy.forwardPenalty
            if fwd <= 0.0 then
              var runBest = Double.NegativeInfinity
              var runArg = 0
              j = 0
              while j < s do
                if best(i - 1)(j) > runBest then
                  runBest = best(i - 1)(j)
                  runArg = j
                best(i)(j) = runBest + massByScene(i).getOrElse(scenes(j), 0.0)
                back(i)(j) = runArg
                j += 1
            else
              j = 0
              while j < s do
                var bestVal = Double.NegativeInfinity
                var bestArg = 0
                var k = 0
                while k <= j do
                  // Skipping is free for the first scene advanced and priced after that, so an
                  // ordinary step forward costs nothing and only leaps are discouraged.
                  val skipped = math.max(0L, scenes(j).toLong - scenes(k).toLong - 1L)
                  val step = best(i - 1)(k) - fwd * skipped
                  if !step.isFinite then return Left(refused("forward score overflow"))
                  if step > bestVal then
                    bestVal = step
                    bestArg = k
                  k += 1
                best(i)(j) = bestVal + massByScene(i).getOrElse(scenes(j), 0.0)
                back(i)(j) = bestArg
                j += 1
          case Some(lambda) =>
            // Backward steps allowed at a price, so strong evidence can buy one. Quadratic in the
            // number of scenes, which is fifty here.
            j = 0
            while j < s do
              var bestVal = Double.NegativeInfinity
              var bestArg = 0
              var k = 0
              while k < s do
                val step = best(i - 1)(k) - (if k > j then lambda * (k - j) else 0.0)
                if !step.isFinite then return Left(refused("backward score overflow"))
                if step > bestVal then
                  bestVal = step
                  bestArg = k
                k += 1
              best(i)(j) = bestVal + massByScene(i).getOrElse(scenes(j), 0.0)
              back(i)(j) = bestArg
              j += 1
        if !best(i).forall(_.isFinite) then return Left(refused("accumulated score overflow"))
        i += 1
      var cur = 0
      j = 1
      while j < s do
        if best(n - 1)(j) > best(n - 1)(cur) then cur = j
        j += 1
      val chosen = Array.ofDim[Int](n)
      i = n - 1
      while i >= 0 do
        chosen(i) = cur
        if i > 0 then cur = back(i)(cur)
        i -= 1
      Right(rows.zipWithIndex.map { case (row, idx) =>
        val scene = scenes(chosen(idx))
        val inScene = row.anchorMass.toVector.filter { case (ref, m) =>
          m > 0.0 && grouping.membership.get(ref).contains(scene)
        }
        if inScene.isEmpty then
          fill.flatMap(_.targets.get(row.unit -> scene)) match
            case Some(ref) => decision(row, Some(ref), Some(scene), Origin.RequestedFill)
            case None      => decision(row, row.mapSource, Some(scene), Origin.RawSourceMaximum)
        else
          decision(
            row,
            Some(inScene.sortBy { case (ref, m) => (-m, ref.key) }.head._1),
            Some(scene),
            Origin.GroupConstrained
          )
      })
