package storymodel4s.pipeline.agentmapping

import cats.data.NonEmptyVector
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.{RecallInventory, RecallUnit}

/** Conversion of case-study reference answers (prompt v2) into provisional align references.
  *
  * Why a separate step: a parsed answer keeps what the reply stated (`ReferenceAnswers`); a
  * provisional reference is a checked meaning on one source (ADR 0031 §1). Between them sit
  * declared choices: how a `form` becomes a hypothesis shape, how anchor words become exact UTF-16
  * support, and what the weights' remainder means. Each reference either converts or is refused
  * with a typed reason, so the ledger accounts for every reference the reply made.
  *
  * Declared choices (case study 2, milestone 0):
  *   - `specific` and `extended` are `Single` with one constituent (the rows of every interval);
  *     `summary` is `Broad` with one constituent; `repeated` is `Single` with one constituent per
  *     interval (its occurrences); `joint` is `Joint` with one constituent per interval (moment).
  *   - One hypothesis is a definite reference whose weight is its confidence; several are an
  *     alternative whose members keep their weights as written.
  *   - The v2 prompt defines the remainder of the weights as "could fit places not listed", so the
  *     coverage is `Exhaustive`: 1 - Σw is a known unassigned share, never redistributed.
  *   - Anchor words are found in the unit's own text by normalized token sequence (lower case, runs
  *     of `[a-z0-9']`); exactly one occurrence is required.
  */
