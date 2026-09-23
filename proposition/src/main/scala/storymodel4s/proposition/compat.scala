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
    CompareCore.structuralScore(conceptMatch, argumentMatch, partialityPenalty)

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

  /** How well two concepts match: frame identity when both have frames, else lemma identity. */
  def conceptScore(x: Concept, y: Concept): Double =
    CompareCore.conceptScore(
      x.isUnknown,
      x.frame.map(_.key),
      x.lemma.value,
      x.gloss,
      y.isUnknown,
      y.frame.map(_.key),
      y.lemma.value,
      y.gloss
    )

  /** Symmetric comparison: graded parts averaged over both directions, gates OR-ed. */
  def compare[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): CompatibilityReport =
    val r = CompareCore.compare(ChartView(a), ChartView(b), TiePolicy.Historical)
    val g = r.certain // Historical: the one reading
    CompatibilityReport(
      r.conceptMatch,
      r.argumentMatch,
      r.partialityPenalty,
      g.roleReversal,
      g.polarityConflict,
      g.embeddingConflict,
      r.matched
    )

/** A chart seen through the accessors the compare core reads, in its own storage order: heads by
  * `ConceptId`, relations as stored. This is the order today's `compare` has always used.
  */
private[proposition] final class ChartView[C <: CheckState](chart: PropositionChart[C])
    extends CompareView[ConceptId, PropositionRelation]:
  val order: Ordering[ConceptId] = summon[Ordering[ConceptId]]

  /** Units of comparison: predicates, or all concepts when a chart has no predicates. */
  val heads: Vector[ConceptId] =
    val p = chart.predicates
    if p.nonEmpty then p
    else
      val known = chart.conceptIds.filterNot(id => chart.concepts(id).isUnknown)
      if known.nonEmpty then known else chart.conceptIds

  def isUnknown(n: ConceptId): Boolean = chart.concepts(n).isUnknown
  def frameKey(n: ConceptId): Option[(String, String)] = chart.concepts(n).frame.map(_.key)
  def lemma(n: ConceptId): String = chart.concepts(n).lemma.value
  def gloss(n: ConceptId): Option[String] = chart.concepts(n).gloss
  def relationsFrom(n: ConceptId): Vector[PropositionRelation] = chart.relationsFrom(n)
  def source(r: PropositionRelation): SourceRole = r.role.source
  def normalized(r: PropositionRelation): Option[ParticipantRole] = r.role.normalizedRole
  def target(r: PropositionRelation): CompareTarget[ConceptId] = r.to match
    case ConceptTarget.Node(id)     => CompareTarget.Node(id)
    case ConceptTarget.Literal(lit) => CompareTarget.Literal(lit)
    case ConceptTarget.Unknown      => CompareTarget.Unknown
  def polarity(n: ConceptId): Polarity = chart.polarityOf(n)
  def embeddingKinds(n: ConceptId): Set[EmbeddingKind] = chart.embeddingKinds(n)

/** A relation filler as the compare core sees it: a concept of the same graph, a literal, or
  * explicitly unknown.
  */
private[proposition] enum CompareTarget[+N]:
  case Node(n: N)
  case Literal(value: LiteralValue)
  case Unknown

/** What the compare core reads from one side. `N` is a concept handle and `R` a relation. Under
  * [[TiePolicy.Historical]] the order on `N`, and the relation order, break ties, so they decide
  * whether the result depends on ids. Under [[TiePolicy.OrderFree]] the order only sorts `matched`.
  */
private[proposition] trait CompareView[N, R]:
  def order: Ordering[N]
  def heads: Vector[N]
  def isUnknown(n: N): Boolean
  def frameKey(n: N): Option[(String, String)]
  def lemma(n: N): String
  def gloss(n: N): Option[String]
  def relationsFrom(n: N): Vector[R]
  def source(r: R): SourceRole
  def normalized(r: R): Option[ParticipantRole]
  def target(r: R): CompareTarget[N]
  def polarity(n: N): Polarity
  def embeddingKinds(n: N): Set[EmbeddingKind]

/** How the compare core resolves choices that its scores leave tied.
  *
  * Why two policies: the chart path's published output breaks ties by view order, and historical
  * parity pins it (S2a-0). The strict path must not: over a canonical graph, view order is a
  * content-hash colouring that moves with focus and with lemmas that score nothing, a hidden prior
  * ADR 0019 rejects.
  */
