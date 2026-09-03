package storymodel4s.document

import cats.data.NonEmptyVector
import storymodel4s.core.*
import storymodel4s.proposition.{
  Checked,
  ConceptId,
  EmbeddedProposition,
  EmbeddingKind,
  ParticipantRole,
  PropositionChart,
  RoleAssignment
}
import storymodel4s.story.HolderGap

/** The chart nodes offered as the holder of a context, or the reason none was offered.
  *
  * Why the provider offers nodes and not an entity: entity identity is minted by the compiler, so a
  * rule reading a chart can only point at the words. Why several nodes and not one: two reporting
  * predicates in one sentence ("he said ... and he told ...") are two chart nodes and one speaker,
  * and only the entity layer can see that. The compiler collapses these by entity and abstains when
  * more than one survives.
  */
enum HolderCandidate:
  /** Chart nodes any of which may name the holder, in chart order and without duplicates. */
  case Fillers(refs: NonEmptyVector[ChartNodeRef])

  /** Nothing was offered; `gap` says why the rule had nothing to point at. */
  case Missing(gap: HolderGap)

  def render: String = this match
    case Fillers(refs) => refs.toVector.map(_.key).mkString("fillers(", ",", ")")
    case Missing(gap)  => s"missing:${gap.render}"

/** The kind of context a placement step opens, with the holder question answered as far as the
  * chart allows.
  *
  * Why the holder-less kinds carry no candidate: a hypothetical has no holder by its nature, and a
  * speech whose speaker no rule could name has one the model failed to find. Storing both as an
  * absent candidate would make those indistinguishable (design contract 7), so only the kinds that
  * have a holder carry the question at all.
  */
enum StepKind:
  case Speech(candidate: HolderCandidate)
  case Belief(candidate: HolderCandidate)
  case Desire(candidate: HolderCandidate)
  case Intention(candidate: HolderCandidate)
  case Memory(candidate: HolderCandidate)
  case Imagination(candidate: HolderCandidate)
  case Hypothetical
  case Counterfactual

  /** The holder candidate, for the kinds that have a holder at all. */
  def holderCandidate: Option[HolderCandidate] = this match
    case Speech(c)      => Some(c)
    case Belief(c)      => Some(c)
    case Desire(c)      => Some(c)
    case Intention(c)   => Some(c)
    case Memory(c)      => Some(c)
    case Imagination(c) => Some(c)
    case _              => None

  /** The kind alone, without the holder. This names the context family and nothing about who holds
    * it, which is what a context's identity is derived from.
    */
  def familyName: String = this match
    case Speech(_)      => "speech"
    case Belief(_)      => "belief"
    case Desire(_)      => "desire"
    case Intention(_)   => "intention"
    case Memory(_)      => "memory"
    case Imagination(_) => "imagination"
    case Hypothetical   => "hypothetical"
    case Counterfactual => "counterfactual"

/** One holder standing between the narrated world and a situation.
  *
  * Why two cases: a quotation is evidence in the surface text that survives sentence-by-sentence
  * parsing, and an embedding is evidence in one chart that does not. They are found by different
  * readings of different observations, and a reader auditing a context must be able to see which
  * one placed it.
  */
enum ContextStep:
  /** The situation's words lie inside this quotation of the canonical text. */
  case Quoted(quotation: TextSpan, candidate: HolderCandidate)

  /** The situation's concept is held by `container` under `kind` in its own chart. `evidence` is
    * the container's own alignment span: the words that do the holding.
    */
  case Embedded(container: ChartNodeRef, evidence: SpanRef, kind: StepKind)

  /** The part of a step that decides which context it is, with the holder deliberately left out: a
    * quotation is one context however its speaker resolves, so identity must not move when
    * attribution does.
    */
  def placementKey: String = this match
    case Quoted(q, _)         => s"quotation:${q.start}:${q.endExclusive}"
    case Embedded(c, _, kind) => s"embedded:${c.key}:${kind.familyName}"

  /** The words that evidence this step. */
  def support: SpanRef = this match
    case Quoted(q, _)       => SpanRef(q)
    case Embedded(_, ev, _) => ev

/** Why the placement rule could not place a situation.
  *
  * Every case is a refusal and not a fallback. Design contract 4 forbids reported content becoming
  * root-world fact by default, so a rule that cannot decide must say so and let the claim gap; the
  * one thing it must never do is pick the narrated world because nothing else matched.
  */
