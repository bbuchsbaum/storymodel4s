package storymodel4s.features

import cats.Functor
import storymodel4s.core.*

/** Why a reduction could not produce a value even though its support was nonempty. */
enum UndefinedReason:
  /** A slope needs at least two distinct positions. */
  case SlopeNeedsTwoPositions

  /** Weighted reductions need positive total weight. */
  case ZeroTotalWeight

  /** No observed sample fell inside the kernel's support. */
  case OutsideKernelSupport

  /** An observed value was not finite (NaN/±∞) and was treated as absent. */
  case NotFinite
  case Custom(namespace: String, name: String)

/** What made a provider value structurally unusable despite a safe target association.
  *
  * Why: malformed provider output is neither policy exclusion nor provider abstention, and
  * downstream coverage reports must be able to distinguish those mechanisms.
  */
enum MalformedReason:
  /** A provider returned a value that violated the result contract for its requested target. */
  case ProviderResult

  case Custom(namespace: String, name: String)

/** Why a value is absent. Missing is a first-class outcome, never a zero. */
enum MissingReason:
  /** The lexicon/provider has no entry for this target (e.g. a proper name). */
  case NotInLexicon

  /** The provider recognised the target but it is outside its vocabulary/domain. */
  case OutOfVocabulary

  /** The provider declined to answer. */
  case ProviderAbstained

  /** The target was excluded by policy (punctuation, low coverage, leakage rule). */
  case Excluded

  /** A safely associated provider value was rejected because it violated its result contract. */
  case Malformed(reason: MalformedReason)

  /** The support contained no observed sample at all. */
  case AllMissing

  /** The support had observed samples but the operation is undefined on them. */
  case Undefined(reason: UndefinedReason)

  /** A namespaced domain reason that does not belong in the portable closed vocabulary. */
  case Custom(namespace: String, label: String)

  case Unknown

/** An observed value with optional uncertainty, or a typed absence. */
enum Estimate[+V]:
  case Observed(value: V, credence: Option[Credence])
  case Missing(reason: MissingReason)

  def toOption: Option[V] = this match
    case Observed(v, _) => Some(v)
    case Missing(_)     => None

  def isObserved: Boolean = toOption.isDefined

  def map[W](f: V => W): Estimate[W] = this match
    case Observed(v, c) => Observed(f(v), c)
    case Missing(r)     => Missing(r)

object Estimate:
  def observed[V](v: V): Estimate[V] = Observed(v, None)
  def missing[V](reason: MissingReason): Estimate[V] = Missing(reason)

  /** A scalar estimate: a non-finite value is never observed, it is
    * `Missing(Undefined(NotFinite))`.
    */
  def score(v: Double, credence: Option[Credence] = None): ScoreEstimate =
    if isFinite(v) then Observed(v, credence)
    else Missing(MissingReason.Undefined(UndefinedReason.NotFinite))

  /** Observed finite value, if any; non-finite observations count as absent. */
  def finite(e: Estimate[Double]): Option[Double] = e.toOption.filter(isFinite)

  private[features] def isFinite(v: Double): Boolean = !v.isNaN && !v.isInfinite

  given Functor[Estimate] with
    def map[A, B](fa: Estimate[A])(f: A => B): Estimate[B] = fa.map(f)

type ScoreEstimate = Estimate[Double]

/** An estimate with a nonnegative weight, e.g. a kernel weight or a count mass. */
final case class WeightedEstimate[+V](estimate: Estimate[V], weight: Double)

/** How many targets could have carried a value and how many did.
  *
  * Why: sums and means over windows are confounded by lexicon coverage; every aggregate must say
  * how much of its support it actually observed. Construction is validated: `observed ≤ eligible`
  * and both nonnegative.
  */
final case class Coverage private (eligible: Int, observed: Int):
  def fraction: Double = if eligible == 0 then 0.0 else observed.toDouble / eligible
  def missing: Int = eligible - observed
  def isEmpty: Boolean = eligible == 0
  def +(o: Coverage): Coverage = Coverage(eligible + o.eligible, observed + o.observed)

object Coverage:
  val empty: Coverage = Coverage(0, 0)

  def of(eligible: Int, observed: Int): Either[DomainError, Coverage] =
    if eligible < 0 || observed < 0 then
      Left(DomainError.InvariantViolation("features/coverage", "negative count"))
    else if observed > eligible then
      Left(
        DomainError.InvariantViolation(
          "features/coverage",
          s"observed $observed exceeds eligible $eligible"
        )
      )
    else Right(Coverage(eligible, observed))

  /** For counts that are correct by construction (e.g. a filter over a known set). */
  def unsafe(eligible: Int, observed: Int): Coverage =
    of(eligible, observed).fold(e => throw new IllegalArgumentException(e.toString), identity)

/** Platform-stable canonical rendering of doubles for identifiers and checksums.
  *
  * Why: `Double.toString` differs between the JVM/Native and Scala.js (`1.0` vs `1`), so any
  * content address that embeds it would differ per platform. The IEEE-754 bit pattern does not.
  */
object CanonicalDouble:
  def render(d: Double): String =
    val bits = java.lang.Double.doubleToLongBits(d)
    val hex = java.lang.Long.toHexString(bits)
    "0x" + ("0" * (16 - hex.length)) + hex
