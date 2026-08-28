package storymodel4s.acquire

/** Controlled transformations with known structural consequences (design record §88.2, §95). A
  * critic must prefer the source-supported original over each foil.
  */
enum FoilKind:
  case SwapRoles
  case FlipPolarity
  case EmbeddedToRoot
  case IntendedToRealized
  case DuplicateRetrospective
  case RemoveCausalCue

/** An original and its transformed counterpart. */
final case class Foil[A](kind: FoilKind, original: A, foil: A, note: Option[String])

/** Produces foils for a candidate of type `A` (a chart, a sentence, a relation). Concrete
  * generators live with the types they transform; this module only defines the contract.
  */
trait FoilGenerator[A]:
  def supports(kind: FoilKind): Boolean
  def foils(original: A, kinds: Set[FoilKind]): Vector[Foil[A]]

/** Whether the critic preferred the original over one foil, with the codes it emitted. */
final case class FoilOutcome(
    kind: FoilKind,
    criticPreferredOriginal: Boolean,
    codes: Vector[FindingCode]
)

/** Per-kind aggregation of foil outcomes. */
final case class FoilKindStats(kind: FoilKind, trials: Int, preferredOriginal: Int):
  def preferenceRate: Option[Double] =
    if trials == 0 then None else Some(preferredOriginal.toDouble / trials)

final case class FoilReport(outcomes: Vector[FoilOutcome]):
  def byKind: Map[FoilKind, FoilKindStats] =
    FoilKind.values.iterator.map { k =>
      val os = outcomes.filter(_.kind == k)
      k -> FoilKindStats(k, os.size, os.count(_.criticPreferredOriginal))
    }.toMap

  def preferenceRate(kind: FoilKind): Option[Double] = byKind(kind).preferenceRate

  /** Preference rate over all trials, `None` when there were none. */
  def overall: Option[Double] =
    if outcomes.isEmpty then None
    else Some(outcomes.count(_.criticPreferredOriginal).toDouble / outcomes.size)

  def failures: Vector[FoilOutcome] = outcomes.filterNot(_.criticPreferredOriginal)

  /** Whether every exercised kind meets `minRate`. `None` when no foil was tried at all: a gate
    * with zero trials is undefined, never vacuously passed (review finding #36). Kinds with no
    * trials are listed in [[untried]] and do not count against the rate.
    */
  def passes(minRate: Double): Option[Boolean] =
    if outcomes.isEmpty then None
    else Some(byKind.values.forall(_.preferenceRate.forall(_ >= minRate)))

  /** Kinds with no trials in this report. */
  def untried: Set[FoilKind] = byKind.values.filter(_.trials == 0).map(_.kind).toSet

  def ++(other: FoilReport): FoilReport = FoilReport(outcomes ++ other.outcomes)

object FoilReport:
  val empty: FoilReport = FoilReport(Vector.empty)