object ReferenceConversion:
  val Version: String = "case-study-reference-conversion/v1"

  import ReferenceAnswers.{Form, Reference}
  import AgentAnswers.Field
  import ProvisionalReference.{Declaration, Hypothesis, Member, Shape, WeightCoverage, Weighted}

  /** Why an interval list was invalid. Why: an unknown row is a different failure from a malformed
    * reply, and the harness treated them differently (it clamped unknown rows).
    */
  enum IntervalProblem:
    case UnknownRow, ReversedInterval, Malformed

  /** Why one reference did not convert. Why: every reference the reply made is accounted for. */
  enum Refusal:
    case WordsUnstated
    case EmptyAnchor
    case AnchorNotFound
    case AnchorAmbiguous(occurrences: Int)
    case FormUnstated
    case AltsUnstated
    case AltsInvalid(problem: IntervalProblem)
    case NoHypotheses
    case WeightUnstated(member: Int)
    case ZeroWeight(member: Int)
    case NoIntervals(member: Int)
    case Library(reason: ProvisionalReference.Refusal)

    /** Two references of one participant have the same normalized support (ADR 0031 §3). */
    case DuplicateAnchor

    /** A word is covered by two references of equal shortest support (ADR 0031 §3). */
    case AmbiguousInnermost

    def code: String = this match
      case Library(reason)    => s"library-${reason.productPrefix}"
      case AltsInvalid(p)     => s"alts-invalid-$p"
      case AnchorAmbiguous(_) => "anchor-ambiguous"
      case WeightUnstated(_)  => "weight-unstated"
      case ZeroWeight(_)      => "zero-weight"
      case NoIntervals(_)     => "no-intervals"
      case other              => other.productPrefix

  /** One converted reference: its exact anchor in the transcript, form and checked meaning. */
  final case class Converted(anchor: SpanSet, form: Form, declaration: Declaration):
    def alternativesPresent: Boolean = declaration match
      case Declaration.Alternative(_) => true
      case _                          => false

  /** A normalized token of a text, with its UTF-16 span in that text. */
  final case class Token(text: String, start: Int, endExclusive: Int)

  /** The normalization the harness used: lower case, maximal runs of `[a-z0-9']`. */
  def tokens(text: String): Vector[Token] =
    def kept(c: Char): Boolean =
      val l = Character.toLowerCase(c)
      (l >= 'a' && l <= 'z') || (l >= '0' && l <= '9') || l == '\''
    val out = Vector.newBuilder[Token]
    var i = 0
    while i < text.length do
      if kept(text(i)) then
        val start = i
        while i < text.length && kept(text(i)) do i += 1
        out += Token(text.substring(start, i).toLowerCase(java.util.Locale.ROOT), start, i)
      else i += 1
    out.result()

  /** Exact support of `words` inside `unit`: the span from its first to its last token, offset to
    * the transcript. Refuses an empty anchor, no occurrence, or more than one occurrence.
    */
  def anchor(unit: RecallUnit, words: String): Either[Refusal, SpanSet] =
    val wanted = tokens(words).map(_.text)
    val spans = unit.span.spans.toVector
    if wanted.isEmpty then Left(Refusal.EmptyAnchor)
    else
      val base = spans.head.start
      val have = tokens(unit.text)
      val hits = have.indices
        .filter(i =>
          i + wanted.size <= have.size && have.slice(i, i + wanted.size).map(_.text) == wanted
        )
      hits match
        case Seq(i) =>
          val first = have(i)
          val last = have(i + wanted.size - 1)
          TextSpan
            .of(base + first.start, base + last.endExclusive)
            .left
            .map(_ => Refusal.AnchorNotFound)
            .map(SpanSet.one)
        case Seq() => Left(Refusal.AnchorNotFound)
        case many  => Left(Refusal.AnchorAmbiguous(many.size))

  /** Classify an invalid interval list from its raw JSON. */
  def intervalProblem(raw: io.circe.Json, rows: SourceRows): IntervalProblem =
    val pairs = raw.asArray.toVector.flatten.flatMap(_.asArray).collect { case Vector(a, b) =>
      (a.asNumber.flatMap(_.toInt), b.asNumber.flatMap(_.toInt))
    }
    val wellFormed = raw.asArray.exists(_.forall(_.asArray.exists(_.size == 2))) &&
      pairs.forall((a, b) => a.isDefined && b.isDefined)
    val known = pairs.flatMap((a, b) => a.toVector ++ b.toVector)
    if !wellFormed then IntervalProblem.Malformed
    else if known.exists(r => !rows.contains(r)) then IntervalProblem.UnknownRow
    else IntervalProblem.ReversedInterval

  /** The hypothesis a form declares over row intervals. `row` names a row's mapping target. */
  def hypothesis(
      form: Form,
      intervals: Vector[RowInterval],
      row: Int => SourceNodeRef
  ): Option[Hypothesis] =
    def rows(iv: RowInterval): Vector[Int] = (iv.first to iv.last).toVector
    def one(units: Vector[Int]): Option[NonEmptyVector[SourceNodeRef]] =
      NonEmptyVector.fromVector(units.distinct.sorted.map(row))
    val all = intervals.flatMap(rows)
    form match
      case Form.Specific | Form.Extended =>
        one(all).map(c => Hypothesis(Shape.Single, NonEmptyVector.one(c)))
      case Form.Summary => one(all).map(c => Hypothesis(Shape.Broad, NonEmptyVector.one(c)))
      case Form.Repeated | Form.Joint =>
        val shape = if form == Form.Joint then Shape.Joint else Shape.Single
        NonEmptyVector
          .fromVector(intervals.flatMap(iv => one(rows(iv))))
          .map(cs => Hypothesis(shape, cs))

  /** The declaration of one reference's hypotheses, with weights as written. */
  def declaration(
      form: Form,
      alts: Vector[ReferenceAnswers.Hypothesis],
      row: Int => SourceNodeRef,
      rows: SourceRows
  ): Either[Refusal, Declaration] =
    val members = alts.zipWithIndex.foldLeft[Either[Refusal, Vector[(Hypothesis, Double)]]](
      Right(Vector.empty)
    ) { case (acc, (alt, i)) =>
      acc.flatMap { done =>
        val weight: Either[Refusal, Double] = alt.w match
          case Field.Value(w) =>
            val value = w.toDouble
            if value > 0.0 then Right(value) else Left(Refusal.ZeroWeight(i))
          case Field.Invalid(_)          => Left(Refusal.ZeroWeight(i))
          case Field.Absent | Field.Null => Left(Refusal.WeightUnstated(i))
        val located: Either[Refusal, Hypothesis] = alt.iv match
          case Field.Value(ivs)   => hypothesis(form, ivs, row).toRight(Refusal.NoIntervals(i))
          case Field.Invalid(raw) => Left(Refusal.AltsInvalid(intervalProblem(raw, rows)))
          case Field.Absent | Field.Null => Left(Refusal.NoIntervals(i))
        for
          w <- weight
          h <- located
        yield done :+ (h -> w)
      }
    }
    members.flatMap {
      case Vector()       => Left(Refusal.NoHypotheses)
      case Vector((h, w)) => Right(Declaration.Definite(h, Some(w)))
      case many           =>
        Right(Declaration.Alternative(many.map((h, w) => Weighted(Member.Located(h), Some(w)))))
    }

  /** Convert one reference of one unit, checking its meaning against `source` on `selection`. */
  def reference(
      unit: RecallUnit,
      ref: Reference,
      row: Int => SourceNodeRef,
      rows: SourceRows,
      source: SourceRepresentation,
      selection: TemporalSupport.Selection
  ): Either[Refusal, Converted] =
    for
      words <- ref.words match
        case Field.Value(w) => Right(w)
        case _              => Left(Refusal.WordsUnstated)
      at <- anchor(unit, words)
      form <- ref.shape match
        case ReferenceAnswers.Shape.Formed(Field.Value(f)) => Right(f)
        case _                                             => Left(Refusal.FormUnstated)
      alts <- ref.alts match
        case Field.Value(a)     => Right(a)
        case Field.Invalid(raw) =>
          Left(Refusal.AltsInvalid(intervalProblem(raw, rows)))
        case _ => Left(Refusal.AltsUnstated)
      declared <- declaration(form, alts, row, rows)
      _ <- ProvisionalReference
        .of(source, selection, declared, WeightCoverage.Exhaustive)
        .left
        .map(Refusal.Library(_))
    yield Converted(at, form, declared)

  /** Indices of samples refused because their normalized support repeats another's, or because a
    * word is covered by two of them with equal shortest support (the projection's Innermost rule).
    * Ties are removed together and the check is repeated until none remain, so no tie is broken by
    * input order. `anchors` holds every sample of one participant at one layer.
    */
  def inadmissible(
      inventory: RecallInventory,
      anchors: Vector[SpanSet]
  ): Map[Int, Refusal] =
    val support = anchors.map(OnsetLevelProjection.support)
    val duplicate = support.indices
      .groupBy(support)
      .values
      .filter(_.size > 1)
      .flatten
      .map(_ -> Refusal.DuplicateAnchor)
      .toMap
    def length(i: Int): Int = support(i).map(_.length).sum
    @annotation.tailrec
    def loop(live: Set[Int], refused: Map[Int, Refusal]): Map[Int, Refusal] =
      val tied = inventory.words.iterator.flatMap { word =>
        val covering = live.toVector.filter(i => support(i).exists(_.overlaps(word.span)))
        if covering.size < 2 then Vector.empty
        else
          val shortest = covering.map(length).min
          val ties = covering.filter(i => length(i) == shortest)
          if ties.size > 1 then ties else Vector.empty
      }.toSet
      if tied.isEmpty then refused
      else loop(live -- tied, refused ++ tied.map(_ -> Refusal.AmbiguousInnermost))
    loop(support.indices.toSet -- duplicate.keySet, duplicate)