private[proposition] enum TiePolicy:
  /** Greedy relation assignment and the view's order as the last tie-break (the chart path). */
  case Historical

  /** Every choice is the optimum of a fixed objective over all admissible choices, and choices that
    * stay equally optimal are aggregated, never picked: see [[CompareCore]].
    */
  case OrderFree

/** Directional result of the core, before a caller decides how to name matched heads. */
private[proposition] final case class CoreReport[NA, NB](
    conceptMatch: Double,
    argumentMatch: Double,
    partialityPenalty: Double,
    gateReadings: Set[GateReading],
    matched: Vector[(NA, NB)]
):
  /** Each gate raised in every best reading. A singleton set under [[TiePolicy.Historical]]. */
  def certain: GateReading = gateReadings.reduce(_ & _)

/** One combination of the three contradiction gates, under one best reading of a comparison.
  *
  * Why a plain case class: the gates are independent, and all eight combinations are lawful
  * readings, so no combination of field values is false (the rule 8 Cartesian-product test).
  */
final case class GateReading(
    roleReversal: Boolean,
    polarityConflict: Boolean,
    embeddingConflict: Boolean
):
  def gated: Boolean = roleReversal || polarityConflict || embeddingConflict
  def |(o: GateReading): GateReading =
    GateReading(
      roleReversal || o.roleReversal,
      polarityConflict || o.polarityConflict,
      embeddingConflict || o.embeddingConflict
    )
  def &(o: GateReading): GateReading =
    GateReading(
      roleReversal && o.roleReversal,
      polarityConflict && o.polarityConflict,
      embeddingConflict && o.embeddingConflict
    )

object GateReading:
  val None: GateReading = GateReading(false, false, false)

/** The one implementation of chart comparison. [[ChartCompatibility]] runs it over charts in
  * storage order and [[SemanticCompatibility]] over projected graphs, so the two cannot drift.
  */
