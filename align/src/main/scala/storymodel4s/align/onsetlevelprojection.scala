package storymodel4s.align

import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Onset-point projection of held reference levels onto declared recall-clock bins (ADR 0031 §3).
  *
  * Why: a regressor holds a level over recall time, while every `TemporalQuery.Allocation`
  * distributes or bounds an amount. This projection is a separate family, so a level can never be
  * read as a conserved amount. It is generic over the value attached to each anchored reference and
  * needs only onsets: a covered word with an onset is a point sample of its reference, and a bin
  * reduces over the distinct references sampled in it. It applies no lag and no haemodynamic model,
  * and it never infers coverage between onsets. The held-interval projection is specified by the
  * ADR but deliberately not built here.
  */
object OnsetLevelProjection:
  val Version: String = "onset-level-projection/v1"

  /** The reference layer a level was measured at. Why: unit and atom levels are different samples
    * and are never pooled silently.
    */
  enum Layer:
    case Unit, Atom
    case Custom(namespace: String, label: String)

  /** The interpretation a reference belongs to. Why: narrative and referent readings of one recall
    * are separate accounts.
    */
  enum Interpretation:
    case Narrative, Referent
    case Custom(namespace: String, label: String)

  /** The caller's declaration of what the levels mean. Why: the projection is generic, so the
    * measure's own conditioning (presentation axis, source grain, measure kind) is recorded as an
    * opaque digest rather than interpreted.
    */
  final case class Declaration(layer: Layer, interpretation: Interpretation, measure: Checksum)

  /** Which covering references a word samples. Why: a broad frame over a specific nucleus covers
    * the same words twice, and the choice between them is an analysis declaration.
    */
  enum Nesting:
    /** The covering reference with the shortest anchor (UTF-16 units covered). */
    case Innermost

    /** Every covering reference is a distinct sample. */
    case All

  /** Why a reference has no level. Why: a not-applicable reference is a typed state, never zero and
    * never an input to a numeric reducer (rule 7).
    */
  enum NotApplicableReason:
    case Discourse, NoSourceSupport
    case Custom(namespace: String, label: String)

  /** A reference's held level or its typed inapplicability. Why: keeps "no value" apart from any
    * value.
    */
  enum Level[+A]:
    case Applicable(value: A)
    case NotApplicable(reason: NotApplicableReason)

  /** One anchored reference: its exact UTF-16 support in the recall transcript and its level. Why:
    * untrusted input; `Request.checked` validates it. Within one declared layer the reference's
    * identity is its normalized support (see `support`), not the way the anchor is written.
    */
  final case class Sample[+A](anchor: SpanSet, level: Level[A])

  /** The normalized support of an anchor: empty spans dropped, overlapping and abutting spans
    * merged, surface-unit tags ignored. Why: `{[0,5)}`, `{[0,3),[3,5)}` and a unit-tagged `{[0,5)}`
    * cover the same text, so they are one reference; identity, coverage and Innermost length all
    * use this support. An anchor of only empty spans has empty support and covers no word.
    */
  def support(anchor: SpanSet): Vector[TextSpan] =
    anchor.spans.toVector
      .filterNot(_.isEmpty)
      .sortBy(s => (s.start, s.endExclusive))
      .foldLeft(Vector.empty[TextSpan]) { (merged, span) =>
        merged.lastOption.flatMap(_.union(span)) match
          case Some(joined) => merged.init :+ joined
          case None         => merged :+ span
      }

  private def supportLength(support: Vector[TextSpan]): Int = support.map(_.length).sum

  /** A declared level reducer. Why: sealed, and without a sum, so no amount-conserving rule can be
    * declared for a level projection (rules 2 and 8). Every reducer counts each distinct reference
    * once.
    */
  sealed trait Reducer[A, O]:
    def id: String

  object Reducer:
    /** Arithmetic mean over distinct applicable references. The sum of the exact binary values is
      * exact; the single division is rounded to `MeanQuotient` (34 significant digits, half-even),
      * and that quotient is then rounded to the nearest Double. The result does not depend on
      * sample order.
      */
    case object Mean extends Reducer[Double, Double]:
      val id = "mean-distinct-references"
      val MeanQuotient: java.math.MathContext = java.math.MathContext.DECIMAL128
    case object Minimum extends Reducer[Double, Double]:
      val id = "minimum-distinct-references"
    case object Maximum extends Reducer[Double, Double]:
      val id = "maximum-distinct-references"

    /** Share of distinct applicable references per category; never a mean of codes.
      *
      * Categories are grouped by `==`, so a category type must have lawful equality. `Double` and
      * `Float` are refused at compile time (NaN is unequal to itself; -0.0 and 0.0 are equal but
      * distinct values). A caller's own type that wraps a floating value has the same obligation.
      */
    final case class Share[C]()(using NotFloating[C]) extends Reducer[C, CategoryShares[C]]:
      val id = "category-share-distinct-references"

    /** The set of categories held by at least one sampled applicable reference. The equality
      * obligation of `Share` applies.
      */
    final case class Presence[C]()(using NotFloating[C]) extends Reducer[C, Set[C]]:
      val id = "category-presence"

  /** Evidence that a category type is not a bare floating-point type. Why: floating equality is not
    * lawful for grouping categories (see `Reducer.Share`).
    */
  final class NotFloating[C] private ()
  object NotFloating:
    given lawful[C](using
        scala.util.NotGiven[C =:= Double],
        scala.util.NotGiven[C =:= Float]
    ): NotFloating[C] = new NotFloating[C]

  /** Distinct-reference counts per category with their denominator. Why: a share is reported with
    * the count it divides, so it is never an unexplained fraction.
    */
  final class CategoryShares[C] private[OnsetLevelProjection] (
      val counts: Map[C, Int],
      val references: Int
  ):
    /** Exact share of `category`: zero for an absent category when references were sampled; `None`
      * only when no reference was sampled.
      */
    def share(category: C): Option[ExactRational] =
      Option
        .when(references > 0)(counts.getOrElse(category, 0))
        .flatMap(n => ExactRational.of(n.toLong, references.toLong).toOption)
    override def equals(other: Any): Boolean = other match
      case that: CategoryShares[?] => counts == that.counts && references == that.references
      case _                       => false
    override def hashCode(): Int = (counts, references).hashCode
    override def toString: String = s"CategoryShares($counts of $references)"

  /** One half-open bin on a recall clock. Why: produced only by `Bins.checked`. */
  final class Bin private[OnsetLevelProjection] (
      val start: ExactRational,
      val endExclusive: ExactRational
  ):
    override def equals(other: Any): Boolean = other match
      case that: Bin => start == that.start && endExclusive == that.endExclusive
      case _         => false
    override def hashCode(): Int = (start, endExclusive).hashCode
    override def toString: String = s"[$start, $endExclusive)"

  /** Ordered, disjoint, half-open bins declared on one recall clock. Why: a bin on another clock or
    * an overlapping bin would let one onset count twice or cross an unestablished link.
    */
  final class Bins private (val clock: RecallTiming.Clock, val bins: Vector[Bin]):
    override def equals(other: Any): Boolean = other match
      case that: Bins => clock == that.clock && bins == that.bins
      case _          => false
    override def hashCode(): Int = (clock, bins).hashCode
    override def toString: String = s"OnsetLevelProjection.Bins(${bins.size})"

  object Bins:
    enum Refusal:
      case Empty, TooManyBins
      case InvalidRational(bin: Int)
      case NonPositiveWidth(bin: Int)
      case Unordered(bin: Int)
      case Overlapping(bin: Int)

    def checked(
        clock: RecallTiming.Clock,
        bounds: Vector[(ExactRational, ExactRational)]
    ): Either[Refusal, Bins] =
      if bounds.isEmpty then Left(Refusal.Empty)
      else if bounds.size > TemporalGrid.MaximumBins then Left(Refusal.TooManyBins)
      else
        val problem = bounds.indices.iterator.flatMap { i =>
          val (start, end) = bounds(i)
          if !canonical(start) || !canonical(end) then Some(Refusal.InvalidRational(i))
          else if compare(start, end) >= 0 then Some(Refusal.NonPositiveWidth(i))
          else if i == 0 then None
          else
            val (previousStart, previousEnd) = bounds(i - 1)
            if compare(start, previousStart) <= 0 then Some(Refusal.Unordered(i))
            else Option.when(compare(start, previousEnd) < 0)(Refusal.Overlapping(i))
        }
        problem.nextOption() match
          case Some(refusal) => Left(refusal)
          case None          => Right(new Bins(clock, bounds.map((a, b) => new Bin(a, b))))

  /** A checked projection request. Why: binds the levels to one transcript, one timing and one
    * clock before any bin is computed; samples are held in canonical normalized-support order.
    */
  final class Request[A, O] private (
      val timing: RecallTiming,
      val declaration: Declaration,
      val nesting: Nesting,
      val bins: Bins,
      val samples: Vector[Sample[A]],
      val reducer: Reducer[A, O]
  ):
    val version: String = Version
    private def fields = (timing.digest, declaration, nesting, bins, samples, reducer)
    override def equals(other: Any): Boolean = other match
      case that: Request[?, ?] => fields == that.fields
      case _                   => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String =
      s"OnsetLevelProjection.Request(${declaration.layer},$nesting,${samples.size} samples)"

  /** Construction refusals; each names the offending input position or word.
    *
    * `DuplicateAnchor` compares normalized support (see `support`). `AmbiguousInnermost` is checked
    * over every inventory word, including words with missing timing or an onset outside every bin,
    * so it may refuse a tie that no bin would have sampled. That is conservative.
    */
  enum Refusal:
    case ForeignTranscript, ForeignClock
    case AnchorOutsideTranscript(sample: Int)
    case DuplicateAnchor(sample: Int)
    case NonFiniteValue(sample: Int)
    case AmbiguousInnermost(word: RecallWordId)

  object Request:
    def checked[A, O](
        graph: RecallGraph[Checked],
        timing: RecallTiming,
        declaration: Declaration,
        nesting: Nesting,
        bins: Bins,
        samples: Vector[Sample[A]],
        reducer: Reducer[A, O]
    ): Either[Refusal, Request[A, O]] =
      val textLength = graph.transcript.canonicalText.length
      val seen = scala.collection.mutable.HashSet.empty[Vector[TextSpan]]
      val sampleProblem = samples.indices.iterator.flatMap { i =>
        val sample = samples(i)
        if sample.anchor.spans.exists(_.endExclusive > textLength) then
          Some(Refusal.AnchorOutsideTranscript(i))
        else if !seen.add(support(sample.anchor)) then Some(Refusal.DuplicateAnchor(i))
        else
          sample.level match
            case Level.Applicable(value: Double) if numeric(reducer) && !value.isFinite =>
              Some(Refusal.NonFiniteValue(i))
            case _ => None
      }
      if !timing.inventory.describes(graph) then Left(Refusal.ForeignTranscript)
      else if bins.clock != timing.clock then Left(Refusal.ForeignClock)
      else
        sampleProblem.nextOption() match
          case Some(refusal) => Left(refusal)
          case None          =>
            // Normalized supports are distinct here, so this order is total.
            val ordered = samples.sortBy(s =>
              support(s.anchor).map(t => (t.start, t.endExclusive))
            )(using Ordering.Implicits.seqOrdering)
            val ambiguous = nesting match
              case Nesting.All       => None
              case Nesting.Innermost =>
                val cover = coverage(timing.inventory, ordered)
                timing.inventory.words.iterator
                  .flatMap { word =>
                    val covering = cover.getOrElse(word.id, Vector.empty)
                    Option
                      .when(covering.nonEmpty) {
                        val lengths = covering.map(i => supportLength(support(ordered(i).anchor)))
                        lengths.count(_ == lengths.min)
                      }
                      .collect { case n if n > 1 => Refusal.AmbiguousInnermost(word.id) }
                  }
                  .nextOption()
            ambiguous.toLeft(new Request(timing, declaration, nesting, bins, ordered, reducer))

  /** What a bin's words and references were. Why: bins mix states, so the ledger reports counts
    * rather than one exclusive state. The three word counts partition the words with an onset in
    * the bin; the two reference counts are distinct references.
    */
  final class Ledger private[OnsetLevelProjection] (
      val applicableReferences: Int,
      val notApplicableReferences: Int,
      val coveredWords: Int,
      val uncoveredMemberWords: Int,
      val uncoveredUnassignedWords: Int
  ):
    def wordsWithOnset: Int = coveredWords + uncoveredMemberWords + uncoveredUnassignedWords
    private def fields = (
      applicableReferences,
      notApplicableReferences,
      coveredWords,
      uncoveredMemberWords,
      uncoveredUnassignedWords
    )
    override def equals(other: Any): Boolean = other match
      case that: Ledger => fields == that.fields
      case _            => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"Ledger$fields"

  /** Why a bin has no value, derived from its ledger. Why: onset timing cannot show silence, so a
    * bin with no onset is "no sample", never "no speech".
    */
  enum Absence:
    /** No word has an onset in the bin. */
    case NoSample

    /** References were sampled, but every one is not applicable. */
    case NotApplicableOnly

    /** Words have onsets in the bin, but no reference covers any of them. */
    case UncoveredOnly

  /** A bin's reduced level or its typed absence. Why: an absent level has no plausible number. */
  enum BinValue[+O]:
    case Present(value: O)
    case Absent(reason: Absence)

  /** One bin's result. Why: produced only by `project`; keeps the sampled references beside the
    * reduced value so that it can be audited.
    */
  final class BinResult[O] private[OnsetLevelProjection] (
      val bin: Bin,
      val ledger: Ledger,
      val applicable: Vector[SpanSet],
      val notApplicable: Vector[(SpanSet, NotApplicableReason)],
      val value: BinValue[O]
  ):
    private def fields = (bin, ledger, applicable, notApplicable, value)
    override def equals(other: Any): Boolean = other match
      case that: BinResult[?] => fields == that.fields
      case _                  => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"BinResult($bin,$ledger,$value)"

  /** The projection result. Why: what cannot be placed is reported once here, never in a bin: words
    * with missing timing, words with an onset outside every bin, references that cover no word
    * (`wordlessReferences`), and references that cover a word but sampled no bin
    * (`unsampledReferences`; disjoint from the wordless ones).
    */
  final class Projection[A, O] private[OnsetLevelProjection] (
      val request: Request[A, O],
      val bins: Vector[BinResult[O]],
      val missingTimingWords: Vector[RecallWordId],
      val outsideBinWords: Vector[RecallWordId],
      val wordlessReferences: Vector[SpanSet],
      val unsampledReferences: Vector[SpanSet]
  ):
    val version: String = Version
    private def fields =
      (request, bins, missingTimingWords, outsideBinWords, wordlessReferences, unsampledReferences)
    override def equals(other: Any): Boolean = other match
      case that: Projection[?, ?] => fields == that.fields
      case _                      => false
    override def hashCode(): Int = fields.hashCode
    override def toString: String = s"OnsetLevelProjection.Projection(${bins.size} bins)"

  /** Total on a checked request. */
  def project[A, O](request: Request[A, O]): Projection[A, O] =
    val inventory = request.timing.inventory
    val samples = request.samples
    val cover = coverage(inventory, samples)
    val bins = request.bins.bins
    // Per bin: distinct sampled references and the word partition.
    val sampled = Array.fill(bins.size)(scala.collection.mutable.TreeSet.empty[Int])
    val covered = Array.fill(bins.size)(0)
    val uncoveredMember = Array.fill(bins.size)(0)
    val uncoveredUnassigned = Array.fill(bins.size)(0)
    val everSampled = scala.collection.mutable.HashSet.empty[Int]
    val missing = Vector.newBuilder[RecallWordId]
    val outside = Vector.newBuilder[RecallWordId]
    def place(at: ExactRational, word: RecallWord): Unit =
      binOf(bins, at) match
        case None    => outside += word.id
        case Some(b) =>
          val covering = cover.getOrElse(word.id, Vector.empty)
          if covering.nonEmpty then
            covered(b) += 1
            val selected = request.nesting match
              case Nesting.All       => covering
              case Nesting.Innermost =>
                Vector(covering.minBy(i => supportLength(support(samples(i).anchor))))
            selected.foreach { i =>
              sampled(b) += i
              everSampled += i
            }
          else
            inventory.membership(word.id) match
              case WordMembership.Member(_)     => uncoveredMember(b) += 1
              case WordMembership.Unassigned(_) => uncoveredUnassigned(b) += 1
    inventory.words.foreach { word =>
      request.timing.entry(word.id).map(_.observation) match
        case None | Some(RecallTiming.Observation.Missing(_))     => missing += word.id
        case Some(RecallTiming.Observation.OnsetOnly(at, _))      => place(at, word)
        case Some(RecallTiming.Observation.Interval(start, _, _)) => place(start, word)
    }
    val results = bins.indices.toVector.map { b =>
      val refs = sampled(b).toVector
      val applicable = refs.flatMap(i =>
        samples(i).level match
          case Level.Applicable(value) => Some(samples(i).anchor -> value)
          case _                       => None
      )
      val notApplicable = refs.flatMap(i =>
        samples(i).level match
          case Level.NotApplicable(reason) => Some(samples(i).anchor -> reason)
          case _                           => None
      )
      val ledger = new Ledger(
        applicable.size,
        notApplicable.size,
        covered(b),
        uncoveredMember(b),
        uncoveredUnassigned(b)
      )
      val value: BinValue[O] =
        if applicable.nonEmpty then BinValue.Present(reduce(request.reducer, applicable.map(_._2)))
        else if notApplicable.nonEmpty then BinValue.Absent(Absence.NotApplicableOnly)
        else if ledger.wordsWithOnset > 0 then BinValue.Absent(Absence.UncoveredOnly)
        else BinValue.Absent(Absence.NoSample)
      new BinResult(bins(b), ledger, applicable.map(_._1), notApplicable, value)
    }
    val coveringAny = cover.values.flatten.toSet
    new Projection(
      request,
      results,
      missing.result(),
      outside.result(),
      samples.indices.filterNot(coveringAny).map(samples(_).anchor).toVector,
      samples.indices
        .filter(i => coveringAny(i) && !everSampled(i))
        .map(samples(_).anchor)
        .toVector
    )

  private def numeric(reducer: Reducer[?, ?]): Boolean = reducer match
    case Reducer.Mean | Reducer.Minimum | Reducer.Maximum => true
    case _                                                => false

  private def reduce[A, O](reducer: Reducer[A, O], values: Vector[A]): O = reducer match
    case Reducer.Mean =>
      val sum =
        values.foldLeft(java.math.BigDecimal.ZERO)((acc, v) => acc.add(new java.math.BigDecimal(v)))
      sum
        .divide(java.math.BigDecimal.valueOf(values.size.toLong), Reducer.Mean.MeanQuotient)
        .doubleValue
    case Reducer.Minimum     => values.min
    case Reducer.Maximum     => values.max
    case _: Reducer.Share[c] =>
      val counts = values.groupBy(identity).view.mapValues(_.size).toMap
      new CategoryShares[c](counts, values.size)
    case Reducer.Presence() => values.toSet

  /** Words covered by each sample, by exact UTF-16 intersection with its normalized support. A
    * sweep over the ordered, disjoint inventory words: each anchor span visits only the words it
    * can intersect (rule 9).
    */
  private[align] def coverage[A](
      inventory: RecallInventory,
      samples: Vector[Sample[A]]
  ): Map[RecallWordId, Vector[Int]] =
    val words = inventory.words
    val pairs = samples.indices.iterator.flatMap { i =>
      support(samples(i).anchor).iterator
        .flatMap { span =>
          var lo = 0
          var hi = words.size
          while lo < hi do
            val mid = (lo + hi) >>> 1
            if words(mid).span.endExclusive <= span.start then lo = mid + 1 else hi = mid
          Iterator
            .from(lo)
            .takeWhile(j => j < words.size && words(j).span.start < span.endExclusive)
            .filter(j => words(j).span.overlaps(span))
        }
        .distinct
        .map(j => words(j).id -> i)
    }
    pairs.toVector.groupMap(_._1)(_._2)

  private def binOf(bins: Vector[Bin], at: ExactRational): Option[Int] =
    var lo = 0
    var hi = bins.size
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if compare(bins(mid).endExclusive, at) <= 0 then lo = mid + 1 else hi = mid
    Option.when(lo < bins.size && compare(bins(lo).start, at) <= 0)(lo)

  private def canonical(r: ExactRational): Boolean =
    r.denominator > 0 && BigInt(r.numerator).gcd(BigInt(r.denominator)) == 1

  private def compare(a: ExactRational, b: ExactRational): Int =
    (BigInt(a.numerator) * BigInt(b.denominator))
      .compare(BigInt(b.numerator) * BigInt(a.denominator))
