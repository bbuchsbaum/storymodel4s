package storymodel4s.proposition

/** Result of structurally comparing two charts.
  *
  * The graded parts (`conceptMatch`, `argumentMatch`, `partialityPenalty`, `structuralScore`) say
  * how much local content is shared. The gating flags say whether the two charts *contradict* in a
  * way that no amount of similarity should override (design record §49.1). They are kept out of the
  * score on purpose: a consumer decides how to gate.
  */
final case class CompatibilityReport(
    conceptMatch: Double,
    argumentMatch: Double,
    partialityPenalty: Double,
    roleReversal: Boolean,
    polarityConflict: Boolean,
    embeddingConflict: Boolean,
    matchedPredicates: Vector[(ConceptId, ConceptId)]
):
  /** Graded compatibility in `[0, 1]`, excluding gates. */
  def structuralScore: Double =
    val s = 0.45 * conceptMatch + 0.4 * argumentMatch + 0.15 * (1.0 - partialityPenalty)
    math.max(0.0, math.min(1.0, s))

  def gated: Boolean = roleReversal || polarityConflict || embeddingConflict
  def gates: Set[String] =
    Set(
      Option.when(roleReversal)("role-reversal"),
      Option.when(polarityConflict)("polarity-conflict"),
      Option.when(embeddingConflict)("embedding-conflict")
    ).flatten

/** Structural adjudication of two local charts: the primitive `align` uses to rerank candidates
  * that embeddings retrieved. Pure and deterministic; symmetric in its graded parts and gates.
  */