private[proposition] object CompareCore:
  private val SenseMismatch = 0.6

  def structuralScore(conceptMatch: Double, argumentMatch: Double, partiality: Double): Double =
    val s = 0.45 * conceptMatch + 0.4 * argumentMatch + 0.15 * (1.0 - partiality)
    math.max(0.0, math.min(1.0, s))

  def conceptScore(
      xUnknown: Boolean,
      xFrame: Option[(String, String)],
      xLemma: String,
      xGloss: Option[String],
      yUnknown: Boolean,
      yFrame: Option[(String, String)],
      yLemma: String,
      yGloss: Option[String]
  ): Double =
    if xUnknown && yUnknown then 1.0
    else if xUnknown || yUnknown then 0.0
    else
      (xFrame, yFrame) match
        case (Some(f), Some(g)) if f == g             => 1.0
        case (Some(_), Some(_)) if xLemma == yLemma   => SenseMismatch
        case (Some(_), Some(_))                       => 0.0
        case _ if xLemma == yLemma                    => 1.0
        case _ if xGloss.nonEmpty && xGloss == yGloss => 0.8
        case _                                        => 0.0

  private def nodeScore[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      x: NA,
      vb: CompareView[NB, RB],
      y: NB
  ): Double =
    conceptScore(
      va.isUnknown(x),
      va.frameKey(x),
      va.lemma(x),
      va.gloss(x),
      vb.isUnknown(y),
      vb.frameKey(y),
      vb.lemma(y),
      vb.gloss(y)
    )

  private def roleEquivalent[RA, RB](
      va: CompareView[?, RA],
      p: RA,
      vb: CompareView[?, RB],
      q: RB
  ): Boolean =
    va.source(p) == vb.source(q) ||
      (va.normalized(p).nonEmpty && va.normalized(p) == vb.normalized(q))

  private def targetScore[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      ta: CompareTarget[NA],
      vb: CompareView[NB, RB],
      tb: CompareTarget[NB]
  ): Double =
    (ta, tb) match
      case (CompareTarget.Node(x), CompareTarget.Node(y))       => nodeScore(va, x, vb, y)
      case (CompareTarget.Literal(x), CompareTarget.Literal(y)) => if x == y then 1.0 else 0.0
      case (CompareTarget.Unknown, CompareTarget.Unknown)       => 1.0
      case _                                                    => 0.0

  private def isUnknownTarget[N](v: CompareView[N, ?], t: CompareTarget[N]): Boolean =
    t match
      case CompareTarget.Unknown => true
      case CompareTarget.Node(n) => v.isUnknown(n)
      case _                     => false

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

  private def directional[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      vb: CompareView[NB, RB]
  ): CoreReport[NA, NB] =
    val ha = va.heads
    val hb = vb.heads
    if ha.isEmpty || hb.isEmpty then
      return CoreReport(0.0, 0.0, 0.0, Set(GateReading.None), Vector.empty)

    // Evaluate every head pair once, then choose for each head of `a` the partner offering the
    // best reading: highest concept score, then best argument agreement, then fewest gates. The
    // last key is the view's own order on heads: that is where ids can enter.
    given Ordering[NB] = vb.order
    val evals: Map[(NA, NB), PairEval] =
      (for pa <- ha; pb <- hb yield (pa, pb) -> evalPair(va, vb, pa, pb)).toMap
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
    CoreReport(
      conceptMatch,
      argumentMatch,
      partiality,
      Set(GateReading(reversal, polarityConflict, embeddingConflict)),
      matched.map(m => (m._1, m._2))
    )

  private def evalPair[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      vb: CompareView[NB, RB],
      pa: NA,
      pb: NB
  ): PairEval =
    val concept = nodeScore(va, pa, vb, pb)
    val ra = va.relationsFrom(pa)
    val rb = vb.relationsFrom(pb)
    def ts(x: RA, y: RB): Double = targetScore(va, va.target(x), vb, vb.target(y))
    var argSum = 0.0
    var unknownMismatch = 0
    // Greedy one-to-one assignment of role-equivalent relations, best filler match first. Ties go
    // to the earlier relation of `b`, so the view's relation order is observable here.
    val used = scala.collection.mutable.HashSet.empty[Int]
    ra.foreach { x =>
      val candidates =
        rb.zipWithIndex.filter((y, i) => !used(i) && roleEquivalent(va, x, vb, y))
      if candidates.nonEmpty then
        val (y, i) = candidates.maxBy((y, i) => (ts(x, y), -i))
        used += i
        argSum += ts(x, y)
        if isUnknownTarget(va, va.target(x)) != isUnknownTarget(vb, vb.target(y)) then
          unknownMismatch += 1
    }
    // Role reversal: two distinct roles whose fillers match better crosswise than straight.
    var reversal = false
    for x1 <- ra; x2 <- ra if va.source(x1) != va.source(x2)
    do
      val c1 = rb.filter(y => roleEquivalent(va, x1, vb, y))
      val c2 = rb.filter(y => roleEquivalent(va, x2, vb, y))
      val pairs = for y1 <- c1; y2 <- c2 if y1 != y2 yield (y1, y2)
      if pairs.nonEmpty then
        val straight = pairs.map((y1, y2) => ts(x1, y1) + ts(x2, y2)).max
        val crossed = pairs.map((y1, y2) => ts(x1, y2) + ts(x2, y1)).max
        val distinctFillers = targetScore(va, va.target(x1), va, va.target(x2)) < 1.0
        if distinctFillers && crossed >= 2.0 && straight < crossed then reversal = true

    val polarityConflict = (va.polarity(pa), vb.polarity(pb)) match
      case (Polarity.Positive, Polarity.Negative) | (Polarity.Negative, Polarity.Positive) => true
      case _                                                                               => false

    // A concept may be held under several embeddings (said *and* believed): the pair conflicts
    // when exactly one side is embedded, or when both are but share no kind (Unknown matches any).
    val ka = va.embeddingKinds(pa)
    val kb = vb.embeddingKinds(pb)
    val embeddingConflict =
      if ka.isEmpty != kb.isEmpty then true
      else if ka.isEmpty then false
      else
        !(ka.contains(EmbeddingKind.Unknown) || kb.contains(EmbeddingKind.Unknown) ||
          ka.exists(kb.contains))

    PairEval(
      concept,
      argSum,
      ra.size,
      unknownMismatch,
      reversal,
      polarityConflict,
      embeddingConflict
    )

  // --- TiePolicy.OrderFree ----------------------------------------------------------------------
  //
  // Scores are multiples of 0.1 (conceptScore's constants, and 0/1 for literals and unknowns), so
  // the order-free path sums them as integer tenths: a Double sum would depend on summation order
  // in its last bit, and summation order is exactly what this path must not read.

  private def tenths(x: Double): Long = math.round(x * 10)

  /** One relation assignment's content: argument agreement in tenths, and filler-status mismatches.
    */
  private final case class Assigned(argSum10: Long, unknownMismatch: Int)

  /** The best one-to-one assignment of role-equivalent relations under a fixed lexicographic
    * objective: most argument agreement, then most roles matched (partiality counts a role present
    * on both sides whose filler is unknown on one), then fewest such mismatches. The optimum's
    * value is unique, and it determines both returned numbers, so neither depends on relation
    * order. Greedy is not order-free even without ties: the relation visited first takes its best
    * partner.
    */
  private def assignOrderFree[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      ra: Vector[RA],
      vb: CompareView[NB, RB],
      rb: Vector[RB]
  ): Assigned =
    val s = math.min(ra.size, rb.size).toLong
    if s == 0 then return Assigned(0L, 0)
    // w = ts10 * k2 + k1 - mismatch. k1 > s bounds the mismatch total, k2 > k1 * s bounds the
    // coverage term, so the three objectives never trade against each other. Every admissible
    // edge weighs at least k1 - 1 >= 1, so leaving a pair unmatched is never better.
    val k1 = s + 1
    val k2 = k1 * (s + 1)
    def mismatch(x: RA, y: RB): Int =
      if isUnknownTarget(va, va.target(x)) != isUnknownTarget(vb, vb.target(y)) then 1 else 0
    def ts10(x: RA, y: RB): Long = tenths(targetScore(va, va.target(x), vb, vb.target(y)))
    val weight: Array[Array[Long]] = Array.tabulate(ra.size, rb.size) { (i, j) =>
      if roleEquivalent(va, ra(i), vb, rb(j)) then
        ts10(ra(i), rb(j)) * k2 + k1 - mismatch(ra(i), rb(j))
      else 0L
    }
    val total = MaxWeightAssignment.solve(weight)
    val ts = total / k2
    val cover = total % k2
    val matchedCount = (cover + k1 - 1) / k1
    Assigned(ts, (matchedCount * k1 - cover).toInt)

  private final case class PairEvalOF(
      concept10: Long,
      argSum10: Long,
      argTotal: Int,
      unknownMismatch: Int,
      reversal: Boolean,
      polarityConflict: Boolean,
      embeddingConflict: Boolean
  ):
    def gateCount: Int = Seq(reversal, polarityConflict, embeddingConflict).count(identity)

  private def evalPairOrderFree[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      vb: CompareView[NB, RB],
      pa: NA,
      pb: NB
  ): PairEvalOF =
    val h = evalPair(va, vb, pa, pb) // gates are max/any over all relation pairs: order-free
    val ra = va.relationsFrom(pa)
    val a = assignOrderFree(va, ra, vb, vb.relationsFrom(pb))
    PairEvalOF(
      tenths(h.concept),
      a.argSum10,
      ra.size,
      a.unknownMismatch,
      h.reversal,
      h.polarityConflict,
      h.embeddingConflict
    )

  /** [[directional]] without the view order. Each head of `a` takes every partner that is best by
    * (concept, argument agreement, fewest gates, fewest filler mismatches). Those partners agree on
    * every graded quantity, since `argTotal` is fixed by the head of `a`, so the graded report is
    * unique. They can still differ in WHICH gate they raise. That is an ambiguity, not a fact, so
    * it is kept: `gateReadings` holds the gate combination of every choice of best partners (at
    * most eight). Picking one would read the order; unioning them would report conflicts that no
    * single best reading has. Either every reading is gated or none is, because tied partners raise
    * equally many gates. Every tied partner is listed in `matched`, so the matched SET does not
    * depend on order; the view order only sorts it.
    */
  private def directionalOrderFree[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      vb: CompareView[NB, RB]
  ): CoreReport[NA, NB] =
    val ha = va.heads
    val hb = vb.heads
    if ha.isEmpty || hb.isEmpty then
      return CoreReport(0.0, 0.0, 0.0, Set(GateReading.None), Vector.empty)

    val chosen = ha.map { pa =>
      val evals = hb.map(pb => pb -> evalPairOrderFree(va, vb, pa, pb))
      def key(e: PairEvalOF) = (e.concept10, e.argSum10, -e.gateCount, -e.unknownMismatch)
      val best = evals.map((_, e) => key(e)).max
      val tied = evals.filter((_, e) => key(e) == best)
      (pa, tied)
    }

    val concept10 = chosen.map(_._2.head._2.concept10).sum
    val conceptMatch = concept10.toDouble / 10 / ha.size
    val matched = chosen.filter(_._2.head._2.concept10 > 0)

    var argTotal = 0
    var argSum10 = 0L
    var unknownMismatch = 0
    var readings = Set(GateReading.None)

    matched.foreach { (_, tied) =>
      val e = tied.head._2
      argTotal += e.argTotal
      argSum10 += e.argSum10
      unknownMismatch += e.unknownMismatch
      val alternatives =
        tied.map((_, t) => GateReading(t.reversal, t.polarityConflict, t.embeddingConflict)).toSet
      readings = for r <- readings; a <- alternatives yield r | a
    }

    val argumentMatch = if argTotal == 0 then conceptMatch else argSum10.toDouble / 10 / argTotal
    val partiality = if argTotal == 0 then 0.0 else unknownMismatch.toDouble / argTotal
    val pairs = for (pa, tied) <- matched; (pb, _) <- tied yield (pa, pb)
    CoreReport(
      conceptMatch,
      argumentMatch,
      partiality,
      readings,
      pairs.sorted(using Ordering.Tuple2(using va.order, vb.order))
    )

  /** Symmetric comparison: graded parts averaged over both directions, gates OR-ed. */
  def compare[NA, RA, NB, RB](
      va: CompareView[NA, RA],
      vb: CompareView[NB, RB],
      policy: TiePolicy
  ): CoreReport[NA, NB] =
    val (ab, ba) = policy match
      case TiePolicy.Historical => (directional(va, vb), directional(vb, va))
      case TiePolicy.OrderFree  => (directionalOrderFree(va, vb), directionalOrderFree(vb, va))
    CoreReport(
      (ab.conceptMatch + ba.conceptMatch) / 2,
      (ab.argumentMatch + ba.argumentMatch) / 2,
      (ab.partialityPenalty + ba.partialityPenalty) / 2,
      for x <- ab.gateReadings; y <- ba.gateReadings yield x | y,
      ab.matched
    )