enum PlacementRefusal:
  /** The text's quotation structure is not derivable, so no span can be placed against it. */
  case TextUnreadable(defect: QuotationDefect)

  /** The situation's words start inside a quotation and end outside it, or the reverse. */
  case UndecidableQuotation(quotation: TextSpan)

  /** The chart holds one concept under several embeddings, so the path is not a chain. */
  case AmbiguousEmbedding(content: ConceptId)

  /** The chart's embedding relation loops, so no outermost holder exists. */
  case CyclicEmbedding(content: ConceptId)

  /** The chart says the concept is held but not how, and there is no context kind for that. */
  case UnknownEmbeddingKind(container: ConceptId)

  /** A quotation the containment test returned is not one the scan recorded. Unreachable while the
    * scan and the containment test share one value; refused rather than defaulted so that a future
    * caller wiring two different scans together fails loudly instead of silently unattributed.
    */
  case UnscannedQuotation(quotation: TextSpan)

  def render: String = this match
    case TextUnreadable(d)       => s"text-unreadable:${d.render}"
    case UndecidableQuotation(q) => s"undecidable-quotation:${q.start}:${q.endExclusive}"
    case AmbiguousEmbedding(c)   => s"ambiguous-embedding:${c.value}"
    case CyclicEmbedding(c)      => s"cyclic-embedding:${c.value}"
    case UnknownEmbeddingKind(c) => s"unknown-embedding-kind:${c.value}"
    case UnscannedQuotation(q)   => s"unscanned-quotation:${q.start}:${q.endExclusive}"

/** Everything the placement rule reads about one text, read once.
  *
  * Why a value and not two arguments: the quotation spans and the speaker offered for each of them
  * are one reading of one text, and a caller holding a scan of text A with speakers derived from
  * text B would place situations against evidence that does not describe them. Non-case with a
  * private constructor for the same reason.
  *
  * Why the quotation defect is carried rather than thrown away: a text whose marks do not pair is a
  * text whose reported content cannot be found, and every situation in it must gap rather than fall
  * to the narrated world. Holding the defect lets [[ContextPlacement.place]] refuse each root by
  * name instead of the whole build refusing once.
  */
final class TextPlacement private[document] (
    val scanned: Either[QuotationDefect, QuotationScan],
    val speakers: Map[TextSpan, HolderCandidate]
):
  def quotations: Vector[TextSpan] = scanned.map(_.spans).getOrElse(Vector.empty)

  override def toString: String =
    scanned.fold(d => s"TextPlacement(refused ${d.render})", s => s"TextPlacement(${s.size})")

/** Where a situation sits relative to the narrated world, derived from the chart and the text.
  *
  * The positive rule, stated once so it can be falsified: a situation is placed in the **root
  * narrated world exactly when both readings come back empty** — the words that anchor it lie
  * wholly outside every quotation of the canonical text, and its concept is held by no embedding of
  * its own chart. Root placement is therefore a reading of two recorded observations, not the
  * branch that fires when nothing else matched. When either reading cannot be taken — the marks do
  * not pair, the anchor straddles a mark, the chart's embedding relation is not a chain — the rule
  * refuses by name and the claim gaps.
  */
