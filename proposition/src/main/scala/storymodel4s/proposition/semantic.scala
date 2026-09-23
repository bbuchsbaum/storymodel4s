package storymodel4s.proposition

/** The order a [[SemanticGraph]] was projected under.
  *
  * Why a phantom: the strict path must never score a graph whose order came from concept ids or
  * storage, and a type parameter makes that a compile error rather than a convention.
  */
sealed trait GraphOrder
object GraphOrder:
  /** Order is a function of content alone (gloss-aware exact canonical labelling). */
  sealed trait Canonical extends GraphOrder

  /** Order is the chart's own: concepts by `ConceptId`, relations as stored. Historical parity
    * only.
    */
  sealed trait Source extends GraphOrder

/** Why a chart could not be projected canonically.
  *
  * Why typed: the only alternative to refusing is an order that depends on concept ids, which is
  * exactly what the canonical projection exists to exclude (ADR 0019, S2).
  */
enum ProjectionRefusal:
  /** Individualization–refinement exceeded `maxLeaves` leaves; the chart is too symmetric. */
  case CanonicalBudgetExhausted(maxLeaves: Int)

/** The content of a proposition chart that scoring may read, and nothing else.
  *
  * Why it exists: a chart carries alignments with text spans, sentence ids, provenance and concept
  * ids, all of which can encode position or order. This graph keeps only concepts (kind, lemma,
  * gloss, frame identity), relations (source role, normalized role, filler), polarity and embedding
  * kinds, addressed by a private index whose order is fixed by `O`. There is no accessor back to
  * the chart, and no public index. `Option[SemanticGraph]` keeps a missing chart distinct from an
  * empty one.
  */
final class SemanticGraph[O <: GraphOrder] private (
    private[proposition] val kinds: Vector[ConceptKind],
    private[proposition] val lemmas: Vector[String],
    private[proposition] val glosses: Vector[Option[String]],
    private[proposition] val frames: Vector[Option[(String, String)]],
    private[proposition] val polarities: Vector[Polarity],
    private[proposition] val embeddings: Vector[Set[EmbeddingKind]],
    private[proposition] val relationsOf: Vector[Vector[Int]],
    private[proposition] val relations: Vector[SemanticRelation],
    private[proposition] val tiePolicy: TiePolicy
):
  /** Whether the chart had no concepts. An empty chart is a real chart, not a missing one. */
  def isEmpty: Boolean = kinds.isEmpty

  private def parts: Product =
    (kinds, lemmas, glosses, frames, polarities, embeddings, relationsOf, relations, tiePolicy)

  override def equals(that: Any): Boolean = that match
    case g: SemanticGraph[?] => parts == g.parts
    case _                   => false
  override def hashCode: Int = parts.hashCode
  override def toString: String = s"SemanticGraph(${kinds.size} concepts)"

object SemanticGraph:
  private[proposition] def build[O <: GraphOrder, C <: CheckState](
      chart: PropositionChart[C],
      order: Vector[ConceptId],
      relationOrder: Vector[PropositionRelation] => Vector[PropositionRelation],
      tiePolicy: TiePolicy
  ): SemanticGraph[O] =
    val index = order.zipWithIndex.toMap
    val concepts = order.map(chart.concepts)
    val perConcept = order.map(id => relationOrder(chart.relationsFrom(id)))
    val flat = perConcept.flatten.map { r =>
      val to = r.to match
        case ConceptTarget.Node(id)     => CompareTarget.Node(index(id))
        case ConceptTarget.Literal(lit) => CompareTarget.Literal(lit)
        case ConceptTarget.Unknown      => CompareTarget.Unknown
      SemanticRelation(r.role.source, r.role.normalizedRole, to)
    }
    val offsets = perConcept.scanLeft(0)(_ + _.size)
    new SemanticGraph[O](
      concepts.map(_.kind),
      concepts.map(_.lemma.value),
      concepts.map(_.gloss),
      concepts.map(_.frame.map(_.key)),
      order.map(chart.polarityOf),
      order.map(chart.embeddingKinds),
      perConcept.indices.map(i => (offsets(i) until offsets(i + 1)).toVector).toVector,
      flat,
      tiePolicy
    )

/** One relation of a [[SemanticGraph]]: roles and filler only, with no credence or id. */
private[proposition] final case class SemanticRelation(
    source: SourceRole,
    normalized: Option[ParticipantRole],
    target: CompareTarget[Int]
)