/** Maximum-weight assignment (Hungarian algorithm), exact over integer weights.
  *
  * Why exact and not greedy: the strict compare needs the optimum's value to be a function of the
  * weights alone, and greedy's answer depends on the order it visits rows. A zero weight means the
  * pair is not admissible; callers keep every admissible weight positive, so a zero-weight slot and
  * no assignment are the same thing. O(n^3) in the larger side.
  */
private[proposition] object MaxWeightAssignment:

  /** The largest total weight of a one-to-one assignment of rows to columns. */
  def solve(weight: Array[Array[Long]]): Long =
    val rows = weight.length
    val cols = if rows == 0 then 0 else weight(0).length
    val n = math.max(rows, cols)
    if n == 0 then return 0L
    def w(i: Int, j: Int): Long = if i < rows && j < cols then weight(i)(j) else 0L
    // Minimum-cost assignment on cost = -w over the padded square matrix; 1-based potentials.
    val inf = Long.MaxValue / 4
    val u = Array.fill(n + 1)(0L)
    val v = Array.fill(n + 1)(0L)
    val p = Array.fill(n + 1)(0) // p(j): the row assigned to column j
    val way = Array.fill(n + 1)(0)
    for i <- 1 to n do
      p(0) = i
      var j0 = 0
      val minv = Array.fill(n + 1)(inf)
      val used = Array.fill(n + 1)(false)
      while p(j0) != 0 do
        used(j0) = true
        val i0 = p(j0)
        var delta = inf
        var j1 = 0
        for j <- 1 to n do
          if !used(j) then
            val cur = -w(i0 - 1, j - 1) - u(i0) - v(j)
            if cur < minv(j) then
              minv(j) = cur
              way(j) = j0
            if minv(j) < delta then
              delta = minv(j)
              j1 = j
        for j <- 0 to n do
          if used(j) then
            u(p(j)) += delta
            v(j) -= delta
          else minv(j) -= delta
        j0 = j1
      while j0 != 0 do
        val j1 = way(j0)
        p(j0) = p(j1)
        j0 = j1
    (1 to n).map(j => w(p(j) - 1, j - 1)).sum