object ContextPlacement:
  /** Rule name recorded on every context receipt. */
  val RuleName: String = "context-placement-rule"

  /** Calibration model of the context rule.
    *
    * It replaces `narrated-world-default-v1`, whose name recorded that the narrated world was what
    * the rule fell back to. The placement is a total function of two things the model already
    * holds: the chart's own embedding relation, and the quotation spans of the canonical text. The
    * probability is about that mapping and not about whether the placement is true of the world; a
    * reader wanting the second must look at the context frame and its evidence.
    */
  val Rule: RuleId = RuleId.unsafe("context-placement-v1")

  /** Roles whose filler is the one doing the saying, thinking, or wanting.
    *
    * Why these two and no others: `Agent` is the sayer of a speech act and `Experiencer` the holder
    * of an attitude, and no other normalized role names the party a report belongs to. A numbered
    * argument that reached no normalized role is not read here, because nothing in the chart
    * licensed a reading of it, and guessing that `ARG0` means the speaker would assert frame
    * knowledge this layer does not have.
    */
  val HolderRoles: Set[ParticipantRole] = Set(ParticipantRole.Agent, ParticipantRole.Experiencer)

  private def isHolderRole(role: RoleAssignment): Boolean =
    role.normalizedRole.exists(HolderRoles.contains)

  /** The words that anchor one concept: its own alignment spans when it has any of its own, the
    * spans of every alignment naming it otherwise, and the sentence when the chart aligns it
    * nowhere.
    *
    * Why the narrowest first: `He said: "..."` has one alignment on `said` and others covering the
    * quoted words, and a root anchored at the whole sentence would straddle the quotation mark and
    * refuse. The concept's own span is what decides whether the concept is inside the quotation.
    */
  def anchorOf[C <: storymodel4s.proposition.CheckState](
      chart: PropositionChart[C],
      concept: ConceptId,
      unit: SurfaceUnit
  ): TextSpan =
    val exact = chart.alignments.filter(_.target.conceptIds == Set(concept))
    val naming =
      if exact.nonEmpty then exact
      else chart.alignments.filter(_.target.conceptIds.contains(concept))
    naming
      .flatMap(_.spans.refs.toVector.map(_.span))
      .reduceLeftOption(_.hull(_))
      .getOrElse(unit.span)

  /** The chart nodes offered as the holder of `container`: its fillers under a holder role. */
  def holderCandidate[C <: storymodel4s.proposition.CheckState](
      chart: PropositionChart[C],
      container: ConceptId,
      unit: SurfaceUnit
  ): HolderCandidate =
    val fillers = chart
      .relationsFrom(container)
      .filter(r => isHolderRole(r.role))
      .flatMap(_.to.nodeId)
      .filter(chart.concepts.contains)
      .distinct
      .sorted
      .map(id => ChartNodeRef(unit.id, id))
    NonEmptyVector.fromVector(fillers) match
      case Some(refs) => HolderCandidate.Fillers(refs)
      case None       => HolderCandidate.Missing(HolderGap.NoCandidate)

  /** The chain of embeddings holding `content`, innermost holder first.
    *
    * Refuses rather than choosing when a concept is held several ways, because "said and also
    * believed" is two contexts and the chart does not say which one this situation is in.
    */
  def heldChain[C <: storymodel4s.proposition.CheckState](
      chart: PropositionChart[C],
      content: ConceptId
  ): Either[PlacementRefusal, Vector[EmbeddedProposition]] =
    @annotation.tailrec
    def walk(
        current: ConceptId,
        seen: Set[ConceptId],
        acc: Vector[EmbeddedProposition]
    ): Either[PlacementRefusal, Vector[EmbeddedProposition]] =
      chart.embeddingsOf(current) match
        case Vector()    => Right(acc)
        case Vector(one) =>
          if seen.contains(one.container) then Left(PlacementRefusal.CyclicEmbedding(current))
          else walk(one.container, seen + one.container, acc :+ one)
        case _ => Left(PlacementRefusal.AmbiguousEmbedding(current))
    walk(content, Set(content), Vector.empty)

  private def stepKind[C <: storymodel4s.proposition.CheckState](
      chart: PropositionChart[C],
      container: ConceptId,
      unit: SurfaceUnit,
      kind: EmbeddingKind
  ): Either[PlacementRefusal, StepKind] =
    def held = holderCandidate(chart, container, unit)
    kind match
      case EmbeddingKind.Speech         => Right(StepKind.Speech(held))
      case EmbeddingKind.Belief         => Right(StepKind.Belief(held))
      case EmbeddingKind.Desire         => Right(StepKind.Desire(held))
      case EmbeddingKind.Intention      => Right(StepKind.Intention(held))
      case EmbeddingKind.Memory         => Right(StepKind.Memory(held))
      case EmbeddingKind.Imagination    => Right(StepKind.Imagination(held))
      case EmbeddingKind.Hypothetical   => Right(StepKind.Hypothetical)
      case EmbeddingKind.Counterfactual => Right(StepKind.Counterfactual)
      case EmbeddingKind.Unknown        => Left(PlacementRefusal.UnknownEmbeddingKind(container))

  /** The speech containers of one sentence whose own words end at or before `before`. */
  private def containersBefore(
      unit: SurfaceUnit,
      before: Int,
      chartOf: SurfaceUnitId => Option[PropositionChart[Checked]]
  ): Vector[(PropositionChart[Checked], SurfaceUnit, ConceptId)] =
    chartOf(unit.id).toVector.flatMap { chart =>
      chart.embedded
        .filter(_.kind == EmbeddingKind.Speech)
        .map(_.container)
        .distinct
        .sorted
        .filter(c => anchorOf(chart, c, unit).endExclusive <= before)
        .map(c => (chart, unit, c))
    }

  /** The speaker offered for one quotation, or the reason none is.
    *
    * The lookback is one sentence and it is stated rather than tuned. Candidates are the speech
    * containers of the sentence the opening mark falls in, whose own words end at or before that
    * mark; only when that sentence offers none does the sentence before it answer. Reaching past
    * that would attribute every quotation of a story to whoever last spoke, and asking both
    * sentences at once would make ordinary dialogue ambiguous — `He said: "one." She said: "two."`
    * has one speaker for the second quotation and the containing sentence names it.
    *
    * The rule offers chart nodes; the compiler decides by entity. Two containers in one sentence
    * pointing at the same word are one speaker, which is why `he said ... and he told ...` can
    * still attribute; two pointing at different words abstain rather than pick.
    */
  def speakerOf(
      quotation: TextSpan,
      atlas: SurfaceAtlas,
      chartOf: SurfaceUnitId => Option[PropositionChart[Checked]]
  ): HolderCandidate =
    val opening = atlas.unitAt(quotation.start, SurfaceUnitKind.Sentence)
    val own = opening.toVector.flatMap(u => containersBefore(u, quotation.start, chartOf))
    val rows =
      if own.nonEmpty then own
      else
        opening.toVector
          .flatMap(u => atlas.sentences.find(_.ordinal == u.ordinal - 1))
          .flatMap(u => containersBefore(u, quotation.start, chartOf))
    val refs = rows
      .flatMap((chart, u, container) =>
        holderCandidate(chart, container, u) match
          case HolderCandidate.Fillers(fs) => fs.toVector
          case HolderCandidate.Missing(_)  => Vector.empty
      )
      .distinct
    NonEmptyVector.fromVector(refs) match
      case Some(nev)             => HolderCandidate.Fillers(nev)
      case None if rows.nonEmpty => HolderCandidate.Missing(HolderGap.UnresolvedCandidate)
      case None                  => HolderCandidate.Missing(HolderGap.NoCandidate)

  /** Read one text once: its quotations, and the speaker offered for each of them. */
  def read(
      source: StorySource,
      atlas: SurfaceAtlas,
      charts: Vector[(SurfaceUnitId, PropositionChart[Checked])]
  ): TextPlacement =
    val byUnit = charts.toMap
    QuotationScan.of(source.canonicalText) match
      case Left(defect) => new TextPlacement(Left(defect), Map.empty)
      case Right(scan)  =>
        val speakers =
          scan.spans.map(q => q -> speakerOf(q, atlas, byUnit.get)).toMap
        new TextPlacement(Right(scan), speakers)

  /** Place one situation root, or refuse by name.
    *
    * The two readings compose: quotations that the chart itself accounts for are dropped, so a
    * sentence whose own reporting predicate sits outside the quotation contributes an embedding
    * step and not a quotation step, and the same content is never held twice for one reason. What
    * survives is the reporting the chart could not see, because it happened in an earlier sentence.
    */
  def place(
      chart: PropositionChart[Checked],
      root: ConceptId,
      unit: SurfaceUnit,
      text: TextPlacement
  ): Either[PlacementRefusal, ContextAssignmentProposal] =
    for
      scan <- text.scanned.left.map(PlacementRefusal.TextUnreadable(_))
      chain <- heldChain(chart, root)
      embedded <- chain.reverse.foldLeft[Either[PlacementRefusal, Vector[ContextStep]]](
        Right(Vector.empty)
      ) { (acc, e) =>
        for
          built <- acc
          kind <- stepKind(chart, e.container, unit, e.kind)
        yield built :+ ContextStep.Embedded(
          ChartNodeRef(unit.id, e.container),
          SpanRef(Some(unit.id), anchorOf(chart, e.container, unit)),
          kind
        )
      }
      enclosing <- scan.containment(anchorOf(chart, root, unit)) match
        case QuotationContainment.Outside          => Right(Vector.empty[TextSpan])
        case QuotationContainment.Straddling(q)    => Left(PlacementRefusal.UndecidableQuotation(q))
        case QuotationContainment.Inside(enclosed) => Right(enclosed.toVector)
      unaccounted = enclosing.filterNot(q =>
        chain.exists(e => !q.contains(anchorOf(chart, e.container, unit)))
      )
      quoted <- unaccounted.foldLeft[Either[PlacementRefusal, Vector[ContextStep]]](
        Right(Vector.empty)
      ) { (acc, q) =>
        for
          built <- acc
          who <- text.speakers.get(q).toRight(PlacementRefusal.UnscannedQuotation(q))
        yield built :+ ContextStep.Quoted(q, who)
      }
    yield NonEmptyVector.fromVector(quoted ++ embedded) match
      case Some(path) => ContextAssignmentProposal.Held(path)
      case None       => ContextAssignmentProposal.NarratedWorld