object ChartCompatibility:
  private val SenseMismatch = 0.6

  /** How well two concepts match: frame identity when both have frames, else lemma identity. */
  def conceptScore(x: Concept, y: Concept): Double =
    if x.isUnknown && y.isUnknown then 1.0
    else if x.isUnknown || y.isUnknown then 0.0
    else
      (x.frame, y.frame) match
        case (Some(f), Some(g)) if f.key == g.key        => 1.0
        case (Some(_), Some(_)) if x.lemma == y.lemma    => SenseMismatch
        case (Some(_), Some(_))                          => 0.0
        case _ if x.lemma == y.lemma                     => 1.0
        case _ if x.gloss.nonEmpty && x.gloss == y.gloss => 0.8
        case _                                           => 0.0

  private def roleEquivalent(p: RoleAssignment, q: RoleAssignment): Boolean =
    p.source == q.source ||
      (p.normalizedRole.nonEmpty && p.normalizedRole == q.normalizedRole)

  private def targetScore[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B],
      ta: ConceptTarget,
      tb: ConceptTarget
  ): Double =
    (ta, tb) match
      case (ConceptTarget.Node(x), ConceptTarget.Node(y)) =>
        conceptScore(a.concepts(x), b.concepts(y))
      case (ConceptTarget.Literal(x), ConceptTarget.Literal(y)) => if x == y then 1.0 else 0.0
      case (ConceptTarget.Unknown, ConceptTarget.Unknown)       => 1.0
      case _                                                    => 0.0

  private def isUnknownTarget[C <: CheckState](chart: PropositionChart[C], t: ConceptTarget) =
    t match
      case ConceptTarget.Unknown  => true
      case ConceptTarget.Node(id) => chart.concepts(id).isUnknown
      case _                      => false

  /** Units of comparison: predicates, or all concepts when a chart has no predicates. */
  private def heads[C <: CheckState](chart: PropositionChart[C]): Vector[ConceptId] =
    val p = chart.predicates
    if p.nonEmpty then p
    else
      val known = chart.conceptIds.filterNot(id => chart.concepts(id).isUnknown)
      if known.nonEmpty then known else chart.conceptIds

  private def directional[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): CompatibilityReport =
    val ha = heads(a)
    val hb = heads(b)
    if ha.isEmpty || hb.isEmpty then
      return CompatibilityReport(0.0, 0.0, 0.0, false, false, false, Vector.empty)

    // Evaluate every head pair once, then choose for each head of `a` the partner offering the
    // best reading: highest concept score, then best argument agreement, then fewest gates.
    val evals: Map[(ConceptId, ConceptId), PairEval] =
      (for pa <- ha; pb <- hb yield (pa, pb) -> evalPair(a, b, pa, pb)).toMap
    val pairs = ha.map { pa =>
      val best = hb.maxBy { pb =>
        val e = evals((pa, pb))
        (e.concept, e.argRatio, -e.gateCount, pb)
      }
      (pa, best, evals((pa, best)))
    }
    val matched = pairs.filter(_._3.concept > 0.0)
    val conceptMatch = pairs.map(_._3.concept).sum / ha.size

    var argTotal = 0
    var argSum = 0.0
    var unknownMismatch = 0
    var reversal = false
    var polarityConflict = false
    var embeddingConflict = false

    matched.foreach { (_, _, e) =>
      argTotal += e.argTotal
      argSum += e.argSum
      unknownMismatch += e.unknownMismatch
      reversal ||= e.reversal
      polarityConflict ||= e.polarityConflict
      embeddingConflict ||= e.embeddingConflict
    }

    val argumentMatch = if argTotal == 0 then conceptMatch else argSum / argTotal
    val partiality = if argTotal == 0 then 0.0 else unknownMismatch.toDouble / argTotal
    CompatibilityReport(
      conceptMatch,
      argumentMatch,
      partiality,
      reversal,
      polarityConflict,
      embeddingConflict,
      matched.map(m => (m._1, m._2))
    )

  private final case class PairEval(
      concept: Double,
      argSum: Double,
      argTotal: Int,
      unknownMismatch: Int,
      reversal: Boolean,
      polarityConflict: Boolean,
      embeddingConflict: Boolean
  ):
    def argRatio: Double = if argTotal == 0 then 1.0 else argSum / argTotal
    def gateCount: Int =
      Seq(reversal, polarityConflict, embeddingConflict).count(identity)

  private def evalPair[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B],
      pa: ConceptId,
      pb: ConceptId
  ): PairEval =
    val concept = conceptScore(a.concepts(pa), b.concepts(pb))
    val ra = a.relationsFrom(pa)
    val rb = b.relationsFrom(pb)
    var argSum = 0.0
    var unknownMismatch = 0
    // Greedy one-to-one assignment of role-equivalent relations, best filler match first.
    val used = scala.collection.mutable.HashSet.empty[Int]
    ra.foreach { x =>
      val candidates = rb.zipWithIndex.filter((y, i) => !used(i) && roleEquivalent(x.role, y.role))
      if candidates.nonEmpty then
        val (y, i) = candidates.maxBy((y, i) => (targetScore(a, b, x.to, y.to), -i))
        used += i
        argSum += targetScore(a, b, x.to, y.to)
        if isUnknownTarget(a, x.to) != isUnknownTarget(b, y.to) then unknownMismatch += 1
    }
    // Role reversal: two distinct roles whose fillers match better crosswise than straight.
    var reversal = false
    for x1 <- ra; x2 <- ra if x1.role.source != x2.role.source
    do
      val c1 = rb.filter(y => roleEquivalent(x1.role, y.role))
      val c2 = rb.filter(y => roleEquivalent(x2.role, y.role))
      val pairs = for y1 <- c1; y2 <- c2 if y1 != y2 yield (y1, y2)
      if pairs.nonEmpty then
        val straight = pairs
          .map((y1, y2) => targetScore(a, b, x1.to, y1.to) + targetScore(a, b, x2.to, y2.to))
          .max
        val crossed = pairs
          .map((y1, y2) => targetScore(a, b, x1.to, y2.to) + targetScore(a, b, x2.to, y1.to))
          .max
        val distinctFillers = targetScore(a, a, x1.to, x2.to) < 1.0
        if distinctFillers && crossed >= 2.0 && straight < crossed then reversal = true

    val polarityConflict = (a.polarityOf(pa), b.polarityOf(pb)) match
      case (Polarity.Positive, Polarity.Negative) | (Polarity.Negative, Polarity.Positive) => true
      case _                                                                               => false

    val embeddingConflict = (a.embeddingOf(pa).map(_.kind), b.embeddingOf(pb).map(_.kind)) match
      case (Some(_), None) | (None, Some(_)) => true
      case (Some(k1), Some(k2))              =>
        k1 != k2 && k1 != EmbeddingKind.Unknown && k2 != EmbeddingKind.Unknown
      case _ => false

    PairEval(
      concept,
      argSum,
      ra.size,
      unknownMismatch,
      reversal,
      polarityConflict,
      embeddingConflict
    )

  /** Symmetric comparison: graded parts averaged over both directions, gates OR-ed. */
  def compare[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): CompatibilityReport =
    val ab = directional(a, b)
    val ba = directional(b, a)
    CompatibilityReport(
      (ab.conceptMatch + ba.conceptMatch) / 2,
      (ab.argumentMatch + ba.argumentMatch) / 2,
      (ab.partialityPenalty + ba.partialityPenalty) / 2,
      ab.roleReversal || ba.roleReversal,
      ab.polarityConflict || ba.polarityConflict,
      ab.embeddingConflict || ba.embeddingConflict,
      ab.matchedPredicates
    )
