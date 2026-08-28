package storymodel4s.features

import cats.Functor
import storymodel4s.core.*

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

  given Functor[Estimate] with
    def map[A, B](fa: Estimate[A])(f: A => B): Estimate[B] = fa.map(f)

type ScoreEstimate = Estimate[Double]

/** An estimate with a nonnegative weight, e.g. a kernel weight or a count mass. */
final case class WeightedEstimate[+V](estimate: Estimate[V], weight: Double)

/** How many targets could have carried a value and how many did.
  *
  * Why: sums and means over windows are confounded by lexicon coverage; every aggregate must say
  * how much of its support it actually observed.
  */
final case class Coverage(eligible: Int, observed: Int):
  require(eligible >= 0 && observed >= 0 && observed <= eligible, "coverage: observed ≤ eligible")
  def fraction: Double = if eligible == 0 then 0.0 else observed.toDouble / eligible
  def missing: Int = eligible - observed
  def isEmpty: Boolean = eligible == 0
  def +(o: Coverage): Coverage = Coverage(eligible + o.eligible, observed + o.observed)

object Coverage:
  val empty: Coverage = Coverage(0, 0)