/** Projection from charts to [[SemanticGraph]]s. */
object SemanticProjection:

  /** Content-ordered projection: concepts in gloss-aware exact canonical order, each concept's
    * relations sorted by role and canonical filler. Isomorphic charts (up to concept ids, relation
    * storage order, alignments and provenance) project to equal graphs. Refuses rather than fall
    * back when the canonical search budget is exhausted.
    */
  def canonical(
      chart: PropositionChart[Checked]
  ): Either[ProjectionRefusal, SemanticGraph[GraphOrder.Canonical]] =
    Canonical.glossedExactOrder(chart) match
      case None        => Left(ProjectionRefusal.CanonicalBudgetExhausted(Canonical.MaxLeaves))
      case Some(order) =>
        val index = order.zipWithIndex.toMap
        val label: ConceptId => String = id => f"${index(id)}%05d"
        val key: PropositionRelation => String =
          r => ChartIdentity.roleKey(r.role) + " " + ChartIdentity.targetKey(r.to, label)
        Right(SemanticGraph.build(chart, order, _.sortBy(key), TiePolicy.OrderFree))

  /** Storage-ordered projection for the historical scorers only: comparing two of these gives
    * exactly what [[ChartCompatibility.compare]] gives on the charts. Not for strict scoring.
    * Checked only, because the core tells relations apart by index where the chart path uses value
    * equality, and those agree exactly when duplicate relations are refused, as validation does.
    */
  private[storymodel4s] def sourceOrder(
      chart: PropositionChart[Checked]
  ): SemanticGraph[GraphOrder.Source] =
    SemanticGraph.build(chart, chart.conceptIds, identity, TiePolicy.Historical)

/** Result of comparing two [[SemanticGraph]]s: the graded parts and gates of a
  * [[CompatibilityReport]], with whether any head matched instead of which heads did.
  *
  * Gates can be ambiguous. When equally good partner choices raise different gates, `gateReadings`
  * holds each resulting combination. A flag means the gate is raised under EVERY best reading, so
  * an ambiguous conflict never looks established. `gated` is exact either way: tied partners raise
  * equally many gates, so either every reading is gated or none is.
  *
  * Why not `CompatibilityReport`: its matched pairs are concept ids, which a content scorer must
  * not see. Why not a case class: the fields are one computation's joint output (`matched` and
  * `conceptMatch` are related), so it is minted only by [[SemanticCompatibility.compare]].
  */
final class ContentCompatibilityReport private[proposition] (
    val conceptMatch: Double,
    val argumentMatch: Double,
    val partialityPenalty: Double,
    val gateReadings: Set[GateReading],
    val matched: Boolean
):
  require(gateReadings.nonEmpty, "a compare report needs at least one gate reading")

  private def certain: GateReading = gateReadings.reduce(_ & _)

  /** Role reversal under every best reading. */
  def roleReversal: Boolean = certain.roleReversal

  /** Polarity conflict under every best reading. */
  def polarityConflict: Boolean = certain.polarityConflict

  /** Embedding conflict under every best reading. */
  def embeddingConflict: Boolean = certain.embeddingConflict

  /** Whether equally good readings disagree on which gates are raised. */
  def gatesAmbiguous: Boolean = gateReadings.size > 1

  /** Graded compatibility in `[0, 1]`, excluding gates; the same formula as the chart report. */
  def structuralScore: Double =
    CompareCore.structuralScore(conceptMatch, argumentMatch, partialityPenalty)

  /** Some contradiction holds under every best reading. */
  def gated: Boolean = gateReadings.forall(_.gated)

  private def parts: Product =
    (conceptMatch, argumentMatch, partialityPenalty, gateReadings, matched)

  override def equals(that: Any): Boolean = that match
    case r: ContentCompatibilityReport => parts == r.parts
    case _                             => false
  override def hashCode: Int = parts.hashCode
  override def toString: String = "ContentCompatibilityReport" + parts.toString

/** Chart comparison over projected graphs; the same core as [[ChartCompatibility.compare]]. Both
  * sides must share an order policy, so a canonical graph is never scored against a source-ordered
  * one.
  */
object SemanticCompatibility:
  def compare[O <: GraphOrder](
      a: SemanticGraph[O],
      b: SemanticGraph[O]
  ): ContentCompatibilityReport =
    // Both sides share `O`, hence a tie policy: canonical graphs resolve ties order-free, and
    // source-ordered ones reproduce the chart path exactly.
    val r = CompareCore.compare(GraphView(a), GraphView(b), a.tiePolicy)
    new ContentCompatibilityReport(
      r.conceptMatch,
      r.argumentMatch,
      r.partialityPenalty,
      r.gateReadings,
      r.matched.nonEmpty
    )

/** A graph seen by the compare core: heads and relations in the graph's own index order. */
private[proposition] final class GraphView(g: SemanticGraph[?]) extends CompareView[Int, Int]:
  val order: Ordering[Int] = Ordering.Int

  /** Units of comparison: predicates, or all concepts when there are no predicates. */
  val heads: Vector[Int] =
    val all = g.kinds.indices.toVector
    val p = all.filter(i => g.kinds(i) == ConceptKind.Predicate)
    if p.nonEmpty then p
    else
      val known = all.filterNot(isUnknown)
      if known.nonEmpty then known else all

  def isUnknown(n: Int): Boolean = g.kinds(n) == ConceptKind.Unknown
  def frameKey(n: Int): Option[(String, String)] = g.frames(n)
  def lemma(n: Int): String = g.lemmas(n)
  def gloss(n: Int): Option[String] = g.glosses(n)
  def relationsFrom(n: Int): Vector[Int] = g.relationsOf(n)
  def source(r: Int): SourceRole = g.relations(r).source
  def normalized(r: Int): Option[ParticipantRole] = g.relations(r).normalized
  def target(r: Int): CompareTarget[Int] = g.relations(r).target
  def polarity(n: Int): Polarity = g.polarities(n)
  def embeddingKinds(n: Int): Set[EmbeddingKind] = g.embeddings(n)
